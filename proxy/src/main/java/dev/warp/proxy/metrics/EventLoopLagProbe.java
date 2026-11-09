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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Measures each event loop's lag: a task scheduled every second on every loop records how late it
 * ran, into {@code warp_event_loop_lag_seconds}. A loop busy with a slow handler or a flood shows
 * here before anything else does.
 */
public final class EventLoopLagProbe {

  static final long PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final ProxyMetrics metrics;
  private final List<ScheduledFuture<?>> probes = new ArrayList<>();

  /**
   * Creates the probe.
   *
   * @param metrics where the lag goes
   */
  public EventLoopLagProbe(ProxyMetrics metrics) {
    this.metrics = metrics;
  }

  /**
   * Starts a probe on every executor of the group.
   *
   * @param group the worker loops
   */
  public void start(EventExecutorGroup group) {
    for (EventExecutor executor : group) {
      probes.add(
          executor.scheduleAtFixedRate(
              new Tick(metrics, System::nanoTime, System.nanoTime() + PERIOD_NANOS),
              PERIOD_NANOS,
              PERIOD_NANOS,
              TimeUnit.NANOSECONDS));
    }
  }

  /**
   * One loop's probe. The expected time advances by the period from the previous deadline, as a
   * fixed-rate schedule does, never from the time the tick ran: rebasing on the run time would
   * measure the change in lag between two ticks, and a loop steadily late would show no lag.
   */
  static final class Tick implements Runnable {
    private final ProxyMetrics metrics;
    private final LongSupplier clock;
    private long expected;

    Tick(ProxyMetrics metrics, LongSupplier clock, long firstDeadline) {
      this.metrics = metrics;
      this.clock = clock;
      this.expected = firstDeadline;
    }

    @Override
    public void run() {
      metrics.eventLoopLag(clock.getAsLong() - expected);
      expected += PERIOD_NANOS;
    }
  }

  /** Stops the probes. */
  public void stop() {
    for (ScheduledFuture<?> probe : probes) {
      probe.cancel(false);
    }
    probes.clear();
  }
}
