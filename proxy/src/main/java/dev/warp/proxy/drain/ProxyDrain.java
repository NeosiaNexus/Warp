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
package dev.warp.proxy.drain;

import dev.warp.api.event.EventBus;
import dev.warp.api.event.Subscribe;
import dev.warp.api.event.player.PlayerDrainEvent;
import dev.warp.api.event.player.PreLoginEvent;
import dev.warp.api.event.player.ServerPostConnectEvent;
import dev.warp.api.event.proxy.ProxyDrainEvent;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.player.InboundConnection;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.connection.ConnectedPlayer;
import dev.warp.proxy.connection.WarpPlayer;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.event.player.PlayerDrainEventImpl;
import dev.warp.proxy.player.PlayerRegistry;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The drain, wired into the proxy: its players come from the player registry, plugins decide
 * through {@link PlayerDrainEvent}, each phase fires a {@link ProxyDrainEvent}, and phase 3 hands
 * over to the proxy's shutdown.
 *
 * <p>Once the drain starts, two handlers of the proxy's own join the event bus: one refuses logins
 * as {@code drain.new-players} says (it runs last, so that no plugin lets a refused player in), the
 * other deals with players arriving on a server. Neither is registered before: a proxy that never
 * drains pays nothing for them.
 */
public final class ProxyDrain implements DrainHost<PlayerCandidate> {

  private static final Logger logger = LoggerFactory.getLogger(ProxyDrain.class);

  private final DrainConfig config;
  private final PlayerRegistry players;
  private final WarpEventBus events;
  private final EventBus own;
  private final Consumer<DrainStatus> shutdown;
  private final DrainController<PlayerCandidate> controller;
  private final AtomicBoolean subscribed = new AtomicBoolean();

  /**
   * Creates the drain, idle until {@link #start}.
   *
   * @param config the {@code drain} section
   * @param players the connected players
   * @param events the proxy's event bus
   * @param scheduler runs the drain's steps
   * @param shutdown phase 3: disconnects the players left and stops the proxy
   */
  public ProxyDrain(
      DrainConfig config,
      PlayerRegistry players,
      WarpEventBus events,
      DrainScheduler scheduler,
      Consumer<DrainStatus> shutdown) {
    this.config = config;
    this.players = players;
    this.events = events;
    this.own = new PluginEventBus(events, "warp", null, subscription -> {});
    this.shutdown = shutdown;
    this.controller = new DrainController<>(config, scheduler, this);
  }

  /**
   * Starts the drain, if it has not started yet.
   *
   * @param trigger what starts it
   * @return completed with the final status before the proxy shuts down; the same for every call
   */
  public CompletableFuture<DrainStatus> start(DrainTrigger trigger) {
    return start(trigger, null);
  }

  /**
   * Starts the drain with a message of its own, if it has not started yet.
   *
   * @param trigger what starts it
   * @param reason shown in place of {@code warp.disconnect.restarting}, or {@code null}; ignored if
   *     the drain had started
   * @return completed with the final status before the proxy shuts down; the same for every call
   */
  public CompletableFuture<DrainStatus> start(DrainTrigger trigger, @Nullable Component reason) {
    if (subscribed.compareAndSet(false, true)) {
      var _ = own.subscribe(PreLoginEvent.class, Integer.MIN_VALUE, this::onPreLogin);
      var _ =
          own.subscribe(ServerPostConnectEvent.class, Subscribe.LATE, this::onServerPostConnect);
    }
    return controller.start(trigger, reason);
  }

  /**
   * Returns what the players the drain disconnects are told.
   *
   * @return the reason the drain started with, or {@code warp.disconnect.restarting}
   */
  public Component message() {
    return controller.message();
  }

  /**
   * Returns whether the drain started.
   *
   * @return {@code true} from the first {@link #start} on
   */
  public boolean isDraining() {
    return controller.isDraining();
  }

  /**
   * Returns where the drain stands. Never blocks.
   *
   * @return a snapshot
   */
  public DrainStatus status() {
    return controller.status();
  }

  // ---------------------------------------------------------------------------
  // DrainHost
  // ---------------------------------------------------------------------------

  @Override
  public List<PlayerCandidate> players() {
    return players.all().stream().map(PlayerCandidate::new).toList();
  }

  @Override
  public int playerCount() {
    return players.count();
  }

  @Override
  public CompletableFuture<PlayerDrainEvent.Decision> decide(
      PlayerCandidate candidate,
      PlayerDrainEvent.Reason reason,
      PlayerDrainEvent.Decision fallback) {
    if (!events.hasHandlers(PlayerDrainEventImpl.class)) {
      return CompletableFuture.completedFuture(fallback);
    }
    ConnectedPlayer player = candidate.player();
    // On the player's lane: after the events of its arrival on a server.
    return events
        .fire(new PlayerDrainEventImpl(player.api(), reason, fallback), player.uuid())
        .thenApply(PlayerDrainEvent::result);
  }

  @Override
  public void phaseChanged(DrainStatus status) {
    var _ = events.fire(new ProxyDrainEvent(status));
  }

  @Override
  public void shutdown(DrainStatus status) {
    shutdown.accept(status);
  }

  // ---------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------

  private void onPreLogin(PreLoginEvent event) {
    if (event.result() instanceof PreLoginEvent.Denied) {
      return;
    }
    String refusal = refusal(event.connection());
    if (refusal == null) {
      return;
    }
    event.setResult(new PreLoginEvent.Denied(controller.message()));
    controller.loginRefused();
    logger.info(
        "Refused the login of {} from {} while draining: {}",
        event.username(),
        event.connection().remoteAddress(),
        refusal);
  }

  /**
   * Returns why a client logging in while the proxy drains is refused.
   *
   * @param connection the client
   * @return the reason, for the log, or {@code null} to let it in
   */
  @Nullable String refusal(InboundConnection connection) {
    if (config.newPlayers() == DrainConfig.NewPlayers.REFUSE) {
      return "drain.new-players is refuse";
    }
    if (config.transferTo() == null) {
      return "there is no drain.transfer-to to send it to";
    }
    ProtocolVersion version = ProtocolVersion.byProtocolId(connection.protocolVersion());
    if (version == null || version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_5)) {
      return "its client cannot be transferred (older than 1.20.5)";
    }
    if (connection.isTransferred()) {
      // Another proxy draining at the same time would send it straight back.
      return "a transfer just sent it here, and sending it away again could loop";
    }
    return null;
  }

  private void onServerPostConnect(ServerPostConnectEvent event) {
    if (event.player() instanceof WarpPlayer player) {
      controller.playerArrived(new PlayerCandidate(player.connectedPlayer()));
    }
  }
}
