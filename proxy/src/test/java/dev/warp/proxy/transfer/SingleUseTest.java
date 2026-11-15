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
package dev.warp.proxy.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.warp.proxy.transfer.SingleUse.Outcome;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SingleUse")
class SingleUseTest {

  private static final long NOW = 1_800_000_000_000L;

  @Nested
  @DisplayName("spending tickets")
  class Spending {

    @Test
    @DisplayName("should spend a ticket once, and call its second use a replay")
    void once() {
      SingleUse used = new SingleUse(10);

      assertEquals(Outcome.FIRST, used.use(1, 2, NOW + 20_000, NOW));
      assertEquals(Outcome.REPLAYED, used.use(1, 2, NOW + 20_000, NOW));
      assertEquals(Outcome.REPLAYED, used.use(1, 2, NOW + 20_000, NOW + 19_999));
      assertEquals(1, used.size());
    }

    @Test
    @DisplayName("should tell tickets apart by both halves of their nonce")
    void bothHalves() {
      SingleUse used = new SingleUse(10);

      assertEquals(Outcome.FIRST, used.use(1, 2, NOW + 20_000, NOW));
      assertEquals(Outcome.FIRST, used.use(1, 3, NOW + 20_000, NOW));
      assertEquals(Outcome.FIRST, used.use(2, 2, NOW + 20_000, NOW));
      assertEquals(Outcome.FIRST, used.use(2, 1, NOW + 20_000, NOW));
      assertEquals(4, used.size());
    }

    @Test
    @DisplayName("should spend a ticket once however many threads present it at once")
    void concurrent() throws Exception {
      SingleUse used = new SingleUse(1_000);
      int threads = 8;
      AtomicInteger firsts = new AtomicInteger();
      Set<Outcome> outcomes = ConcurrentHashMap.newKeySet();
      CountDownLatch start = new CountDownLatch(1);
      Thread[] workers = new Thread[threads];
      for (int i = 0; i < threads; i++) {
        workers[i] =
            Thread.ofPlatform()
                .start(
                    () -> {
                      try {
                        start.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      for (long nonce = 0; nonce < 100; nonce++) {
                        Outcome outcome = used.use(nonce, ~nonce, NOW + 20_000, NOW);
                        outcomes.add(outcome);
                        if (outcome == Outcome.FIRST) {
                          firsts.incrementAndGet();
                        }
                      }
                    });
      }
      start.countDown();
      for (Thread worker : workers) {
        worker.join();
      }

      assertEquals(100, firsts.get());
      assertEquals(Set.of(Outcome.FIRST, Outcome.REPLAYED), outcomes);
      assertEquals(100, used.size());
    }
  }

  @Nested
  @DisplayName("staying bounded")
  class Bounded {

    @Test
    @DisplayName("should refuse a new ticket once full, rather than forget a spent one")
    void full() {
      SingleUse used = new SingleUse(2);
      used.use(1, 1, NOW + 20_000, NOW);
      used.use(2, 2, NOW + 20_000, NOW);

      assertEquals(Outcome.FULL, used.use(3, 3, NOW + 20_000, NOW + 500));
      assertEquals(Outcome.FULL, used.use(1, 1, NOW + 20_000, NOW + 500));
      assertEquals(2, used.size());
    }

    @Test
    @DisplayName("should have room for exactly its capacity")
    void capacity() {
      SingleUse used = new SingleUse(3);

      assertEquals(Outcome.FIRST, used.use(1, 1, NOW + 20_000, NOW));
      assertEquals(Outcome.FIRST, used.use(2, 2, NOW + 20_000, NOW));
      assertEquals(Outcome.FIRST, used.use(3, 3, NOW + 20_000, NOW));
      assertEquals(Outcome.FULL, used.use(4, 4, NOW + 20_000, NOW));
    }

    @Test
    @DisplayName("should forget tickets once they expired, and make room again")
    void sweeps() {
      SingleUse used = new SingleUse(2);
      used.use(1, 1, NOW + 1_000, NOW);
      used.use(2, 2, NOW + 5_000, NOW);

      assertEquals(Outcome.FIRST, used.use(3, 3, NOW + 9_000, NOW + 1_001));
      assertEquals(2, used.size());
      // The one still valid is still remembered: full again, it is refused all the same.
      assertEquals(Outcome.FULL, used.use(2, 2, NOW + 5_000, NOW + 2_001));
      assertEquals(Outcome.FIRST, used.use(4, 4, NOW + 9_000, NOW + 5_002));
      assertEquals(Outcome.FULL, used.use(2, 2, NOW + 5_000, NOW + 5_003));
    }

    @Test
    @DisplayName("should keep a ticket until the very millisecond it expires")
    void keptUntilExpiry() {
      SingleUse used = new SingleUse(2);
      used.use(1, 1, NOW + 1_000, NOW);

      assertEquals(Outcome.REPLAYED, used.use(1, 1, NOW + 1_000, NOW + 1_000));
      assertEquals(1, used.size());
      assertEquals(Outcome.FIRST, used.use(2, 2, NOW + 9_000, NOW + 2_001));
      assertEquals(1, used.size());
    }

    @Test
    @DisplayName("should sweep at most once a second, even when full")
    void sweepInterval() {
      SingleUse used = new SingleUse(1);
      used.use(1, 1, NOW + 100, NOW);

      // Expired at NOW + 101, but the last sweep was at NOW: the next one is a second later.
      assertEquals(Outcome.FULL, used.use(2, 2, NOW + 9_000, NOW + 999));
      assertEquals(1, used.size());
      assertEquals(
          Outcome.FIRST, used.use(2, 2, NOW + 9_000, NOW + SingleUse.SWEEP_INTERVAL_MILLIS));
      assertEquals(1, used.size());
    }
  }
}
