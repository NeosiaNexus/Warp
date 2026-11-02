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
package dev.warp.proxy.limit;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import org.jspecify.annotations.Nullable;

/**
 * The online-mode authentications the proxy runs at once (the RSA decryption and the session server
 * request), and the line logins wait in when they are all taken: the authentication priority lane
 * of the anti-bot research (section 9.4).
 *
 * <p>During a fake-authentication flood, those slots are the scarce resource. A login that finds
 * them all taken, or others already waiting, waits instead of being refused: players the anti-bot
 * trusts (their name and network are known) get the next free slot first, the others wait in
 * arrival order. Each line holds at most {@code queueSize} logins, each for at most {@code
 * maxWait}; beyond, the login is refused. Returning players log in during a flood; unknown ones
 * wait their turn instead of timing out.
 *
 * <p>A login that never waited takes its slot at its encryption response, as before: a bot that
 * stalls earlier holds none. A login granted a slot from the line holds it from then on, its
 * encryption request sent, for at most {@code maxWait} until its response.
 *
 * <p>Lock-free; every structure is bounded. Waiters time out on their own event loop.
 */
public final class AuthenticationLane {

  private static final int WAITING = 0;
  private static final int GRANTED = 1;
  private static final int CANCELLED = 2;

  private final int max;
  private final int queueSize;
  private final Duration maxWait;
  private final LongSupplier nanoClock;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final Queue<Waiter> trustedLine = new ConcurrentLinkedQueue<>();
  private final Queue<Waiter> unknownLine = new ConcurrentLinkedQueue<>();
  private final AtomicInteger trustedWaiting = new AtomicInteger();
  private final AtomicInteger unknownWaiting = new AtomicInteger();
  private final LongAdder grantedFromLine = new LongAdder();
  private final LongAdder waitedNanos = new LongAdder();

  /**
   * Creates a lane.
   *
   * @param max the most authentications at once; 0 for no limit, and no line
   * @param queueSize the most logins each line holds; 0 to refuse at once when full
   * @param maxWait the longest a login waits in line, and holds a slot granted from it before its
   *     encryption response
   * @param nanoClock a monotonic clock, for the waiting times reported
   */
  public AuthenticationLane(int max, int queueSize, Duration maxWait, LongSupplier nanoClock) {
    if (max < 0 || queueSize < 0) {
      throw new IllegalArgumentException("max and queueSize must not be negative");
    }
    this.max = max;
    this.queueSize = queueSize;
    this.maxWait = maxWait;
    this.nanoClock = nanoClock;
  }

  // ---------------------------------------------------------------------------
  // Slots
  // ---------------------------------------------------------------------------

  /**
   * Returns whether a login that starts now must wait: every slot is taken, or others wait already.
   *
   * @return {@code true} to queue the login
   */
  public boolean mustWait() {
    return max != 0 && (inFlight.get() >= max || waiting() > 0);
  }

  /**
   * Takes a slot if one is free and nobody waits for it.
   *
   * @return the slot, or {@code null}
   */
  public @Nullable Slot tryAcquire() {
    if (max == 0) {
      return new Slot(false);
    }
    if (waiting() > 0) {
      return null;
    }
    while (true) {
      int current = inFlight.get();
      if (current >= max) {
        return null;
      }
      if (inFlight.compareAndSet(current, current + 1)) {
        return new Slot(true);
      }
    }
  }

  /**
   * Puts a login in line for the next free slot.
   *
   * @param trusted whether the anti-bot trusts the login's name and network: it goes first
   * @param loop the login's event loop, where {@code granted} and {@code expired} run
   * @param granted given the slot once it is free; it must release it, or the login's end must
   * @param expired run if no slot came within the longest wait
   * @return the place in line, to cancel when the login ends, or {@code null} if the line is full
   */
  public @Nullable Waiter enqueue(
      boolean trusted, EventExecutor loop, Consumer<Slot> granted, Runnable expired) {
    AtomicInteger count = trusted ? trustedWaiting : unknownWaiting;
    if (count.incrementAndGet() > queueSize) {
      count.decrementAndGet();
      return null;
    }
    Waiter waiter = new Waiter(trusted, loop, granted, expired, nanoClock.getAsLong());
    (trusted ? trustedLine : unknownLine).add(waiter);
    waiter.timeout = loop.schedule(waiter::timedOut, maxWait.toMillis(), TimeUnit.MILLISECONDS);
    dispatch();
    return waiter;
  }

  /** Hands free slots to the logins waiting, trusted ones first. */
  private void dispatch() {
    while (waiting() > 0) {
      int current = inFlight.get();
      if (current >= max) {
        return;
      }
      if (!inFlight.compareAndSet(current, current + 1)) {
        continue;
      }
      Waiter next = claimNext();
      if (next == null) {
        // Taken by a timeout meanwhile: give the slot back and look again.
        inFlight.decrementAndGet();
        continue;
      }
      next.grant(new Slot(true));
    }
  }

  private @Nullable Waiter claimNext() {
    Waiter waiter = claim(trustedLine, trustedWaiting);
    return waiter != null ? waiter : claim(unknownLine, unknownWaiting);
  }

  private static @Nullable Waiter claim(Queue<Waiter> line, AtomicInteger count) {
    for (Waiter waiter = line.poll(); waiter != null; waiter = line.poll()) {
      if (waiter.state.compareAndSet(WAITING, GRANTED)) {
        count.decrementAndGet();
        return waiter;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------

  /**
   * Returns the most authentications at once.
   *
   * @return the limit, or 0 for none
   */
  public int max() {
    return max;
  }

  /**
   * Returns how many slots are taken.
   *
   * @return the count
   */
  public int inFlight() {
    return inFlight.get();
  }

  /**
   * Returns how many logins wait in line.
   *
   * @return the count, both lines
   */
  public int waiting() {
    return trustedWaiting.get() + unknownWaiting.get();
  }

  /**
   * Returns how many logins were granted a slot from the line since the start.
   *
   * @return the count
   */
  public long grantedFromLine() {
    return grantedFromLine.sum();
  }

  /**
   * Returns how long the logins granted a slot from the line waited, in all, since the start.
   *
   * @return the total, in seconds
   */
  public double waitedSeconds() {
    return waitedNanos.sum() / 1e9;
  }

  /**
   * Returns the longest a login waits.
   *
   * @return the wait
   */
  public Duration maxWait() {
    return maxWait;
  }

  // ---------------------------------------------------------------------------
  // Slot and waiter
  // ---------------------------------------------------------------------------

  /** One authentication in flight, to release exactly once (later calls do nothing). */
  public final class Slot {

    private final boolean counted;
    private final AtomicBoolean released = new AtomicBoolean();

    private Slot(boolean counted) {
      this.counted = counted;
    }

    /** Gives the slot back, to the next login in line if any. Idempotent, from any thread. */
    public void release() {
      if (counted && released.compareAndSet(false, true)) {
        inFlight.decrementAndGet();
        dispatch();
      }
    }
  }

  /** A login waiting in line. */
  public final class Waiter {

    private final boolean trusted;
    private final EventExecutor loop;
    private final Consumer<Slot> granted;
    private final Runnable expired;
    private final long since;
    private final AtomicInteger state = new AtomicInteger(WAITING);
    private volatile @Nullable ScheduledFuture<?> timeout;

    private Waiter(
        boolean trusted, EventExecutor loop, Consumer<Slot> granted, Runnable expired, long since) {
      this.trusted = trusted;
      this.loop = loop;
      this.granted = granted;
      this.expired = expired;
      this.since = since;
    }

    private void grant(Slot slot) {
      cancelTimeout();
      grantedFromLine.increment();
      waitedNanos.add(nanoClock.getAsLong() - since);
      loop.execute(() -> granted.accept(slot));
    }

    private void timedOut() {
      if (leave()) {
        expired.run();
      }
    }

    /** Leaves the line, the login gone. Does nothing once a slot was granted. */
    public void cancel() {
      if (leave()) {
        cancelTimeout();
      }
    }

    private boolean leave() {
      if (!state.compareAndSet(WAITING, CANCELLED)) {
        return false;
      }
      (trusted ? trustedLine : unknownLine).remove(this);
      (trusted ? trustedWaiting : unknownWaiting).decrementAndGet();
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
