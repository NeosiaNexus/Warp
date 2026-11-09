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
package dev.warp.proxy.query;

import java.util.concurrent.TimeUnit;

/**
 * Token buckets in a table of fixed size, one per key, indexed by a hash of the key.
 *
 * <p>The same generic cell rate algorithm as {@link dev.warp.proxy.limit.RateLimiter}, without its
 * cache: a UDP flood must cost nothing per datagram, so the buckets live in two arrays sized once.
 * A key that lands on a slot another key holds takes the slot over with a fresh bucket; the
 * displaced key starts afresh too when it comes back. A flood from many addresses therefore makes
 * the per-address limit more lenient, never stricter, for the addresses it displaces, and the
 * global bucket (a table of one slot) bounds the whole.
 *
 * <p>Not thread-safe: the query listener has one channel on one event loop.
 */
final class QueryRateLimiter {

  /** Slots of the per-address table: a power of two, 256 KB of arrays. */
  static final int ADDRESS_SLOTS = 16_384;

  private final long[] keys;

  /** The theoretical arrival time of each slot's next allowed datagram. */
  private final long[] arrivals;

  private final int mask;
  private final long intervalNanos;

  /** How far ahead of now the theoretical arrival time may be: {@code (burst - 1) × interval}. */
  private final long toleranceNanos;

  /**
   * Creates a limiter.
   *
   * @param slots how many keys it tracks at once; a power of two
   * @param perSecond datagrams per second a key may send once its burst is spent; at least 1
   * @param burst how many datagrams a key may send at once; at least 1
   */
  QueryRateLimiter(int slots, int perSecond, int burst) {
    if (Integer.bitCount(slots) != 1) {
      throw new IllegalArgumentException("slots must be a power of two, got " + slots);
    }
    if (perSecond < 1 || burst < 1) {
      throw new IllegalArgumentException("perSecond and burst must be at least 1");
    }
    this.keys = new long[slots];
    this.arrivals = new long[slots];
    this.mask = slots - 1;
    this.intervalNanos = TimeUnit.SECONDS.toNanos(1) / perSecond;
    this.toleranceNanos = (burst - 1) * intervalNanos;
  }

  /**
   * Takes one token of a key's bucket if it has one.
   *
   * @param key the key, such as {@link dev.warp.proxy.limit.AddressKey#of(byte[])}, or 0 for the
   *     global bucket
   * @param now the current {@link System#nanoTime()}
   * @return {@code true} if the key may act now
   */
  boolean tryAcquire(long key, long now) {
    int slot = (int) (key ^ (key >>> 32) ^ (key >>> 17)) & mask;
    long theoretical;
    if (keys[slot] != key || arrivals[slot] == 0) {
      // Another key's slot, or a slot never used: a fresh bucket.
      keys[slot] = key;
      theoretical = now;
    } else {
      theoretical = arrivals[slot];
    }
    // Differences, not comparisons, so that the clock may wrap.
    if (theoretical - toleranceNanos - now > 0) {
      return false;
    }
    long base = theoretical - now > 0 ? theoretical : now;
    arrivals[slot] = base + intervalNanos;
    return true;
  }
}
