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
package dev.warp.proxy.connection;

import dev.warp.api.event.player.KickedFromServerEvent;
import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.play.StartConfiguration;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Switches servers through the configuration phase (1.20.2 and newer).
 *
 * <p>A switch first logs the player in to the new server while it keeps playing on its current one.
 * Only once the new server accepted the player does the proxy send {@link StartConfiguration}: the
 * client acknowledges and enters the configuration phase, which resets everything the previous
 * server set up; the proxy then drops the previous backend and lets the new one configure the
 * client, and both sides enter PLAY. If the new server cannot be reached or refuses the player, the
 * player never left: it keeps playing where it was.
 *
 * <p>A player that is still in the configuration phase (joining, or between two servers after its
 * server went down) is connected to the next server directly.
 *
 * <p>Every method runs on the client's event loop, which also serves the player's backends.
 */
final class ReconfigurationSwitch implements ServerSwitch {

  private static final Logger logger = LoggerFactory.getLogger(ReconfigurationSwitch.class);

  /** Where a switch from a server the player plays on stands. */
  private enum Phase {
    /** No such switch. */
    IDLE,
    /** Logging in to the new server; the client still plays on its current one. */
    CONNECTING,
    /** The new server accepted the player; the client was asked to enter configuration. */
    AWAITING_ACKNOWLEDGEMENT
  }

  private final ConnectedPlayer player;

  private Phase phase = Phase.IDLE;

  /** The new server's backend while {@link Phase#CONNECTING} or awaiting the acknowledgement. */
  private @Nullable BackendConnection pending;

  /** Whether the server the player is leaving went down while the new one was logging it in. */
  private boolean currentLost;

  /** The new server's backend that went down while awaiting the acknowledgement. */
  private @Nullable MinecraftConnection lostBackend;

  ReconfigurationSwitch(ConnectedPlayer player) {
    this.player = player;
  }

  // ---------------------------------------------------------------------------
  // Switching
  // ---------------------------------------------------------------------------

  @Override
  public void start(ServerInfo target) {
    phase = Phase.CONNECTING;
    pending = null;
    currentLost = player.backendConnection() == null;
    player.scheduleSwitchTimeout();
    player.connect(target);
  }

  /**
   * Returns whether the client was asked to enter the configuration phase for a switch, and its
   * acknowledgement is the proxy's to handle.
   *
   * @return {@code true} while awaiting the acknowledgement of a proxy-initiated reconfiguration
   */
  boolean awaitingAcknowledgement() {
    return phase == Phase.AWAITING_ACKNOWLEDGEMENT;
  }

  /**
   * Continues a switch once the client acknowledged the configuration phase: the previous backend
   * is dropped and the new one configures the client.
   *
   * @param target the server switched to
   */
  void acknowledged(ServerInfo target) {
    player.clientStateChanged(ProtocolState.CONFIGURATION);
    attachPending(target);
  }

  /** Makes the pending backend the player's, the client being in the configuration phase. */
  private void attachPending(ServerInfo target) {
    BackendConnection joining = pending;
    pending = null;
    MinecraftConnection lost = lostBackend;
    lostBackend = null;
    phase = Phase.IDLE;
    // The client waits for the new server's configuration: read for its keep-alive answers, the
    // rest held for that server.
    player.clientConnection().setSessionHandler(new SwitchWaitSessionHandler(player));
    BackendConnection previous = player.backendConnection();
    player.setBackendConnection(joining);
    if (previous != null) {
      previous.disconnect();
    }
    if (joining == null) {
      // The new server went down after accepting the player: the client waits in the
      // configuration phase, so the fallback order applies.
      player.handleBackendFailure(target.name(), lost, KickedFromServerEvent.Cause.CONNECTION_LOST);
      return;
    }
    configure(joining.connection());
  }

  @Override
  public void fallBack(String failedServer, ServerInfo fallback) {
    boolean configuring =
        player.clientConnection().decoder().state() == ProtocolState.CONFIGURATION;
    if (configuring) {
      // The client waits in the configuration phase (a switch, or its first join): connect it to
      // the fallback directly.
      logger.info(
          "Could not connect {} to '{}', trying fallback '{}'",
          player.username(),
          failedServer,
          fallback.name());
      // Tracked as a switch, even on the first join, so that the current server is updated once
      // it completes. The fallback gets the watchdog's whole time: the attempts before it, and the
      // plugins that chose it, took theirs.
      player.scheduleSwitchTimeout();
      player.beginSwitch(fallback);
      connectDuringSwitch(fallback);
    } else {
      logger.info(
          "Backend failed for {}, switching to fallback '{}'", player.username(), fallback.name());
      player.sendSystemMessage("Connecting to " + fallback.name() + "...");
      // Ends the failed switch, if any, but keeps the servers that already failed excluded.
      player.setSwitching(false);
      player.switchServer(fallback);
    }
  }

  private void connectDuringSwitch(ServerInfo target) {
    phase = Phase.IDLE;
    pending = null;
    player.clientConnection().setSessionHandler(new SwitchWaitSessionHandler(player));
    BackendConnection previous = player.backendConnection();
    player.setBackendConnection(null);
    if (previous != null) {
      previous.disconnect();
    }
    player.connect(target);
  }

  // ---------------------------------------------------------------------------
  // Backend lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void connected(BackendConnection backend) {
    if (phase == Phase.CONNECTING) {
      // The client keeps playing on its current server until this one accepts it.
      pending = backend;
      return;
    }
    // The client waits in the configuration phase: the new backend is its server from now on.
    // The current server name changes when the switch completes, in PLAY.
    player.setBackendConnection(backend);
  }

  @Override
  public void connectFailed(ServerInfo target) {
    if (phase == Phase.CONNECTING) {
      abandon(target, null, KickedFromServerEvent.Cause.UNREACHABLE);
      return;
    }
    player.handleBackendFailure(target.name(), null, KickedFromServerEvent.Cause.UNREACHABLE);
  }

  @Override
  public void loggedIn(MinecraftConnection backend) {
    BackendConnection joining = pending;
    if (phase == Phase.CONNECTING && joining != null && joining.connection() == backend) {
      ServerInfo target = joining.server();
      if (player.clientConnection().decoder().state() != ProtocolState.PLAY) {
        // The current server started a reconfiguration of its own meanwhile: the client is
        // already in the configuration phase.
        attachPending(target);
        return;
      }
      // The new server accepted the player: now move the client to the configuration phase.
      // Sending a terminal packet inside an open bundle crashes the client with "Terminal message
      // received in bundle" (Velocity #1384).
      phase = Phase.AWAITING_ACKNOWLEDGEMENT;
      // From here on the current server's packets must not reach the client.
      player.playOn(null);
      player.startConfiguration();
      return;
    }
    configure(backend);
  }

  /** Lets a backend that logged the player in configure the client. */
  private void configure(MinecraftConnection backend) {
    // The server owns the configuration's pace from here on.
    player.configurationStarted();
    backend.writeAndFlush(new LoginAcknowledged());
    backend.setState(ProtocolState.CONFIGURATION);
    backend.setSessionHandler(new BackendConfigSessionHandler(player, backend));
  }

  @Override
  public void failed(MinecraftConnection backend) {
    BackendConnection joining = pending;
    if (joining != null && joining.connection() == backend) {
      if (phase == Phase.CONNECTING) {
        abandon(joining.server(), backend, KickedFromServerEvent.Cause.CONNECTION_LOST);
        return;
      }
      // Awaiting the acknowledgement: the client is on its way to the configuration phase, where
      // attachPending applies the fallback order.
      pending = null;
      lostBackend = backend;
      joining.disconnect();
      return;
    }
    BackendConnection current = player.backendConnection();
    if (current == null || current.connection() != backend) {
      return; // a backend the player already left
    }
    switch (phase) {
      case CONNECTING -> {
        // The server being left went down: the switch goes on, and if it fails, the fallback
        // order applies instead of keeping the player where it was.
        currentLost = true;
        player.playOn(null);
        player.readClientWhileWaiting();
      }
      case AWAITING_ACKNOWLEDGEMENT -> {
        // Being left, and cut off already: attachPending drops it.
      }
      case IDLE -> {
        player.setBackendConnection(null);
        player.handleBackendFailure(
            current.server().name(), backend, KickedFromServerEvent.Cause.CONNECTION_LOST);
      }
    }
  }

  /**
   * Gives up a switch whose new server could not be reached or refused the player before it left
   * its current server.
   */
  private void abandon(
      ServerInfo target, @Nullable MinecraftConnection backend, KickedFromServerEvent.Cause cause) {
    phase = Phase.IDLE;
    BackendConnection joining = pending;
    pending = null;
    if (joining != null) {
      joining.disconnect();
    }
    if (currentLost) {
      BackendConnection current = player.backendConnection();
      player.setBackendConnection(null);
      if (current != null) {
        current.disconnect();
      }
      player.handleBackendFailure(target.name(), backend, cause);
      return;
    }
    player.switchFailed(target, backend, cause);
  }

  @Override
  public void timedOut() {
    if (phase == Phase.CONNECTING) {
      ServerInfo target = player.pendingSwitchTarget();
      if (target != null) {
        abandon(target, null, KickedFromServerEvent.Cause.TIMED_OUT);
        return;
      }
    }
    phase = Phase.IDLE;
    player.setSwitching(false);
    player.disconnectWithReason("Server switch timed out.");
  }

  @Override
  public void close() {
    BackendConnection joining = pending;
    pending = null;
    if (joining != null) {
      joining.disconnect();
    }
  }
}
