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
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.config.ConfigPacket;
import dev.warp.protocol.packet.config.ConfigPluginMessage;
import dev.warp.protocol.packet.config.FinishConfiguration;
import dev.warp.protocol.packet.config.KnownPacks;
import dev.warp.protocol.packet.config.RegistryData;
import dev.warp.protocol.packet.config.ResourcePackPush;
import dev.warp.protocol.packet.config.ServerData;
import dev.warp.protocol.packet.play.KeepAlive;

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
    // KeepAlive is a PlayPacket registered in CONFIG state.
    if (packet instanceof KeepAlive keepAlive) {
      // Client KeepAlive during config — validate against our pending ID.
      player.handleKeepAliveResponse(keepAlive.id());
      return;
    }

    if (!(packet instanceof ConfigPacket configPacket)) {
      logger.warn(
          "Unexpected packet in client CONFIG state: {}", packet.getClass().getSimpleName());
      return;
    }

    switch (configPacket) {
      case AcknowledgeFinishConfiguration ignored -> handleAcknowledgeFinish();
      case ClientInformation clientInfo -> forwardToBackend(clientInfo);
      case ConfigPluginMessage pluginMessage -> forwardToBackend(pluginMessage);
      case KnownPacks knownPacks -> forwardToBackend(knownPacks);
      // Clientbound packets should never arrive from a client.
      case FinishConfiguration ignored -> {
        /* protocol violation, ignore */
      }
      case RegistryData ignored -> {
        /* protocol violation, ignore */
      }
      case ResourcePackPush ignored -> {
        /* protocol violation, ignore */
      }
      case ServerData ignored -> {
        /* protocol violation, ignore */
      }
      case ConfigDisconnect ignored -> {
        /* protocol violation, ignore */
      }
    }
  }

  @Override
  public void disconnected() {
    // Client disconnected during configuration.
    BackendConnection backend = player.backendConnection();
    if (backend != null) {
      backend.disconnect();
    }
  }

  // ---------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------

  private void handleAcknowledgeFinish() {
    // Transition client side to PLAY (we are on the client event loop already).
    player.clientConnection().setState(ProtocolState.PLAY);
    player
        .clientConnection()
        .setSessionHandler(new ClientPlaySessionHandler(player, player.clientConnection()));

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

  private void forwardToBackend(Packet packet) {
    // Use write() without flush — readComplete() will flush the batch.
    backendConnection.write(packet);
  }

  @Override
  public void readComplete() {
    backendConnection.flush();
  }
}
