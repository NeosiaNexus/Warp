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
import dev.warp.api.metrics.Gauge;
import dev.warp.api.metrics.Histogram;
import dev.warp.protocol.netty.TrafficObserver;

import java.lang.management.ManagementFactory;

import com.sun.management.ThreadMXBean;
import io.netty.util.concurrent.FastThreadLocalThread;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hot path's updates allocate nothing: the proof the registry's design rests on, measured with
 * the thread's allocation counter, as the protocol module's allocation tests do.
 */
@DisplayName("Metrics allocation")
class MetricsAllocationTest {

  private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
  private static final int ROUNDS = 100_000;

  @Test
  @DisplayName("should allocate nothing per increment, observation or forwarded frame on a loop")
  void onEventLoop() throws Exception {
    MetricRegistry registry = new MetricRegistry(2);
    ProxyMetrics metrics = new ProxyMetrics(registry);
    Counter.Child counter = registry.counter("warp_t_total", "T.", "k").labels("a");
    Histogram.Child histogram =
        registry.histogram("warp_t_seconds", "T.", Buckets.NETWORK).labels();
    Gauge.Child gauge = registry.gauge("warp_t", "T.").labels();
    TrafficObserver client = metrics.clientTraffic();
    TrafficObserver backend = metrics.backendTraffic("lobby");
    long[] allocated = new long[4];
    Thread loop =
        new FastThreadLocalThread(
            () -> {
              LoopIndex.assign(1);
              allocated[0] = allocatedBy(() -> counter.inc(), () -> counter.add(3));
              allocated[1] =
                  allocatedBy(() -> histogram.observe(0.2), () -> histogram.observeNanos(1234));
              allocated[2] = allocatedBy(gauge::inc, () -> gauge.set(4));
              allocated[3] =
                  allocatedBy(
                      () -> {
                        client.blind(100);
                        client.decoded(20);
                        client.passthrough(100, 300);
                        backend.blind(100);
                        backend.reencoded(100, 300);
                        metrics.eventLoopLag(5_000);
                      });
            });
    loop.start();
    loop.join();

    assertEquals(0, allocated[0], "counter");
    assertEquals(0, allocated[1], "histogram");
    assertEquals(0, allocated[2], "gauge");
    assertEquals(0, allocated[3], "traffic observers");
    assertEquals(ROUNDS * 8L, counter.value()); // two runs of inc() and add(3)
  }

  @Test
  @DisplayName("should allocate nothing per increment off the loops either, once warm")
  void offEventLoop() {
    MetricRegistry registry = new MetricRegistry(2);
    Counter.Child counter = registry.counter("warp_t_total", "T.").labels();
    Histogram.Child histogram = registry.histogram("warp_t_seconds", "T.", Buckets.FAST).labels();

    long allocated = allocatedBy(counter::inc, () -> histogram.observe(0.01));

    assertEquals(0, allocated);
  }

  /**
   * Runs the actions {@value #ROUNDS} times, twice, and returns the bytes the second run allocated
   * on this thread: the first run absorbs class loading and the adders' cells.
   */
  private static long allocatedBy(Runnable... actions) {
    run(actions);
    long before = THREADS.getCurrentThreadAllocatedBytes();
    run(actions);
    return THREADS.getCurrentThreadAllocatedBytes() - before;
  }

  private static void run(Runnable[] actions) {
    for (int i = 0; i < ROUNDS; i++) {
      for (Runnable action : actions) {
        action.run();
      }
    }
  }
}
