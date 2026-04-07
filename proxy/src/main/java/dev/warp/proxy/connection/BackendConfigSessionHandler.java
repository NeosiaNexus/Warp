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
 * Handles the CONFIGURATION state on the backend side (1.20.2+).
 *
 * <p>Relays configuration data from the backend to the client: registry data, known packs, resource
 * packs, and server data. When the backend sends {@link FinishConfiguration}, the handler forwards
 * it to the client and waits for the client's {@link AcknowledgeFinishConfiguration}.
 *
 * <p>Note: {@link KeepAlive} is a {@link dev.warp.protocol.packet.play.PlayPacket PlayPacket} that
 * is also registered in the CONFIGURATION state registry. It arrives via {@code handle()} as a
 * {@code Packet} that is NOT a {@code ConfigPacket}, so it requires a separate {@code instanceof}
 * check.
 */
final class BackendConfigSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(BackendConfigSessionHandler.class);

  private final ConnectedPlayer player;
  private final MinecraftConnection backendConnection;

  BackendConfigSessionHandler(ConnectedPlayer player, MinecraftConnection backendConnection) {
    this.player = player;
    this.backendConnection = backendConnection;
  }

  // ---------------------------------------------------------------------------
  // Packet handling
  // ---------------------------------------------------------------------------

  @Override
  public void handle(Packet packet) {
    // KeepAlive is a PlayPacket registered in CONFIG state — handle it first.
    if (packet instanceof KeepAlive keepAlive) {
      // Echo back to backend immediately — proxy handles backend keepalives.
      backendConnection.writeAndFlush(keepAlive);
      return;
    }

    if (!(packet instanceof ConfigPacket configPacket)) {
      logger.warn(
          "Unexpected packet in backend CONFIG state: {}", packet.getClass().getSimpleName());
      return;
    }

    switch (configPacket) {
      case RegistryData registryData -> forwardToClient(registryData);
      case KnownPacks knownPacks -> forwardToClient(knownPacks);
      case ServerData serverData -> forwardToClient(serverData);
      case ResourcePackPush resourcePack -> forwardToClient(resourcePack);
      case ConfigPluginMessage pluginMessage -> forwardToClient(pluginMessage);
      case FinishConfiguration ignored -> handleFinishConfiguration();
      case ConfigDisconnect disconnect -> handleDisconnect(disconnect);
      // Serverbound packets should never arrive from a backend.
      case AcknowledgeFinishConfiguration ignored -> {
        /* protocol violation, ignore */
      }
      case ClientInformation ignored -> {
        /* protocol violation, ignore */
      }
    }
  }

  @Override
  public void disconnected() {
    logger.info("Backend disconnected during configuration for player {}", player.username());
  }

  // ---------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------

  private void handleFinishConfiguration() {
    // Schedule client-side mutations on the client's event loop — the current code
    // runs on the backend event loop, which may be a different thread.
    MinecraftConnection clientConn = player.clientConnection();
    clientConn
        .channel()
        .eventLoop()
        .execute(
            () -> {
              if (!clientConn.channel().isActive()) {
                return;
              }
              clientConn.writeAndFlush(new FinishConfiguration());
              clientConn.setState(ProtocolState.CONFIGURATION);
              clientConn.setSessionHandler(
                  new ClientConfigSessionHandler(player, backendConnection));
            });
  }

  @SuppressWarnings("unused")
  private void handleDisconnect(ConfigDisconnect disconnect) {
    logger.info("Backend disconnected player {} during configuration", player.username());
    player.disconnect();
  }

  private void forwardToClient(Packet packet) {
    // Use write() without flush — readComplete() will flush the batch.
    player.clientConnection().write(packet);
  }

  @Override
  public void readComplete() {
    player.clientConnection().flush();
  }
}
