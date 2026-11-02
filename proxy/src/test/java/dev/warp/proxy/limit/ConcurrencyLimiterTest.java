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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConcurrencyLimiter")
class ConcurrencyLimiterTest {

  @Test
  @DisplayName("should admit up to the maximum per key, then refuse")
  void cap() {
    ConcurrencyLimiter limiter = new ConcurrencyLimiter(2);

    assertTrue(limiter.tryAcquire(1));
    assertTrue(limiter.tryAcquire(1));
    assertFalse(limiter.tryAcquire(1));
    assertTrue(limiter.tryAcquire(2), "another key has its own count");
  }

  @Test
  @DisplayName("should admit again once an operation ends")
  void release() {
    ConcurrencyLimiter limiter = new ConcurrencyLimiter(1);
    limiter.tryAcquire(1);

    limiter.release(1);

    assertTrue(limiter.tryAcquire(1));
  }

  @Test
  @DisplayName("should not count a refused attempt")
  void refusedNotCounted() {
    ConcurrencyLimiter limiter = new ConcurrencyLimiter(1);
    limiter.tryAcquire(1);
    limiter.tryAcquire(1);
    limiter.tryAcquire(1);

    limiter.release(1);

    assertTrue(limiter.tryAcquire(1));
  }

  @Test
  @DisplayName("should forget a key with nothing in progress")
  void forgetsIdleKeys() {
    ConcurrencyLimiter limiter = new ConcurrencyLimiter(3);
    limiter.tryAcquire(1);
    limiter.tryAcquire(1);
    limiter.tryAcquire(2);

    limiter.release(1);
    limiter.release(2);
    assertEquals(1, limiter.keys());
    limiter.release(1);

    assertEquals(0, limiter.keys());
  }

  @Test
  @DisplayName("should never admit more than the maximum from concurrent threads")
  void concurrent() throws InterruptedException {
    ConcurrencyLimiter limiter = new ConcurrencyLimiter(5);
    AtomicInteger admitted = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < 32; i++) {
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      start.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                    if (limiter.tryAcquire(7)) {
                      admitted.incrementAndGet();
                    }
                  }));
    }

    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }

    assertTrue(admitted.get() <= 5, "admitted " + admitted.get());
    assertTrue(admitted.get() >= 1);
  }

  @Test
  @DisplayName("should refuse a maximum below 1")
  void validation() {
    assertThrows(IllegalArgumentException.class, () -> new ConcurrencyLimiter(0));
  }
}
