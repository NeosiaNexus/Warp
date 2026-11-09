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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EventLoopLagProbe")
class EventLoopLagProbeTest {

  private static final long PERIOD = EventLoopLagProbe.PERIOD_NANOS;
  private static final long LAG = TimeUnit.MILLISECONDS.toNanos(300);

  private final MetricRegistry registry = new MetricRegistry(0);
  private final ProxyMetrics metrics = new ProxyMetrics(registry);
  private long now;

  @Test
  @DisplayName("should report the full lag of a loop that is steadily late, tick after tick")
  void steadyLag() {
    EventLoopLagProbe.Tick tick = new EventLoopLagProbe.Tick(metrics, () -> now, PERIOD);

    for (int run = 1; run <= 3; run++) {
      now = run * PERIOD + LAG;
      tick.run();
    }

    RecordingVisitor out = RecordingVisitor.of(registry);
    assertEquals(3, out.value("warp_event_loop_lag_seconds_count"));
    assertEquals(0.9, out.value("warp_event_loop_lag_seconds_sum"), 1e-9);
    assertEquals(0, out.value("warp_event_loop_lag_seconds_bucket{le=0.25}"));
    assertEquals(3, out.value("warp_event_loop_lag_seconds_bucket{le=0.5}"));
  }

  @Test
  @DisplayName("should record zero lag for a loop that runs on time, and a stall once")
  void stallThenRecovery() {
    EventLoopLagProbe.Tick tick = new EventLoopLagProbe.Tick(metrics, () -> now, PERIOD);

    now = PERIOD;
    tick.run();
    now = 2 * PERIOD + 5 * PERIOD; // A 5 s stall.
    tick.run();
    now = 3 * PERIOD + 5 * PERIOD; // Still 5 s behind the fixed-rate deadline.
    tick.run();

    RecordingVisitor out = RecordingVisitor.of(registry);
    assertEquals(3, out.value("warp_event_loop_lag_seconds_count"));
    assertEquals(10, out.value("warp_event_loop_lag_seconds_sum"), 1e-9);
    assertEquals(1, out.value("warp_event_loop_lag_seconds_bucket{le=0.001}"));
  }
}
