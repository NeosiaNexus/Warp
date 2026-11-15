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

import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.metrics.Collector;
import dev.warp.api.metrics.MetricType;
import dev.warp.api.metrics.SampleVisitor;
import dev.warp.proxy.WarpBuildInfo;
import dev.warp.proxy.server.BackendServer;
import dev.warp.proxy.server.ServerRegistry;

import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import org.jspecify.annotations.Nullable;

/**
 * The proxy's state read at scrape time: players, backends' loads, the drain, the event loops'
 * queues, Netty's direct memory, and {@code warp_info}.
 */
public final class ProxyCollector implements Collector {

  private static final List<String> NONE = List.of();
  private static final List<String> SERVER = List.of("server");
  private static final List<String> LOOP = List.of("loop");

  private final IntSupplier players;
  private final IntSupplier networkPlayers;
  private final ServerRegistry servers;
  private final Supplier<DrainStatus> drain;
  private final Supplier<@Nullable EventExecutorGroup> loops;
  private final double startTimeSeconds = System.currentTimeMillis() / 1000.0;

  /**
   * Creates the collector.
   *
   * @param players how many players are in PLAY on this proxy
   * @param networkPlayers how many players are on the whole fleet
   * @param servers the backends
   * @param drain where the drain stands
   * @param loops the worker loops, {@code null} before they start
   */
  public ProxyCollector(
      IntSupplier players,
      IntSupplier networkPlayers,
      ServerRegistry servers,
      Supplier<DrainStatus> drain,
      Supplier<@Nullable EventExecutorGroup> loops) {
    this.players = players;
    this.networkPlayers = networkPlayers;
    this.servers = servers;
    this.drain = drain;
    this.loops = loops;
  }

  @Override
  public void collect(SampleVisitor out) {
    out.family("warp_info", MetricType.GAUGE, "The proxy: always 1, the details in the labels.");
    out.sample(
        "warp_info",
        List.of("version", "commit", "java"),
        List.of(
            WarpBuildInfo.VERSION,
            WarpBuildInfo.GIT_COMMIT,
            System.getProperty("java.version", "unknown")),
        1);
    out.family("warp_start_time_seconds", MetricType.GAUGE, "When the proxy started, Unix time.");
    out.sample("warp_start_time_seconds", NONE, NONE, startTimeSeconds);

    int online = players.getAsInt();
    out.family("warp_players_online", MetricType.GAUGE, "Players in PLAY on this proxy.");
    out.sample("warp_players_online", NONE, NONE, online);
    out.family(
        "warp_players_online_network",
        MetricType.GAUGE,
        "Players on the whole network; this proxy's alone until a fleet store is configured.");
    out.sample("warp_players_online_network", NONE, NONE, networkPlayers.getAsInt());

    out.family(
        "warp_server_connections_active", MetricType.GAUGE, "Players attached to each backend.");
    for (BackendServer server : servers.allServers()) {
      out.sample(
          "warp_server_connections_active", SERVER, List.of(server.name()), server.playerCount());
    }

    DrainStatus status = drain.get();
    out.family(
        "warp_drain_phase",
        MetricType.GAUGE,
        "The drain's phase: 0 idle, then announcing, transferring, waiting, stopping.");
    out.sample("warp_drain_phase", NONE, NONE, status.phase().ordinal());
    out.family(
        "warp_drain_players_remaining", MetricType.GAUGE, "Players the drain still waits for.");
    out.sample("warp_drain_players_remaining", NONE, NONE, status.players());
    out.family(
        "warp_drain_duration_seconds",
        MetricType.GAUGE,
        "How long the current or last drain has run.");
    out.sample("warp_drain_duration_seconds", NONE, NONE, status.elapsed().toNanos() / 1e9);

    EventExecutorGroup group = loops.get();
    if (group != null) {
      out.family(
          "warp_event_loop_pending_tasks",
          MetricType.GAUGE,
          "Tasks waiting on each worker event loop.");
      int index = 0;
      for (EventExecutor executor : group) {
        if (executor instanceof SingleThreadEventExecutor single) {
          out.sample(
              "warp_event_loop_pending_tasks",
              LOOP,
              List.of(Integer.toString(index)),
              single.pendingTasks());
        }
        index++;
      }
    }
    out.family(
        "warp_direct_memory_bytes",
        MetricType.GAUGE,
        "Direct memory Netty's pooled allocator holds.");
    out.sample(
        "warp_direct_memory_bytes",
        NONE,
        NONE,
        PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory());
  }
}
