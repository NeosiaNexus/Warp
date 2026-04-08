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

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration;
import dev.warp.protocol.packet.play.KeepAlive;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the client-side CONFIGURATION state (1.20.2+).
 *
 * <p>Relays client configuration responses to the backend. When the client sends {@link
 * AcknowledgeFinishConfiguration}, this handler forwards it to the backend and transitions both
 * sides to PLAY state.
 */
final class ClientConfigSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(ClientConfigSessionHandler.class);

  private final ConnectedPlayer player;
  private final MinecraftConnection backendConnection;

  ClientConfigSessionHandler(ConnectedPlayer player, MinecraftConnection backendConnection) {
    this.player = player;
    this.backendConnection = backendConnection;
  }

  // ---------------------------------------------------------------------------
  // Packet handling
  // ---------------------------------------------------------------------------

  @Override
  public void handle(Packet packet) {
    logger.trace(
        "Client CONFIG handle: {} for {}", packet.getClass().getSimpleName(), player.username());
    if (packet instanceof KeepAlive keepAlive) {
      player.handleKeepAliveResponse(keepAlive.id());
    } else if (packet instanceof AcknowledgeFinishConfiguration) {
      handleAcknowledgeFinish();
    }
  }

  @Override
  public void disconnected() {
    player.disconnect();
  }

  // ---------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------

  private void handleAcknowledgeFinish() {
    // Transition client side to PLAY (we are on the client event loop already).
    player.clientConnection().setState(ProtocolState.PLAY);
    player.clientConnection().setSessionHandler(new ClientPlaySessionHandler(player));

    // Schedule backend-side mutations on the backend's event loop.
    backendConnection
        .channel()
        .eventLoop()
        .execute(
            () -> {
              if (!backendConnection.channel().isActive()) {
                return;
              }
              backendConnection.writeAndFlush(new AcknowledgeFinishConfiguration());
              backendConnection.setState(ProtocolState.PLAY);
              backendConnection.setSessionHandler(
                  new BackendPlaySessionHandler(player, backendConnection));
            });
  }

  @Override
  public void handleBlind(ByteBuf buf) {
    logger.trace("Client→backend blind {} bytes for {}", buf.readableBytes(), player.username());
    backendConnection.writeBlind(buf);
  }

  @Override
  public void readComplete() {
    backendConnection.flush();
  }
}
