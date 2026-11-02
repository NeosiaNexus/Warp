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
package dev.warp.proxy.connection;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Lets one log line through per interval for an event that peers can trigger at will, and counts
 * the ones it holds back, so that a port scan or a flood cannot fill the logs while the first
 * occurrence, the one an operator needs to see, is still logged at once.
 *
 * <p>Lock-free: one compare-and-set per permitted line, an atomic increment per suppressed one.
 */
final class LogThrottle {

  private final long intervalNanos;
  private final LongSupplier nanoClock;

  /** When the next line may be logged, on {@link #nanoClock}'s scale. */
  private final AtomicLong nextPermitted;

  /** Events held back since the last line. */
  private final AtomicLong suppressed = new AtomicLong();

  /**
   * Creates a throttle on the system's monotonic clock.
   *
   * @param interval the minimum time between two lines
   */
  LogThrottle(Duration interval) {
    this(interval, System::nanoTime);
  }

  /**
   * Creates a throttle on the given clock.
   *
   * @param interval the minimum time between two lines
   * @param nanoClock a monotonic clock in nanoseconds, such as {@link System#nanoTime()}
   */
  LogThrottle(Duration interval, LongSupplier nanoClock) {
    this.intervalNanos = interval.toNanos();
    this.nanoClock = nanoClock;
    this.nextPermitted = new AtomicLong(nanoClock.getAsLong());
  }

  /**
   * Records one event and says whether to log it.
   *
   * @return {@code -1} to keep quiet, or, when the event is to be logged, how many events were held
   *     back since the previous line
   */
  long tryAcquire() {
    long now = nanoClock.getAsLong();
    long next = nextPermitted.get();
    if (now - next >= 0 && nextPermitted.compareAndSet(next, now + intervalNanos)) {
      return suppressed.getAndSet(0);
    }
    suppressed.incrementAndGet();
    return -1;
  }

  /**
   * Returns the text to append to a permitted line: nothing when no event was held back, or how
   * many were.
   *
   * @param suppressed the count {@link #tryAcquire()} returned
   * @param what the events, plural, as in {@code "refused connections"}
   * @return {@code ""}, or e.g. {@code " (42 more refused connections since the last warning)"}
   */
  static String suffix(long suppressed, String what) {
    if (suppressed <= 0) {
      return "";
    }
    return " (" + suppressed + " more " + what + " since the last warning)";
  }
}
