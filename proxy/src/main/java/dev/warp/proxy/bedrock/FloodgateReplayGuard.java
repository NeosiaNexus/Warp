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
package dev.warp.proxy.bedrock;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Remembers the blobs recently accepted, so that one captured between Geyser and the proxy cannot
 * log in twice. Floodgate's data carries no timestamp or nonce of its own: Geyser encrypts it
 * afresh for each connection under a random IV, so a blob seen twice is a replay, never a
 * reconnecting player.
 *
 * <p>Keyed by the blob's authentication tag, which the IV and the key make unique per encryption.
 * Bounded twice: an entry expires after the window, and the table holds at most {@code capacity}
 * entries, the oldest making room beyond that, so that a flood of valid-looking blobs (which needs
 * the key) cannot grow memory. Lock-free on the accept path: one {@code putIfAbsent}; the sweep
 * that drops expired entries runs when the table is full, by the thread that filled it.
 */
public final class FloodgateReplayGuard {

  /** The window of the proxy's guard: longer than any login, shorter than a session. */
  public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(10);

  /** How many blobs the proxy's guard remembers at most. */
  public static final int DEFAULT_CAPACITY = 65_536;

  private final long windowNanos;
  private final int capacity;
  private final LongSupplier nanoClock;

  /** Tag to the clock reading at which the entry expires. */
  private final ConcurrentHashMap<ByteBuffer, Long> seen = new ConcurrentHashMap<>();

  /**
   * Creates a guard.
   *
   * @param window how long a blob is remembered
   * @param capacity how many blobs are remembered at most
   * @param nanoClock a monotonic clock in nanoseconds
   */
  public FloodgateReplayGuard(Duration window, int capacity, LongSupplier nanoClock) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be positive, got " + capacity);
    }
    this.windowNanos = window.toNanos();
    this.capacity = capacity;
    this.nanoClock = nanoClock;
  }

  /**
   * Records a blob and says whether it is new.
   *
   * @param tag the blob's authentication tag
   * @return {@code true} the first time this tag is seen within the window, {@code false} for a
   *     replay
   */
  public boolean accept(byte[] tag) {
    long now = nanoClock.getAsLong();
    ByteBuffer key = ByteBuffer.wrap(tag.clone());
    if (seen.size() >= capacity) {
      sweep(now);
    }
    Long previous = seen.putIfAbsent(key, now + windowNanos);
    if (previous == null) {
      return true;
    }
    if (now - previous >= 0) {
      // Expired but not swept yet: the window is over, a new login under this tag is not a replay
      // of one still in progress. Take the slot.
      seen.put(key, now + windowNanos);
      return true;
    }
    return false;
  }

  /** Drops expired entries, then, if still full, the entries met first until under capacity. */
  private void sweep(long now) {
    Iterator<Map.Entry<ByteBuffer, Long>> entries = seen.entrySet().iterator();
    while (entries.hasNext()) {
      if (now - entries.next().getValue() >= 0) {
        entries.remove();
      }
    }
    entries = seen.entrySet().iterator();
    while (seen.size() >= capacity && entries.hasNext()) {
      entries.next();
      entries.remove();
    }
  }

  /**
   * Returns how many blobs are remembered, expired ones included until the next sweep.
   *
   * @return the size
   */
  public int size() {
    return seen.size();
  }
}
