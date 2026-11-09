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

import dev.warp.api.player.ClientPlatform;
import dev.warp.api.player.InboundConnection;
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginState;
import dev.warp.api.server.Route;
import dev.warp.api.server.ServerPing;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.WarpBuildInfo;
import dev.warp.proxy.config.QueryConfig;
import dev.warp.proxy.connection.ConnectedPlayer;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.event.proxy.ProxyPingEventImpl;
import dev.warp.proxy.logging.LogCode;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.status.ServerList;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The snapshot the query listener answers from, refreshed at most every {@value #REFRESH_SECONDS}
 * seconds.
 *
 * <p>The entry comes from the same {@link ServerList} as the server list ping, through {@link
 * dev.warp.api.event.proxy.ProxyPingEvent} when a plugin listens, so that a plugin which shapes the
 * MOTD or the counts needs no code for queries. The event fires with a synthetic connection whose
 * address is the query listener's own, at the latest supported protocol. A handler that answers
 * {@link dev.warp.api.event.proxy.ProxyPingEvent.Ignore} hides the proxy from queries as from
 * pings: stats are dropped until the next snapshot says otherwise.
 *
 * <p>The refresh is asynchronous: a stat that finds the snapshot stale starts one and is answered
 * from the snapshot it found. The event loop never waits for plugins.
 */
public final class QueryStatus {

  private static final Logger logger = LoggerFactory.getLogger(QueryStatus.class);

  /** How long a snapshot is answered from before another is built. */
  static final int REFRESH_SECONDS = 2;

  private static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(REFRESH_SECONDS);

  private final QueryConfig config;
  private final ServerList serverList;
  private final WarpEventBus events;
  private final PlayerRegistry players;
  private final Supplier<List<PluginContainer>> plugins;
  private final LongSupplier nanoClock;
  private final InboundConnection origin;
  private final String hostIp;
  private final int hostPort;

  /** The current snapshot, or {@code null} while a plugin hides the proxy. */
  private volatile @Nullable QuerySnapshot snapshot;

  /** When the current snapshot was built, in {@link System#nanoTime()} time. */
  private volatile long builtAt;

  private final AtomicBoolean refreshing = new AtomicBoolean();
  private final AtomicBoolean reportedTruncation = new AtomicBoolean();

  /**
   * Creates the status and builds its first snapshot from the proxy's proposal, without plugins.
   *
   * @param config the {@code query} section
   * @param serverList the server list entry the proxy proposes
   * @param events the event bus the ping event fires on
   * @param players the connected players, for the names
   * @param plugins the plugins, for the {@code plugins} field
   * @param listener the address the query listener is bound to
   * @param playersAddress the host and port players connect to, for {@code hostip} and {@code
   *     hostport}: {@code query.advertise-host} when set, else the drain's public address, else the
   *     proxy's {@code bind}
   * @param nanoClock a monotonic clock in nanoseconds, such as {@link System#nanoTime()}
   */
  public QueryStatus(
      QueryConfig config,
      ServerList serverList,
      WarpEventBus events,
      PlayerRegistry players,
      Supplier<List<PluginContainer>> plugins,
      InetSocketAddress listener,
      InetSocketAddress playersAddress,
      LongSupplier nanoClock) {
    this.config = config;
    this.serverList = serverList;
    this.events = events;
    this.players = players;
    this.plugins = plugins;
    this.nanoClock = nanoClock;
    this.origin = new QueryOrigin(listener);
    this.hostIp = hostOf(playersAddress);
    this.hostPort = playersAddress.getPort();
    this.snapshot = build(serverList.proposal(ProtocolVersion.latest().protocol()));
    this.builtAt = nanoClock.getAsLong();
  }

  /**
   * Returns the snapshot to answer a stat from, starting a refresh when it is stale.
   *
   * @param now the current {@link System#nanoTime()}
   * @return the snapshot, or {@code null} if a plugin chose to hide the proxy
   */
  @Nullable QuerySnapshot current(long now) {
    if (now - builtAt >= REFRESH_NANOS && refreshing.compareAndSet(false, true)) {
      refresh();
    }
    return snapshot;
  }

  /** Returns the current snapshot without refreshing, for tests. */
  @Nullable QuerySnapshot peek() {
    return snapshot;
  }

  private void refresh() {
    try {
      ServerPing proposal = serverList.proposal(ProtocolVersion.latest().protocol());
      if (!events.hasHandlers(ProxyPingEventImpl.class)) {
        publish(proposal);
        return;
      }
      var _ =
          events
              .fire(new ProxyPingEventImpl(origin, proposal))
              .whenComplete(
                  (event, failure) -> {
                    if (failure != null) {
                      logger.debug(
                          "The ping event for a query failed: kept the last snapshot", failure);
                      refreshing.set(false);
                      return;
                    }
                    publish(event.answer());
                  });
    } catch (RuntimeException e) {
      logger.debug("Building the query snapshot failed: kept the last snapshot", e);
      refreshing.set(false);
    }
  }

  private void publish(@Nullable ServerPing ping) {
    try {
      QuerySnapshot fresh = ping == null ? null : build(ping);
      this.snapshot = fresh;
      this.builtAt = nanoClock.getAsLong();
      if (fresh != null && fresh.truncated()) {
        reportTruncation(fresh);
      }
    } finally {
      refreshing.set(false);
    }
  }

  private QuerySnapshot build(ServerPing ping) {
    ServerPing.Players counts = ping.players();
    int online = counts == null ? 0 : counts.online();
    int max = counts == null ? 0 : counts.max();
    return QuerySnapshot.of(
        PlainTextComponentSerializer.plainText().serialize(ping.description()),
        ping.version().name(),
        pluginsField(),
        config.map(),
        online,
        max,
        hostPort,
        hostIp,
        counts == null ? List.of() : names());
  }

  /** The names listed: up to {@code query.player-names} players who allow listings, sorted. */
  private List<String> names() {
    int limit = config.playerNames();
    if (limit == 0) {
      return List.of();
    }
    List<String> names = new ArrayList<>(Math.min(limit, 64));
    for (ConnectedPlayer player : players.all()) {
      if (names.size() >= limit) {
        break;
      }
      if (player.allowsServerListing()) {
        names.add(player.username());
      }
    }
    // A stable order, for operators who diff two replies.
    names.sort(String.CASE_INSENSITIVE_ORDER);
    return names;
  }

  /**
   * The {@code plugins} field: {@code Warp <version>: <id> <version>; ...}, as Bukkit writes it.
   */
  private String pluginsField() {
    StringBuilder field = new StringBuilder("Warp ").append(WarpBuildInfo.VERSION);
    if (!config.showPlugins()) {
      return field.toString();
    }
    boolean first = true;
    for (PluginContainer plugin : plugins.get()) {
      if (plugin.state() != PluginState.ENABLED) {
        continue;
      }
      field.append(first ? ": " : "; ");
      first = false;
      field.append(plugin.description().name()).append(' ').append(plugin.description().version());
    }
    return field.toString();
  }

  private void reportTruncation(QuerySnapshot fresh) {
    String message =
        "A full query stat lists {} of {} player names: the reply is capped at "
            + QuerySnapshot.MAX_REPLY
            + " bytes";
    if (reportedTruncation.compareAndSet(false, true)) {
      logger.warn(
          LogCode.QUERY_REPLY_TRUNCATED.marker(),
          message + " (reported once; later ones are logged at DEBUG)",
          fresh.listed(),
          fresh.players().size());
    } else {
      logger.debug(message, fresh.listed(), fresh.players().size());
    }
  }

  /**
   * Returns the host written as {@code hostip}: the configured one, else the address's host name as
   * written, or its literal address when it was given as one.
   *
   * @param address the address players connect to
   * @return the host
   */
  private String hostOf(InetSocketAddress address) {
    if (!config.advertiseHost().isEmpty()) {
      return config.advertiseHost();
    }
    InetAddress resolved = address.getAddress();
    return resolved == null ? address.getHostString() : resolved.getHostAddress();
  }

  /**
   * The connection a query stands for in the ping event: the listener's own address, the latest
   * protocol, no host name typed.
   */
  private record QueryOrigin(InetSocketAddress remoteAddress) implements InboundConnection {

    @Override
    public Optional<String> virtualHost() {
      return Optional.empty();
    }

    @Override
    public Optional<Route> route() {
      return Optional.empty();
    }

    @Override
    public int protocolVersion() {
      return ProtocolVersion.latest().protocol();
    }

    @Override
    public ClientPlatform platform() {
      return ClientPlatform.JAVA;
    }

    @Override
    public boolean isActive() {
      return true;
    }

    @Override
    public boolean isTransferred() {
      return false;
    }

    @Override
    public String toString() {
      return "Query[" + remoteAddress + "]";
    }
  }
}
