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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QueryRateLimiter")
class QueryRateLimiterTest {

  private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

  @Test
  @DisplayName("should allow the burst at once, then one per interval")
  void burstThenRate() {
    QueryRateLimiter limiter = new QueryRateLimiter(16, 5, 10);

    int allowed = 0;
    for (int i = 0; i < 20; i++) {
      if (limiter.tryAcquire(1, 0)) {
        allowed++;
      }
    }
    assertEquals(10, allowed);
    assertFalse(limiter.tryAcquire(1, SECOND / 5 - 1));
    assertTrue(limiter.tryAcquire(1, SECOND / 5));
  }

  @Test
  @DisplayName("should refill fully after burst / rate seconds of silence")
  void refills() {
    QueryRateLimiter limiter = new QueryRateLimiter(16, 5, 10);
    for (int i = 0; i < 10; i++) {
      assertTrue(limiter.tryAcquire(1, 0));
    }

    int allowed = 0;
    for (int i = 0; i < 20; i++) {
      if (limiter.tryAcquire(1, 2 * SECOND)) {
        allowed++;
      }
    }
    assertEquals(10, allowed);
  }

  @Test
  @DisplayName("should keep keys apart")
  void keysApart() {
    QueryRateLimiter limiter = new QueryRateLimiter(16, 1, 1);

    assertTrue(limiter.tryAcquire(1, 0));
    assertFalse(limiter.tryAcquire(1, 0));
    assertTrue(limiter.tryAcquire(2, 0));
  }

  @Test
  @DisplayName("should give a displaced key a fresh bucket, never a stricter one")
  void displacedKeyStartsFresh() {
    // One slot: every key displaces the previous one.
    QueryRateLimiter limiter = new QueryRateLimiter(1, 1, 1);

    assertTrue(limiter.tryAcquire(1, 0));
    assertFalse(limiter.tryAcquire(1, 0));
    assertTrue(limiter.tryAcquire(2, 0));
    assertTrue(limiter.tryAcquire(1, 0));
  }

  @Test
  @DisplayName("should act as a single global bucket with one slot and key 0")
  void global() {
    QueryRateLimiter limiter = new QueryRateLimiter(1, 2000, 2000);

    int allowed = 0;
    for (int i = 0; i < 5000; i++) {
      if (limiter.tryAcquire(0, 0)) {
        allowed++;
      }
    }
    assertEquals(2000, allowed);
    assertTrue(limiter.tryAcquire(0, SECOND / 2000));
  }

  @Test
  @DisplayName("should survive a wrapping clock")
  void clockWraps() {
    QueryRateLimiter limiter = new QueryRateLimiter(16, 1, 1);
    long now = Long.MAX_VALUE - 10;

    assertTrue(limiter.tryAcquire(1, now));
    assertFalse(limiter.tryAcquire(1, now + 20));
    assertTrue(limiter.tryAcquire(1, now + SECOND));
  }

  @Test
  @DisplayName("should refuse a slot count that is not a power of two")
  void powerOfTwo() {
    assertThrows(IllegalArgumentException.class, () -> new QueryRateLimiter(12, 1, 1));
  }
}
