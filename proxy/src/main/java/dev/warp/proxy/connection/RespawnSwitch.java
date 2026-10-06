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
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.protocol.packet.play.Respawn;
import dev.warp.protocol.packet.play.SpawnInfo;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Switches servers before 1.20.2, where there is no configuration phase: the classic proxy
 * technique, as Velocity does it.
 *
 * <p>The player keeps playing on its server while the proxy logs it in to the new one. The new
 * backend is held, its packets kept from the client, until its Join Game: the proxy then drops the
 * previous backend and sends the client
 *
 * <ol>
 *   <li>the packets that clear what the previous server left ({@link ServerLeftovers}): tab list
 *       entries, boss bars, tab list header and footer, title;
 *   <li>the new server's Join Game, which gives the client a new world and the new entity ID.
 *       Before 1.16 its dimension is changed (overworld to nether, anything else to overworld):
 *       those clients only reload the world on a Respawn into another dimension;
 *   <li>a Respawn into the new server's world ({@link Respawn#of(SpawnInfo)}).
 * </ol>
 *
 * <p>A server that cannot be reached, refuses the player or stalls only cancels the switch: the
 * player stays where it is. When the player has no server left (its server went down, or it is
 * still joining), the fallback order applies instead. The first Join Game a player receives is
 * forwarded as it is: there is nothing to clear yet.
 *
 * <p>Sources: Velocity {@code ClientPlaySessionHandler#handleBackendJoinGame} and {@code
 * #doFastClientServerSwitch}, {@code TransitionSessionHandler}.
 */
final class RespawnSwitch implements ServerSwitch {

  private static final Logger logger = LoggerFactory.getLogger(RespawnSwitch.class);

  private final ConnectedPlayer player;

  /** The backend logging the player in, until its Join Game; {@code null} otherwise. */
  private @Nullable BackendConnection pending;

  /** Whether the client has received a Join Game, so is in a world. */
  private boolean spawned;

  RespawnSwitch(ConnectedPlayer player) {
    this.player = player;
  }

  // ---------------------------------------------------------------------------
  // Switching
  // ---------------------------------------------------------------------------

  @Override
  public void start(ServerInfo target) {
    player.scheduleSwitchTimeout();
    player.connect(target);
  }

  @Override
  public void fallBack(String failedServer, ServerInfo fallback) {
    if (spawned) {
      logger.info(
          "Backend failed for {}, switching to fallback '{}'", player.username(), fallback.name());
      player.sendSystemMessage("Connecting to " + fallback.name() + "...");
    } else {
      logger.info(
          "Could not connect {} to '{}', trying fallback '{}'",
          player.username(),
          failedServer,
          fallback.name());
    }
    player.beginSwitch(fallback);
    start(fallback);
  }

  /**
   * Moves the client to the backend that sent {@code joinGame}, if it is the one being logged in.
   *
   * @param backend the backend connection that sent the Join Game
   * @param joinGame its Join Game
   */
  void joined(MinecraftConnection backend, JoinGame joinGame) {
    BackendConnection joining = pending;
    if (joining == null || joining.connection() != backend) {
      backend.close(); // a switch that was abandoned
      return;
    }
    pending = null;
    BackendConnection previous = player.backendConnection();
    player.setBackendConnection(joining);
    if (previous != null) {
      previous.disconnect();
    }
    player.setCurrentServerName(joining.server().name());
    player.setEntityId(joinGame.entityId());

    MinecraftConnection client = player.clientConnection();
    if (spawned) {
      logger.debug("Respawning {} into '{}'", player.username(), joining.server().name());
      player.closeBundle();
      for (PlayPacket leftover : player.leftovers().clear(player.protocolVersion())) {
        client.write(leftover);
      }
      writeRejoin(client, joinGame);
    } else {
      spawned = true;
      client.setSessionHandler(new ClientPlaySessionHandler(player));
      client.write(joinGame);
    }
    backend.setSessionHandler(new BackendPlaySessionHandler(player, backend));
    client.flush();
    player.switchComplete();
  }

  /** Writes the Join Game and the Respawn that move a client already in a world. */
  private void writeRejoin(MinecraftConnection client, JoinGame joinGame) {
    if (!(joinGame.body() instanceof JoinGame.Decoded body)) {
      throw new IllegalStateException("Join Game is decoded before 1.20.2: " + joinGame);
    }
    SpawnInfo spawn = body.spawn();
    if (player.protocolVersion().isOlderThan(ProtocolVersion.MINECRAFT_1_16)) {
      // These clients reload the world only on a Respawn into another dimension: join elsewhere.
      SpawnInfo elsewhere = spawn.withDimension(spawn.dimension() == 0 ? -1 : 0);
      client.write(
          new JoinGame(joinGame.entityId(), joinGame.hardcore(), body.withSpawn(elsewhere)));
    } else {
      client.write(joinGame);
    }
    client.write(Respawn.of(spawn));
  }

  // ---------------------------------------------------------------------------
  // Backend lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void connected(BackendConnection backend) {
    pending = backend;
  }

  @Override
  public void connectFailed(ServerInfo target) {
    pendingFailed(target);
  }

  @Override
  public void loggedIn(MinecraftConnection backend) {
    // No configuration phase: the backend goes straight to PLAY, and waits for its Join Game.
    backend.setState(ProtocolState.PLAY);
    backend.setSessionHandler(new BackendJoinSessionHandler(player, this, backend));
  }

  @Override
  public void failed(MinecraftConnection backend) {
    BackendConnection joining = pending;
    if (joining != null && joining.connection() == backend) {
      pending = null;
      joining.disconnect();
      pendingFailed(joining.server());
      return;
    }
    BackendConnection current = player.backendConnection();
    if (current != null && current.connection() == backend) {
      player.setBackendConnection(null);
      if (joining == null) {
        player.handleBackendFailure(current.server().name());
      }
      // Otherwise a switch is already taking the player elsewhere.
    }
  }

  @Override
  public void timedOut() {
    BackendConnection joining = pending;
    if (joining == null) {
      player.setSwitching(false);
      player.disconnectWithReason("Server switch timed out.");
      return;
    }
    pending = null;
    joining.disconnect();
    pendingFailed(joining.server());
  }

  @Override
  public void close() {
    BackendConnection joining = pending;
    pending = null;
    if (joining != null) {
      joining.disconnect();
    }
  }

  /**
   * The server the player was being moved to failed: the player stays on its server if it still has
   * one, otherwise the fallback order applies.
   */
  private void pendingFailed(ServerInfo target) {
    BackendConnection current = player.backendConnection();
    if (current != null && current.isActive()) {
      logger.info(
          "Could not move {} to '{}', staying on its server", player.username(), target.name());
      player.cancelSwitch();
      player.sendSystemMessage("Could not connect to " + target.name() + ".");
      return;
    }
    player.handleBackendFailure(target.name());
  }
}
