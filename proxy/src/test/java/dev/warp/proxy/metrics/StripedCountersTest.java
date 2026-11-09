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
package dev.warp.proxy.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.warp.api.metrics.Buckets;
import dev.warp.api.metrics.Counter;
import dev.warp.api.metrics.Histogram;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.util.concurrent.FastThreadLocalThread;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Striped counters")
class StripedCountersTest {

  private static final int LOOPS = 4;
  private static final int OTHERS = 3;
  private static final int PER_THREAD = 150_000; // a multiple of 3: one third per bucket

  @Test
  @DisplayName("should lose no increment from loops with a slot nor from threads without one")
  void concurrentIncrements() throws Exception {
    MetricRegistry registry = new MetricRegistry(LOOPS);
    Counter.Child counter = registry.counter("warp_t_total", "T.").labels();
    Histogram.Child histogram =
        registry.histogram("warp_t_seconds", "T.", Buckets.of(0.5, 1.5)).labels();
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    AtomicLong scrapes = new AtomicLong();
    for (int i = 0; i < LOOPS + OTHERS; i++) {
      int index = i;
      Runnable work =
          () -> {
            if (index < LOOPS) {
              LoopIndex.assign(index);
            }
            await(start);
            for (int n = 0; n < PER_THREAD; n++) {
              counter.inc();
              counter.add(2);
              histogram.observe(n % 3); // 0, 1 and 2: one per bucket
            }
          };
      threads.add(index < LOOPS ? new FastThreadLocalThread(work) : new Thread(work));
    }
    // A scraper reads while the writers run: sums must never go backwards.
    Thread scraper =
        new Thread(
            () -> {
              await(start);
              long last = 0;
              while (!Thread.currentThread().isInterrupted()) {
                long now = counter.value();
                if (now < last) {
                  throw new AssertionError("sum went backwards: " + last + " then " + now);
                }
                last = now;
                scrapes.incrementAndGet();
              }
            });
    threads.forEach(Thread::start);
    scraper.start();
    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    scraper.interrupt();
    scraper.join();

    long expected = (long) (LOOPS + OTHERS) * PER_THREAD;
    assertEquals(expected * 3, counter.value());
    assertEquals(expected, histogram.count());
    RecordingVisitor out = RecordingVisitor.of(registry);
    assertEquals(expected / 3 + expected / 3, out.value("warp_t_seconds_bucket{le=1.5}"));
    assertEquals(expected / 3, out.value("warp_t_seconds_bucket{le=0.5}"));
    assertEquals(expected, out.value("warp_t_seconds_bucket{le=+Inf}"));
    assertEquals(expected, histogram.sum(), 0.0);
  }

  @Test
  @DisplayName("should fall back to the shared adder when the slot is beyond the stripes")
  void slotBeyondStripes() throws Exception {
    MetricRegistry registry = new MetricRegistry(1);
    Counter.Child counter = registry.counter("warp_t_total", "T.").labels();
    Thread thread =
        new FastThreadLocalThread(
            () -> {
              LoopIndex.assign(7);
              counter.add(5);
            });
    thread.start();
    thread.join();

    assertEquals(5, counter.value());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
