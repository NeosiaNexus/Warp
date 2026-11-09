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

import dev.warp.proxy.server.BackendServer;
import dev.warp.proxy.server.ServerRegistry;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.netty.util.concurrent.EventExecutorGroup;
import org.jspecify.annotations.Nullable;

/**
 * Pings every registered backend on a fixed interval and records {@code warp_server_rtt_seconds}
 * and {@code warp_server_health}. A ping is a status request on a connection of its own, as the
 * server list would make; the backend sees one more ping per interval.
 */
public final class BackendHealthProbe {

  private final ProxyMetrics metrics;
  private final ServerRegistry servers;
  private final Duration interval;
  private @Nullable ScheduledFuture<?> task;

  /**
   * Creates the probe.
   *
   * @param metrics where the results go
   * @param servers the backends
   * @param interval the time between two rounds; zero for no pings
   */
  public BackendHealthProbe(ProxyMetrics metrics, ServerRegistry servers, Duration interval) {
    this.metrics = metrics;
    this.servers = servers;
    this.interval = interval;
  }

  /**
   * Starts the rounds, the first one after an interval.
   *
   * @param group the loops to schedule on
   */
  public void start(EventExecutorGroup group) {
    if (interval.isZero()) {
      return;
    }
    long millis = interval.toMillis();
    task = group.next().scheduleAtFixedRate(this::round, millis, millis, TimeUnit.MILLISECONDS);
  }

  /** Stops the rounds. */
  public void stop() {
    ScheduledFuture<?> running = task;
    if (running != null) {
      running.cancel(false);
      task = null;
    }
  }

  /** Pings every backend once. */
  void round() {
    for (BackendServer server : servers.allServers()) {
      long start = System.nanoTime();
      var _ =
          server
              .ping()
              .whenComplete(
                  (ping, error) ->
                      metrics.serverPinged(
                          server.name(), error == null ? System.nanoTime() - start : -1));
    }
  }
}
