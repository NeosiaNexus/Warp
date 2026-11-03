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
package dev.warp.proxy.console;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;

/**
 * The bounded queue between the logging thread and the thread that writes to the console. A slow
 * console (a terminal paused by a text selection, a panel that reads its pipe late) must never slow
 * the proxy down, so when the queue fills up:
 *
 * <ul>
 *   <li>DEBUG and TRACE lines are dropped once it is half full, leaving the room to the others;
 *   <li>INFO lines are dropped once it is full;
 *   <li>WARN and ERROR lines are never dropped: they go into a reserve beyond the capacity, and
 *       only a console stalled long enough to fill that reserve as well makes the logging thread
 *       wait. That thread is Log4j's, never one of Netty's, which only hand records to Log4j.
 * </ul>
 *
 * <p>Dropped lines are counted, and the console says how many once it catches up. The log file
 * keeps every line whatever happens here.
 */
final class ConsoleBuffer {

  private final int capacity;
  private final LinkedBlockingQueue<ConsoleLine> queue;
  private final AtomicLong dropped = new AtomicLong();

  /**
   * Creates a queue.
   *
   * @param capacity how many lines may wait, beyond which INFO lines are dropped
   * @param reserve how many more WARN and ERROR lines may wait before the logging thread does
   */
  ConsoleBuffer(int capacity, int reserve) {
    if (capacity < 2 || reserve < 0) {
      throw new IllegalArgumentException("capacity " + capacity + ", reserve " + reserve);
    }
    this.capacity = capacity;
    this.queue = new LinkedBlockingQueue<>(capacity + reserve);
  }

  /**
   * Queues a line, or drops it when the queue is too full for its level.
   *
   * @param line the line
   * @return whether the line was queued
   */
  boolean offer(ConsoleLine line) {
    Level level = line.level();
    int size = queue.size();
    if (level.isMoreSpecificThan(Level.WARN)) {
      try {
        queue.put(line);
        return true;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        dropped.incrementAndGet();
        return false;
      }
    }
    int limit = level.isMoreSpecificThan(Level.INFO) ? capacity : capacity / 2;
    if (size >= limit || !queue.offer(line)) {
      dropped.incrementAndGet();
      return false;
    }
    return true;
  }

  /**
   * Waits for the next line.
   *
   * @param timeoutMillis how long to wait
   * @return the line, or {@code null} after the timeout
   * @throws InterruptedException if interrupted while waiting
   */
  @Nullable ConsoleLine poll(long timeoutMillis) throws InterruptedException {
    return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Takes the next line, without waiting.
   *
   * @return the line, or {@code null} when none waits
   */
  @Nullable ConsoleLine poll() {
    return queue.poll();
  }

  /**
   * Moves the lines waiting, at most {@code max}, into {@code into}.
   *
   * @param into where to move them
   * @param max how many at most
   * @return how many were moved
   */
  int drainTo(Collection<ConsoleLine> into, int max) {
    return queue.drainTo(into, max);
  }

  /** Returns how many lines wait. */
  int size() {
    return queue.size();
  }

  /** Returns the lines dropped since the last call, and starts counting again. */
  long takeDropped() {
    return dropped.getAndSet(0);
  }
}
