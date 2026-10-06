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

import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.play.StartConfiguration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Switches servers through the configuration phase (1.20.2 and newer).
 *
 * <p>A switch sends {@link StartConfiguration}: the client acknowledges, enters the configuration
 * phase, which resets everything the previous server set up, and waits there while the proxy drops
 * the previous backend and logs in to the new one. The new backend configures the client through
 * the configuration relay, then both sides enter PLAY. A player that is still in the configuration
 * phase (joining, or between two servers) is connected to the next server directly.
 */
final class ReconfigurationSwitch implements ServerSwitch {

  private static final Logger logger = LoggerFactory.getLogger(ReconfigurationSwitch.class);

  private final ConnectedPlayer player;

  ReconfigurationSwitch(ConnectedPlayer player) {
    this.player = player;
  }

  // ---------------------------------------------------------------------------
  // Switching
  // ---------------------------------------------------------------------------

  @Override
  public void start(ServerInfo target) {
    // Sending a terminal packet inside an open bundle crashes the client with "Terminal message
    // received in bundle" (Velocity #1384).
    player.closeBundle();
    // From here on the client leaves PLAY: the current server's packets must not reach it.
    player.playOn(null);
    player.clientConnection().writeAndFlush(new StartConfiguration());
  }

  /**
   * Continues a switch once the client acknowledged the {@link StartConfiguration}: moves the
   * client to the configuration state, pauses it, drops the previous backend and connects to the
   * target. The rest flows through the handler chain: backend login, configuration relay, PLAY.
   *
   * @param target the server the player switches to
   */
  void acknowledged(ServerInfo target) {
    player.clientConnection().setState(ProtocolState.CONFIGURATION);
    player.scheduleSwitchTimeout();
    connectDuringSwitch(target);
  }

  @Override
  public void fallBack(String failedServer, ServerInfo fallback) {
    boolean configuring =
        player.clientConnection().decoder().state() == ProtocolState.CONFIGURATION;
    if (player.isSwitching() || configuring) {
      // The client waits in the configuration phase (a switch, or its first join): connect it to
      // the fallback directly.
      logger.info(
          "Could not connect {} to '{}', trying fallback '{}'",
          player.username(),
          failedServer,
          fallback.name());
      if (!player.isSwitching()) {
        // First join: track the fallback as a switch, so the current server is updated and the
        // watchdog applies once it completes.
        player.scheduleSwitchTimeout();
      }
      player.beginSwitch(fallback);
      connectDuringSwitch(fallback);
    } else {
      logger.info(
          "Backend failed for {}, switching to fallback '{}'", player.username(), fallback.name());
      player.sendSystemMessage("Connecting to " + fallback.name() + "...");
      player.switchServer(fallback);
    }
  }

  /**
   * Connects the waiting client to {@code target}: resets it to a clean wait state (auto-read may
   * have been re-enabled by a configuration that failed), drops any lingering backend and connects.
   */
  private void connectDuringSwitch(ServerInfo target) {
    MinecraftConnection client = player.clientConnection();
    client.setAutoRead(false);
    client.setSessionHandler(new SwitchWaitSessionHandler(player));
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
    // The client waits in the configuration phase: the new backend is its server from now on.
    // The current server name changes when the switch completes, in PLAY.
    player.setBackendConnection(backend);
  }

  @Override
  public void connectFailed(ServerInfo target) {
    player.handleBackendFailure(target.name());
  }

  @Override
  public void loggedIn(MinecraftConnection backend) {
    backend.writeAndFlush(new LoginAcknowledged());
    backend.setState(ProtocolState.CONFIGURATION);
    backend.setSessionHandler(new BackendConfigSessionHandler(player, backend));
  }

  @Override
  public void failed(MinecraftConnection backend) {
    BackendConnection current = player.backendConnection();
    if (current == null || current.connection() != backend) {
      return; // a backend the player already left
    }
    if (player.isSwitching() && player.clientConnection().decoder().state() == ProtocolState.PLAY) {
      return; // being left: the client has not acknowledged the Start Configuration yet
    }
    player.setBackendConnection(null);
    player.handleBackendFailure(current.server().name());
  }

  @Override
  public void timedOut() {
    player.setSwitching(false);
    player.disconnectWithReason("Server switch timed out.");
  }

  @Override
  public void close() {
    // Nothing beyond the current backend, which the player closes.
  }
}
