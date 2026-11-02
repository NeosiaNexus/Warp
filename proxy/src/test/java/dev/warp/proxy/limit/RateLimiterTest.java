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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RateLimiter")
class RateLimiterTest {

  private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

  private final AtomicLong now = new AtomicLong(1_000 * SECOND);

  // ---------------------------------------------------------------------------
  // Token bucket
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("token bucket")
  class TokenBucket {

    @Test
    @DisplayName("should allow a burst at once, then refuse")
    void burst() {
      RateLimiter limiter = limiter(3, Duration.ofSeconds(1));

      assertEquals(0, limiter.tryAcquire(1));
      assertEquals(0, limiter.tryAcquire(1));
      assertEquals(0, limiter.tryAcquire(1));
      assertTrue(limiter.tryAcquire(1) > 0);
    }

    @Test
    @DisplayName("should say how long until the next token")
    void waitUntilNextToken() {
      RateLimiter limiter = limiter(1, Duration.ofSeconds(3));
      limiter.tryAcquire(1);

      now.addAndGet(SECOND);

      assertEquals(2 * SECOND, limiter.tryAcquire(1));
    }

    @Test
    @DisplayName("should earn one token per interval")
    void refill() {
      RateLimiter limiter = limiter(2, Duration.ofSeconds(1));
      limiter.tryAcquire(1);
      limiter.tryAcquire(1);

      now.addAndGet(SECOND - 1);
      assertTrue(limiter.tryAcquire(1) > 0, "a nanosecond early");

      now.addAndGet(1);
      assertEquals(0, limiter.tryAcquire(1));
      assertTrue(limiter.tryAcquire(1) > 0, "only one token earned");
    }

    @Test
    @DisplayName("should hold a key to one per interval in the long run")
    void sustainedRate() {
      RateLimiter limiter = limiter(5, Duration.ofMillis(100));
      int allowed = 0;

      // An attempt every 10 ms for 10 s.
      for (int i = 0; i < 1000; i++) {
        if (limiter.tryAcquire(1) == 0) {
          allowed++;
        }
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
      }

      assertEquals(5 + 99, allowed, "the burst, then one every 100 ms");
    }

    @Test
    @DisplayName("should not count refused attempts")
    void refusedAttemptsAreFree() {
      RateLimiter limiter = limiter(1, Duration.ofSeconds(3));
      limiter.tryAcquire(1);
      for (int i = 0; i < 100; i++) {
        now.addAndGet(SECOND / 100);
        limiter.tryAcquire(1);
      }

      now.addAndGet(2 * SECOND);

      assertEquals(0, limiter.tryAcquire(1), "3 s after the only allowed attempt");
    }

    @Test
    @DisplayName("should never let a full bucket hold more than the burst")
    void bucketCapacity() {
      RateLimiter limiter = limiter(2, Duration.ofSeconds(1));

      now.addAndGet(3600 * SECOND);

      assertEquals(0, limiter.tryAcquire(1));
      assertEquals(0, limiter.tryAcquire(1));
      assertTrue(limiter.tryAcquire(1) > 0);
    }

    @Test
    @DisplayName("should keep a bucket per key")
    void perKey() {
      RateLimiter limiter = limiter(1, Duration.ofSeconds(1));

      assertEquals(0, limiter.tryAcquire(1));
      assertTrue(limiter.tryAcquire(1) > 0);
      assertEquals(0, limiter.tryAcquire(2));
    }

    @Test
    @DisplayName("should work across a wrap of the nanosecond clock")
    void clockWrap() {
      now.set(Long.MAX_VALUE - SECOND / 2);
      RateLimiter limiter = limiter(1, Duration.ofSeconds(1));
      assertEquals(0, limiter.tryAcquire(1));

      now.addAndGet(SECOND / 2 + SECOND / 4); // past Long.MAX_VALUE
      assertEquals(SECOND / 4, limiter.tryAcquire(1));

      now.addAndGet(SECOND / 4);
      assertEquals(0, limiter.tryAcquire(1));
    }
  }

  // ---------------------------------------------------------------------------
  // Memory
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("memory")
  class Memory {

    @Test
    @DisplayName("should forget a key once its bucket has been full for burst x interval")
    void forgetsIdleKeys() {
      RateLimiter limiter = limiter(2, Duration.ofSeconds(1));
      for (long key = 0; key < 100; key++) {
        limiter.tryAcquire(key);
      }
      assertEquals(100, limiter.keys());

      now.addAndGet(2 * SECOND + 1);

      assertEquals(0, limiter.keys());
    }

    @Test
    @DisplayName("should keep a key that keeps trying")
    void keepsActiveKeys() {
      RateLimiter limiter = limiter(1, Duration.ofSeconds(10));
      limiter.tryAcquire(1);

      for (int i = 0; i < 5; i++) {
        now.addAndGet(5 * SECOND);
        limiter.tryAcquire(1);
      }

      assertEquals(1, limiter.keys());
    }
  }

  // ---------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("should refuse a burst below 1 or an interval that is not positive")
  void validation() {
    assertThrows(IllegalArgumentException.class, () -> limiter(0, Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> limiter(1, Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> limiter(1, Duration.ofSeconds(-1)));
  }

  private RateLimiter limiter(int burst, Duration interval) {
    return new RateLimiter(burst, interval, now::get);
  }
}
