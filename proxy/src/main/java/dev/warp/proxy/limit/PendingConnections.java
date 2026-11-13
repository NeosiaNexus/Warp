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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * The connections that are not logged in, for the whole proxy: their count, its cap, and, when the
 * cap is reached, which of them to close so that a new connection still gets in.
 *
 * <h2>Why close an old connection rather than refuse the new one</h2>
 *
 * <p>A game sends its handshake with its first packet and its next packet right behind; a flood
 * opens sockets and says nothing, or says a little and stalls. With a cap that refuses the
 * newcomer, whoever fills the table with silent sockets locks every player out for as long as it
 * keeps them open. With no cap, memory and file descriptors have no bound. So the cap stays, and
 * when it is reached the place goes to the newcomer, taken from the connection that has been
 * waiting on its client the longest (the oldest half-open connection of a SYN backlog, the oldest
 * idle keep-alive connection of a full nginx worker).
 *
 * <h2>Who may be closed</h2>
 *
 * <ul>
 *   <li>First the <b>silent</b> connections, which have not sent a whole handshake, oldest first.
 *       Bytes trickled in are not progress: only a complete packet is.
 *   <li>Then the <b>engaged</b> ones, which sent a handshake and now keep the proxy waiting for the
 *       next packet (the request of a server list ping, the name of a login), least recent progress
 *       first.
 *   <li>Never a connection younger, or whose last progress is more recent, than the grace: it has
 *       not had the time to speak, and closing it would turn the flood into a way to kill the
 *       players who just arrived.
 *   <li>Never a login that gave its name: it may be waiting on the proxy itself (the session
 *       server, a verification), and the limits per address and the anti-bot bound those.
 * </ul>
 *
 * <p>When nothing may be closed, the newcomer is refused, as before.
 *
 * <h2>Threads</h2>
 *
 * <p>The count is one atomic integer. The connections that may be closed are kept per event loop,
 * in two intrusive lists in order of last progress, which only that loop touches: tracking a
 * connection, moving it and closing the oldest are a few field writes, with no lock and no
 * allocation. A newcomer first takes its place from a connection of its own event loop, at once.
 * Only if that loop has none does it look at the others, through the age of the oldest connection
 * each publishes, and asks the one with the oldest to close it: the newcomer is let in meanwhile,
 * so the count may exceed the cap by up to {@link #slack()} until that loop has run.
 */
public final class PendingConnections {

  /** The most connections the count may hold over the cap while another loop makes room. */
  private static final int MAX_SLACK = 32;

  /** The share of the cap the slack is, at most: one eighth. */
  private static final int SLACK_SHARE = 8;

  private final int max;
  private final long graceNanos;
  private final int slack;
  private final LongSupplier nanoClock;

  /** The clock's value at creation: times are kept from it, so that they compare as plain longs. */
  private final long epoch;

  private final AtomicInteger count = new AtomicInteger();

  /** The lists of every event loop that tracked a connection, for the life of the proxy. */
  private final CopyOnWriteArrayList<Loop> loops = new CopyOnWriteArrayList<>();

  private final ThreadLocal<@Nullable Loop> local = new ThreadLocal<>();

  /**
   * Creates the table.
   *
   * @param max the most connections not logged in at once, or 0 for no cap
   * @param grace how long a connection must have made no progress before it may be closed to make
   *     room; zero to never close one, and refuse the newcomers instead
   * @param nanoClock a monotonic clock in nanoseconds, such as {@link System#nanoTime()}
   */
  public PendingConnections(int max, Duration grace, LongSupplier nanoClock) {
    this.max = max;
    this.graceNanos = max == 0 ? 0 : grace.toNanos();
    this.slack = Math.clamp(max / SLACK_SHARE, 1, MAX_SLACK);
    this.nanoClock = nanoClock;
    this.epoch = nanoClock.getAsLong();
  }

  // ---------------------------------------------------------------------------
  // Count
  // ---------------------------------------------------------------------------

  /**
   * Counts a new connection, closing an older one to make room if the table is full. Every
   * successful call must be followed by exactly one {@link #end()}. Called on the event loop of the
   * new connection.
   *
   * @return {@code false} if the table is full and nothing in it may be closed: the connection is
   *     not counted
   */
  public boolean tryStart() {
    int now = count.incrementAndGet();
    if (max == 0 || now <= max || (graceNanos != 0 && makeRoom(now))) {
      return true;
    }
    count.decrementAndGet();
    return false;
  }

  /** Gives back the place of a connection counted by {@link #tryStart()}. */
  public void end() {
    count.decrementAndGet();
  }

  /**
   * Returns how many connections are not logged in.
   *
   * @return the count
   */
  public int count() {
    return count.get();
  }

  /**
   * Returns the cap.
   *
   * @return the most connections not logged in at once, or 0 for no cap
   */
  public int max() {
    return max;
  }

  /**
   * Returns by how many connections the count may exceed the cap, for the time another event loop
   * takes to close the connection a newcomer was let in for: an eighth of the cap, between 1 and
   * 32.
   *
   * @return the slack
   */
  public int slack() {
    return slack;
  }

  // ---------------------------------------------------------------------------
  // Progress of a connection
  // ---------------------------------------------------------------------------

  /**
   * Tracks a connection that was just counted and has sent nothing: from now on it may be closed to
   * make room, once the grace has passed. Called on the connection's event loop.
   *
   * @param connection the connection, never tracked before
   * @param eventLoop the connection's event loop, which runs what other loops ask of it
   */
  public void track(Entry connection, Executor eventLoop) {
    if (graceNanos == 0) {
      return;
    }
    Loop loop = local.get();
    if (loop == null) {
      loop = new Loop(eventLoop);
      local.set(loop);
      loops.add(loop);
    }
    connection.home = loop;
    loop.append(loop.silent, connection, elapsed());
  }

  /**
   * Notes that a tracked connection sent a complete packet and is now expected to send the next: it
   * becomes the last of its event loop to be closed among those that keep the proxy waiting. Does
   * nothing for a connection that is not tracked, or no longer may be closed.
   *
   * @param connection the connection
   */
  public void progressed(Entry connection) {
    Loop loop = connection.home;
    if (loop == null || connection.queue == null) {
      return;
    }
    loop.remove(connection);
    loop.append(loop.engaged, connection, elapsed());
  }

  /**
   * Takes a connection out of those that may be closed to make room: it is logged in, gone, or a
   * login the proxy itself is now working on. It keeps its place in the count. Does nothing for a
   * connection that is not tracked.
   *
   * @param connection the connection
   */
  public void settle(Entry connection) {
    Loop loop = connection.home;
    if (loop != null) {
      loop.remove(connection);
    }
  }

  // ---------------------------------------------------------------------------
  // Making room
  // ---------------------------------------------------------------------------

  /**
   * Makes room for a newcomer that found the table full.
   *
   * @param counted the count with the newcomer in it
   * @return whether the newcomer may stay
   */
  private boolean makeRoom(int counted) {
    long time = elapsed();
    Loop own = local.get();
    if (own != null && own.makeRoom(time)) {
      return true;
    }
    if (counted > max + slack) {
      return false;
    }
    Loop oldest = null;
    long oldestSince = Long.MAX_VALUE;
    for (Loop loop : loops) {
      long since = loop.oldest;
      if (loop != own && since < oldestSince) {
        oldest = loop;
        oldestSince = since;
      }
    }
    if (oldest == null || time - oldestSince < graceNanos) {
      return false;
    }
    oldest.request();
    return true;
  }

  private long elapsed() {
    return nanoClock.getAsLong() - epoch;
  }

  // ---------------------------------------------------------------------------
  // One connection
  // ---------------------------------------------------------------------------

  /**
   * A connection that is not logged in, as the table sees it: a node of the list its event loop
   * keeps of the connections it may close to make room for a new one. The links live in the
   * connection's own watcher, which extends this class, so that tracking a connection allocates
   * nothing. Every field is read and written on the connection's event loop only.
   */
  public abstract static class Entry {

    /** The event loop's lists the connection was tracked on, or {@code null} if it never was. */
    private @Nullable Loop home;

    /** The list the connection is in, or {@code null} while nothing may close it. */
    private @Nullable Fifo queue;

    /** The neighbours in {@link #queue}, towards the oldest and the newest. */
    private @Nullable Entry older;

    private @Nullable Entry newer;

    /** When the connection last made progress, on the table's clock. */
    private long since;

    /** Creates a connection nothing tracks yet. */
    protected Entry() {}

    /**
     * Closes the connection to make room for a new one. Called on the connection's event loop,
     * once, after it left the lists. The implementation must give the connection's place back
     * ({@link PendingConnections#end()}, exactly once over the connection's life, as for any other
     * close) and close it without blocking.
     */
    protected abstract void evicted();
  }

  // ---------------------------------------------------------------------------
  // One event loop's connections
  // ---------------------------------------------------------------------------

  /** A list of connections in order of last progress; touched by its event loop only. */
  private static final class Fifo {

    private @Nullable Entry oldest;
    private @Nullable Entry newest;

    private Fifo() {}
  }

  /**
   * The connections of one event loop that may be closed to make room. Everything but {@link
   * #oldest} and {@link #request()} is used on that loop only.
   */
  private final class Loop implements Runnable {

    private final Executor executor;
    private final Fifo silent = new Fifo();
    private final Fifo engaged = new Fifo();

    /**
     * When the connection of this loop that made progress the longest ago did, or {@link
     * Long#MAX_VALUE} without one: what the other loops read to choose whom to ask.
     */
    private volatile long oldest = Long.MAX_VALUE;

    /** Whether another loop asked this one to make room, and it has not run yet. */
    private final AtomicBoolean requested = new AtomicBoolean();

    private Loop(Executor executor) {
      this.executor = executor;
    }

    /** Asks this loop, from another, to close connections until the count is back under the cap. */
    private void request() {
      if (requested.compareAndSet(false, true)) {
        try {
          executor.execute(this);
        } catch (RejectedExecutionException _) {
          // The loop is shutting down, and closes its connections itself.
          requested.set(false);
        }
      }
    }

    @Override
    public void run() {
      requested.set(false);
      var _ = makeRoom(elapsed());
    }

    /**
     * Closes connections of this loop, the one that kept the proxy waiting the longest first, until
     * the count is back under the cap or none is left that may be closed.
     *
     * @return whether it closed any
     */
    private boolean makeRoom(long time) {
      boolean closed = false;
      while (count.get() > max) {
        Entry victim = victim(silent, time);
        if (victim == null) {
          victim = victim(engaged, time);
        }
        if (victim == null) {
          break;
        }
        remove(victim);
        victim.evicted();
        closed = true;
      }
      return closed;
    }

    private @Nullable Entry victim(Fifo queue, long time) {
      Entry candidate = queue.oldest;
      return candidate != null && time - candidate.since >= graceNanos ? candidate : null;
    }

    private void append(Fifo queue, Entry connection, long time) {
      Entry last = queue.newest;
      connection.queue = queue;
      connection.since = time;
      connection.older = last;
      connection.newer = null;
      queue.newest = connection;
      if (last == null) {
        queue.oldest = connection;
      } else {
        last.newer = connection;
      }
      publish();
    }

    private void remove(Entry connection) {
      Fifo queue = connection.queue;
      if (queue == null) {
        return;
      }
      Entry before = connection.older;
      Entry after = connection.newer;
      if (before == null) {
        queue.oldest = after;
      } else {
        before.newer = after;
      }
      if (after == null) {
        queue.newest = before;
      } else {
        after.older = before;
      }
      connection.queue = null;
      connection.older = null;
      connection.newer = null;
      publish();
    }

    private void publish() {
      Entry first = silent.oldest;
      Entry second = engaged.oldest;
      long since =
          Math.min(
              first == null ? Long.MAX_VALUE : first.since,
              second == null ? Long.MAX_VALUE : second.since);
      if (oldest != since) {
        oldest = since;
      }
    }
  }
}
