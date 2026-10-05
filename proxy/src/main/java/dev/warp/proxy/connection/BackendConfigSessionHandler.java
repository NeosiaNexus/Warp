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
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.config.FinishConfiguration;
import dev.warp.protocol.packet.play.KeepAlive;

import io.netty.buffer.ByteBuf;
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
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void activated() {
    logger.debug("BackendConfigSessionHandler activated for {}", player.username());

    // Replay cached client settings to the new backend before the client resumes.
    // The backend needs the player's locale, view distance, skin parts, etc. immediately.
    // The client may also resend settings during CONFIG; receiving them twice is harmless —
    // the backend simply overwrites its state with each update.
    ClientInformation cached = player.cachedClientSettings();
    if (cached != null) {
      backendConnection.writeAndFlush(cached);
    }

    // Install client config handler immediately so the client can respond to KnownPacks,
    // PluginMessages, etc. as soon as the backend starts sending configuration data.
    // Without this, the client would still have the LoginSessionHandler active and would
    // drop any CONFIG-state packets.
    MinecraftConnection clientConn = player.clientConnection();
    clientConn
        .channel()
        .eventLoop()
        .execute(
            () -> {
              if (!clientConn.channel().isActive()) {
                logger.warn(
                    "Client channel inactive when installing ConfigHandler for {}",
                    player.username());
                return;
              }
              logger.debug("Installing ClientConfigSessionHandler for {}", player.username());
              clientConn.setSessionHandler(
                  new ClientConfigSessionHandler(player, backendConnection));
              // Ensure auto-read is enabled. During a server switch, auto-read is paused
              // while waiting for the new backend to reach CONFIG. This is idempotent for
              // initial connections where auto-read was never paused.
              clientConn.setAutoRead(true);
            });
  }

  // ---------------------------------------------------------------------------
  // Packet handling
  // ---------------------------------------------------------------------------

  @Override
  public void handle(Packet packet) {
    logger.trace(
        "Backend CONFIG handle: {} for {}", packet.getClass().getSimpleName(), player.username());
    if (packet instanceof KeepAlive keepAlive) {
      backendConnection.writeAndFlush(keepAlive);
    } else if (packet instanceof FinishConfiguration) {
      handleFinishConfiguration();
    } else if (packet instanceof ConfigDisconnect) {
      handleDisconnect();
    }
  }

  @Override
  public void disconnected() {
    logger.info("Backend disconnected during configuration for player {}", player.username());
    player.scheduleBackendFailure();
  }

  // ---------------------------------------------------------------------------
  // Handlers
  // ---------------------------------------------------------------------------

  private void handleFinishConfiguration() {
    // Forward FinishConfiguration to the client. ClientConfigSessionHandler is already
    // installed (see activated()) and will handle the client's AcknowledgeFinishConfiguration.
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
            });
  }

  private void handleDisconnect() {
    logger.info("Backend kicked player {} during configuration", player.username());
    player.scheduleBackendFailure();
  }

  @Override
  public void handleBlind(ByteBuf buf) {
    logger.trace("Backend→client blind {} bytes for {}", buf.readableBytes(), player.username());
    player.clientConnection().forward(buf, backendConnection);
  }

  @Override
  public void readComplete() {
    player.clientConnection().flush();
  }
}
