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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * A token bucket per key, as the generic cell rate algorithm (GCRA): each key may act {@code burst}
 * times at once, then once every {@code interval}, the bucket refilling continuously.
 *
 * <h3>Why GCRA</h3>
 *
 * <p>A token bucket allows short bursts, which real clients make (a player and a sibling joining
 * together, a quick retry after a mistyped address), while holding every key to the same sustained
 * rate. A cooldown, such as Velocity's login limiter, refuses the second attempt of any burst; a
 * fixed window, such as BungeeCord's connection throttle, lets twice its limit through around a
 * window boundary. GCRA computes the same decisions as a token bucket from a single number per key,
 * the time at which its bucket will be full again (the theoretical arrival time), so that a
 * decision is one compare-and-set on an {@link AtomicLong}: lock-free, and allocation-free for a
 * key already known.
 *
 * <h3>Memory</h3>
 *
 * <p>A key is forgotten once its bucket has been full for {@code burst × interval} without any
 * attempt, which is exactly when forgetting it changes nothing. The number of keys is capped too
 * ({@link #MAX_KEYS}): beyond it Caffeine evicts the keys it expects least, by frequency, so that
 * an attacker cycling through addresses cannot grow the proxy's memory, and evicts the addresses
 * that hammer the proxy last.
 */
final class RateLimiter {

  /** The most keys remembered at once: about 10 MB of entries. */
  static final int MAX_KEYS = 100_000;

  private final long intervalNanos;

  /** How far ahead of now the theoretical arrival time may be: {@code (burst - 1) × interval}. */
  private final long toleranceNanos;

  private final LongSupplier nanoClock;
  private final Cache<Long, AtomicLong> arrivals;

  /**
   * Creates a limiter.
   *
   * @param burst how many times a key may act at once; at least 1
   * @param interval the time it takes a key to earn one more; positive
   * @param nanoClock a monotonic clock in nanoseconds, such as {@link System#nanoTime()}
   */
  RateLimiter(int burst, Duration interval, LongSupplier nanoClock) {
    if (burst < 1) {
      throw new IllegalArgumentException("burst must be at least 1, got " + burst);
    }
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive, got " + interval);
    }
    this.intervalNanos = interval.toNanos();
    this.toleranceNanos = (burst - 1) * intervalNanos;
    this.nanoClock = nanoClock;
    this.arrivals =
        Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofNanos(burst * intervalNanos))
            .maximumSize(MAX_KEYS)
            .ticker(nanoClock::getAsLong)
            // Maintenance runs on the calling thread, amortised: no task for a shared pool.
            .executor(Runnable::run)
            .build();
  }

  /**
   * Takes one token of {@code key}'s bucket if it has one.
   *
   * @param key the key, such as {@link AddressKey#of(java.net.InetAddress)}
   * @return {@code 0} if the key may act now, otherwise how many nanoseconds until it may
   */
  long tryAcquire(long key) {
    long now = nanoClock.getAsLong();
    AtomicLong arrival = arrivals.getIfPresent(key);
    if (arrival == null) {
      AtomicLong fresh = new AtomicLong(now);
      arrival = arrivals.asMap().putIfAbsent(key, fresh);
      if (arrival == null) {
        arrival = fresh;
      }
    }
    while (true) {
      long theoretical = arrival.get();
      // Differences, not comparisons, so that the clock may wrap.
      long wait = theoretical - toleranceNanos - now;
      if (wait > 0) {
        return wait;
      }
      long base = theoretical - now > 0 ? theoretical : now;
      if (arrival.compareAndSet(theoretical, base + intervalNanos)) {
        return 0;
      }
    }
  }

  /** Returns how many keys are remembered, for tests. */
  long keys() {
    arrivals.cleanUp();
    return arrivals.estimatedSize();
  }
}
