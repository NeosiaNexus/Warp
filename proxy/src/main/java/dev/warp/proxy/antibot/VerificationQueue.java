/*
 * Copyright (C) 2026 Warp Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.warp.proxy.antibot;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import io.netty.util.concurrent.EventExecutor;
import org.jspecify.annotations.Nullable;

/**
 * The admission of unknown identities to verification (decision D9 of the anti-bot research): at
 * most {@code max-concurrent} verifications run at once, and the logins beyond them wait in a
 * bounded line that is fair between networks.
 *
 * <p><b>Fairness.</b> The line is not one FIFO, which a botnet with many connections from a few
 * networks would fill ahead of everyone, nor a hash map walked in hash order like Sonar's {@code
 * LoginQueue} (section 3.3 of the research), where who is served before timing out is a lottery. It
 * is stochastic fair queueing: {@value #BUCKETS} FIFO buckets, a source's network choosing its
 * bucket by a seeded hash, served round-robin one waiter per active bucket. A network with a
 * thousand waiting connections and a network with one are served alternately; the one waits behind
 * at most one connection of each other active network. Each bucket also holds at most a share of
 * the line, so that one network cannot fill it.
 *
 * <p><b>Costs.</b> Every operation is O(1): buckets are intrusive doubly-linked lists, active
 * buckets form a ring, and a waiter is the one object its login creates. Out of attacks, nothing
 * waits and {@link #tryAcquire} is one atomic increment: no lock is taken and nothing is allocated.
 * The lock guards the few pointer writes of the line itself, taken only when the line is used.
 *
 * <p><b>Known identities never queue</b>: they are not verified at all, so they never reach this
 * class (principle 2 of the research).
 *
 * <p>Thread-safe: logins arrive from every event loop.
 */
public final class VerificationQueue {

  /** The number of buckets sources are spread over. */
  static final int BUCKETS = 1024;

  private static final int WAITING = 0;
  private static final int GRANTED = 1;
  private static final int LEFT = 2;

  private final int max;
  private final int capacity;
  private final int perBucket;
  private final Duration maxWait;
  private final LongSupplier nanoClock;
  private final ReentrantLock lock = new ReentrantLock();

  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger waiting = new AtomicInteger();
  private final LongAdder grantedFromLine = new LongAdder();
  private final LongAdder expired = new LongAdder();
  private final LongAdder refused = new LongAdder();
  private final LongAdder waitedNanos = new LongAdder();

  // Guarded by the lock.
  private final Bucket[] buckets = new Bucket[BUCKETS];
  private @Nullable Bucket cursor;

  /**
   * Creates the queue.
   *
   * @param maxConcurrent the most verifications at once
   * @param capacity the most waiters in line; 0 for no line (busy at once when full)
   * @param maxWait how long a waiter may wait before it is told the proxy is busy
   * @param nanoClock the monotonic clock
   */
  public VerificationQueue(
      int maxConcurrent, int capacity, Duration maxWait, LongSupplier nanoClock) {
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException("maxConcurrent must be at least 1");
    }
    if (capacity < 0) {
      throw new IllegalArgumentException("capacity must not be negative");
    }
    this.max = maxConcurrent;
    this.capacity = capacity;
    // One network may hold at most a sixteenth of the line, and always at least a few places.
    this.perBucket = Math.max(4, capacity / 16);
    this.maxWait = maxWait;
    this.nanoClock = nanoClock;
    for (int i = 0; i < BUCKETS; i++) {
      buckets[i] = new Bucket();
    }
  }

  // ---------------------------------------------------------------------------
  // Admission
  // ---------------------------------------------------------------------------

  /**
   * Takes a verification slot at once, if one is free and nobody is waiting for it.
   *
   * @return the slot, or {@code null} to {@link #enqueue} instead
   */
  public @Nullable Slot tryAcquire() {
    if (waiting.get() > 0) {
      return null; // the line goes first
    }
    for (int current = inFlight.get(); current < max; current = inFlight.get()) {
      if (inFlight.compareAndSet(current, current + 1)) {
        return new Slot();
      }
    }
    return null;
  }

  /**
   * Puts a login in line for a slot, behind the other waiters of its network and alongside those of
   * the others. {@code granted} runs on {@code loop} with the slot, which the login releases when
   * its verification ends; {@code expired} runs on {@code loop} if no slot comes within the maximum
   * wait. Neither runs after {@link Waiter#cancel()}.
   *
   * @param networkHash the hash of the login's network ({@link IdentityKeys#networkHash})
   * @param loop the login's event loop
   * @param granted takes the slot
   * @param expired tells the login the proxy is busy
   * @return the waiter, or {@code null} if the line, or its network's share of it, is full
   */
  public @Nullable Waiter enqueue(
      int networkHash, EventExecutor loop, Consumer<Slot> granted, Runnable expired) {
    Bucket bucket = buckets[networkHash & (BUCKETS - 1)];
    Waiter waiter = new Waiter(bucket, loop, granted, expired, nanoClock.getAsLong());
    lock.lock();
    try {
      if (waiting.get() >= capacity || bucket.count >= perBucket) {
        refused.increment();
        return null;
      }
      bucket.append(waiter);
      waiting.incrementAndGet();
      if (bucket.count == 1) {
        activate(bucket);
      }
    } finally {
      lock.unlock();
    }
    waiter.timeout = loop.schedule(waiter::timedOut, maxWait.toNanos(), TimeUnit.NANOSECONDS);
    dispatch();
    return waiter;
  }

  /** Grants free slots to waiters, round-robin over the active buckets. */
  private void dispatch() {
    while (waiting.get() > 0) {
      Waiter next;
      lock.lock();
      try {
        Bucket bucket = cursor;
        if (bucket == null) {
          return;
        }
        // Take the slot while holding the lock: tryAcquire yields to the line, so the only race
        // is with other dispatches, which the lock orders.
        int current = inFlight.get();
        if (current >= max) {
          return;
        }
        next = bucket.removeFirst();
        waiting.decrementAndGet();
        if (bucket.count == 0) {
          deactivate(bucket);
        } else {
          cursor = bucket.nextActive;
        }
        if (!next.state.compareAndSet(WAITING, GRANTED)) {
          continue; // cancelled meanwhile: its place goes to the next
        }
        inFlight.incrementAndGet();
      } finally {
        lock.unlock();
      }
      next.grant(new Slot());
    }
  }

  private void activate(Bucket bucket) {
    Bucket at = cursor;
    if (at == null) {
      bucket.nextActive = bucket;
      bucket.prevActive = bucket;
      cursor = bucket;
    } else {
      // Behind the whole current round: just before the cursor.
      Bucket last = Objects.requireNonNull(at.prevActive, "an active bucket is in the ring");
      bucket.nextActive = at;
      bucket.prevActive = last;
      last.nextActive = bucket;
      at.prevActive = bucket;
    }
  }

  private void deactivate(Bucket bucket) {
    Bucket next = Objects.requireNonNull(bucket.nextActive, "an active bucket is in the ring");
    if (next == bucket) {
      cursor = null;
    } else {
      Bucket prev = Objects.requireNonNull(bucket.prevActive, "an active bucket is in the ring");
      prev.nextActive = next;
      next.prevActive = prev;
      if (cursor == bucket) {
        cursor = next;
      }
    }
    bucket.nextActive = null;
    bucket.prevActive = null;
  }

  // ---------------------------------------------------------------------------
  // Metrics
  // ---------------------------------------------------------------------------

  /**
   * Returns the most verifications at once.
   *
   * @return the cap
   */
  public int max() {
    return max;
  }

  /**
   * Returns the verifications running now.
   *
   * @return the count
   */
  public int inFlight() {
    return inFlight.get();
  }

  /**
   * Returns the logins waiting in line now.
   *
   * @return the count
   */
  public int waiting() {
    return waiting.get();
  }

  /**
   * Returns the most logins the line holds.
   *
   * @return the capacity
   */
  public int capacity() {
    return capacity;
  }

  /**
   * Returns how long a login may wait in line.
   *
   * @return the maximum wait
   */
  public Duration maxWait() {
    return maxWait;
  }

  /**
   * Returns how many logins got a slot from the line since the start.
   *
   * @return the count
   */
  public long grantedFromLine() {
    return grantedFromLine.sum();
  }

  /**
   * Returns how many logins waited the maximum and were told the proxy is busy.
   *
   * @return the count
   */
  public long expired() {
    return expired.sum();
  }

  /**
   * Returns how many logins found the line, or their network's share of it, full.
   *
   * @return the count
   */
  public long refused() {
    return refused.sum();
  }

  /**
   * Returns the time logins spent in line, in all.
   *
   * @return seconds
   */
  public double waitedSeconds() {
    return waitedNanos.sum() / 1e9;
  }

  // ---------------------------------------------------------------------------
  // Bucket, slot and waiter
  // ---------------------------------------------------------------------------

  /** The FIFO of one network's waiters; guarded by the queue's lock. */
  private static final class Bucket {
    private @Nullable Waiter head;
    private @Nullable Waiter tail;
    private int count;
    private @Nullable Bucket nextActive;
    private @Nullable Bucket prevActive;

    void append(Waiter waiter) {
      Waiter last = tail;
      waiter.prev = last;
      if (last == null) {
        head = waiter;
      } else {
        last.next = waiter;
      }
      tail = waiter;
      waiter.linked = true;
      count++;
    }

    Waiter removeFirst() {
      Waiter first = head;
      if (first == null) {
        throw new IllegalStateException("an active bucket is never empty");
      }
      unlink(first);
      return first;
    }

    void unlink(Waiter waiter) {
      Waiter before = waiter.prev;
      Waiter after = waiter.next;
      if (before == null) {
        head = after;
      } else {
        before.next = after;
      }
      if (after == null) {
        tail = before;
      } else {
        after.prev = before;
      }
      waiter.prev = null;
      waiter.next = null;
      waiter.linked = false;
      count--;
    }
  }

  /** A verification in progress, given back when it ends, however it ends. */
  public final class Slot {
    private final AtomicInteger released = new AtomicInteger();

    private Slot() {}

    /** Gives the slot back; the next waiter in line, if any, gets it. Idempotent. */
    public void release() {
      if (released.compareAndSet(0, 1)) {
        inFlight.decrementAndGet();
        dispatch();
      }
    }
  }

  /** A login waiting in line, and its place in its network's bucket. */
  public final class Waiter {
    private final Bucket bucket;
    private final EventExecutor loop;
    private final Consumer<Slot> granted;
    private final Runnable expired;
    private final long since;
    private final AtomicInteger state = new AtomicInteger(WAITING);
    private volatile @Nullable ScheduledFuture<?> timeout;
    private @Nullable Waiter prev;
    private @Nullable Waiter next;
    private boolean linked;

    private Waiter(
        Bucket bucket, EventExecutor loop, Consumer<Slot> granted, Runnable expired, long since) {
      this.bucket = bucket;
      this.loop = loop;
      this.granted = granted;
      this.expired = expired;
      this.since = since;
    }

    private void grant(Slot slot) {
      cancelTimeout();
      grantedFromLine.increment();
      waitedNanos.add(Math.max(0, nanoClock.getAsLong() - since));
      try {
        loop.execute(() -> granted.accept(slot));
      } catch (RuntimeException gone) {
        slot.release(); // the loop is shut down: the login is gone with it
      }
    }

    private void timedOut() {
      if (leave()) {
        VerificationQueue.this.expired.increment();
        expired.run();
      }
    }

    /** Leaves the line: the login went away before its turn. Idempotent. */
    public void cancel() {
      if (leave()) {
        cancelTimeout();
      }
    }

    /** Takes the waiter out of its bucket if it is still waiting; {@code true} if it was. */
    private boolean leave() {
      if (!state.compareAndSet(WAITING, LEFT)) {
        return false;
      }
      lock.lock();
      try {
        if (linked) { // a dispatch may have taken it out just before it left
          bucket.unlink(this);
          waiting.decrementAndGet();
          if (bucket.count == 0) {
            deactivate(bucket);
          }
        }
      } finally {
        lock.unlock();
      }
      return true;
    }

    private void cancelTimeout() {
      ScheduledFuture<?> pending = timeout;
      if (pending != null) {
        pending.cancel(false);
      }
    }
  }
}
