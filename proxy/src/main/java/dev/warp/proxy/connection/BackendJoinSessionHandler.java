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

import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.PlayDisconnect;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds a backend that logged a player in before 1.20.2, until its Join Game.
 *
 * <p>Those versions go from login straight to PLAY, and the first PLAY packet a server sends is the
 * Join Game. The player may still be playing on another server: nothing from this backend reaches
 * the client before the Join Game, which {@link RespawnSwitch#joined} turns into the switch. The
 * backend's keep-alives are answered meanwhile, and a kick or a closed connection fails the switch.
 * Velocity's {@code TransitionSessionHandler} plays the same role.
 */
final class BackendJoinSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(BackendJoinSessionHandler.class);

  private final ConnectedPlayer player;
  private final RespawnSwitch serverSwitch;
  private final MinecraftConnection backendConnection;

  BackendJoinSessionHandler(
      ConnectedPlayer player, RespawnSwitch serverSwitch, MinecraftConnection backendConnection) {
    this.player = player;
    this.serverSwitch = serverSwitch;
    this.backendConnection = backendConnection;
  }

  @Override
  public void handle(Packet packet) {
    switch (packet) {
      case JoinGame joinGame -> serverSwitch.joined(backendConnection, joinGame);
      case KeepAlive keepAlive -> backendConnection.writeAndFlush(keepAlive);
      case PlayDisconnect ignored -> {
        logger.info("Backend kicked {} before its Join Game", player.username());
        player.scheduleBackendFailure(backendConnection);
      }
      default ->
          logger.debug(
              "Dropping {} sent to {} before the Join Game",
              packet.getClass().getSimpleName(),
              player.username());
    }
  }

  @Override
  public void handleBlind(ByteBuf buf) {
    buf.release(); // nothing reaches the client before the Join Game
  }

  @Override
  public void disconnected() {
    player.scheduleBackendFailure(backendConnection);
  }
}
