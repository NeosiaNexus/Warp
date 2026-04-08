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
import dev.warp.protocol.packet.play.AcknowledgeConfiguration;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.ChatCommand;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.PlayClientSettings;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.protocol.packet.play.PlayPluginMessage;
import dev.warp.protocol.packet.play.ResourcePackResponse;
import dev.warp.protocol.packet.play.Respawn;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.play.TabCompleteRequest;
import dev.warp.protocol.packet.play.TabCompleteResponse;
import dev.warp.protocol.packet.play.Transfer;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles packets from the backend in PLAY state (CLIENTBOUND).
 *
 * <p>Most backend packets are blind-forwarded to the client as raw {@link ByteBuf} — zero
 * deserialization, zero allocation. Only the ~15 proxy-critical packet types are decoded and
 * handled.
 *
 * <h3>Hot path</h3>
 *
 * <p>{@link #handleBlind(ByteBuf)} is the hot path. It forwards raw buffers directly to the client
 * channel via {@link MinecraftConnection#writeBlind(ByteBuf)}. Combined with write batching (one
 * {@code flush()} per {@code channelReadComplete}), this gives optimal throughput.
 */
final class BackendPlaySessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(BackendPlaySessionHandler.class);

  private final ConnectedPlayer player;
  private final MinecraftConnection backendConnection;

  BackendPlaySessionHandler(ConnectedPlayer player, MinecraftConnection backendConnection) {
    this.player = player;
    this.backendConnection = backendConnection;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void disconnected() {
    if (player.isSwitching()) {
      logger.debug("Old backend disconnected during server switch for {}", player.username());
      return;
    }
    logger.info("Backend disconnected for player {}", player.username());
    player.scheduleBackendFailure();
  }

  // ---------------------------------------------------------------------------
  // Typed packet dispatch
  // ---------------------------------------------------------------------------

  @Override
  public void handle(Packet packet) {
    if (!(packet instanceof PlayPacket playPacket)) {
      logger.warn(
          "Unexpected packet in backend PLAY handler: {}", packet.getClass().getSimpleName());
      return;
    }

    switch (playPacket) {
      case KeepAlive keepAlive -> handleKeepAlive(keepAlive);
      case JoinGame joinGame -> handleJoinGame(joinGame);
      case Respawn respawn -> forwardToClient(respawn);
      case PlayDisconnect disconnect -> handleDisconnect(disconnect);
      case SystemChatMessage chatMessage -> forwardToClient(chatMessage);
      case StartConfiguration ignored -> handleStartConfiguration();
      case Transfer transfer -> forwardToClient(transfer);
      case BundleDelimiter delimiter -> handleBundleDelimiter(delimiter);
      case PlayPluginMessage pluginMessage -> handlePluginMessage(pluginMessage);
      case TabCompleteResponse response -> forwardToClient(response);
      // Serverbound packets should never arrive from a backend — silently ignore.
      case ChatCommand ignored -> {
        /* protocol violation */
      }
      case PlayClientSettings ignored -> {
        /* protocol violation */
      }
      case ResourcePackResponse ignored -> {
        /* protocol violation */
      }
      case AcknowledgeConfiguration ignored -> {
        /* protocol violation */
      }
      case TabCompleteRequest ignored -> {
        /* protocol violation */
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Blind forwarding hot path
  // ---------------------------------------------------------------------------

  @Override
  public void handleBlind(ByteBuf buf) {
    // During a server switch, the old backend may still send PLAY packets after the
    // client has entered CONFIG state. Drop them to avoid "unknown packet ID" on the client.
    if (player.isSwitching()) {
      buf.release();
      return;
    }
    if (player.clientConnection().channel().isActive()) {
      player.clientConnection().writeBlind(buf);
    } else {
      buf.release();
    }
  }

  // ---------------------------------------------------------------------------
  // Write batching + back-pressure
  // ---------------------------------------------------------------------------

  @Override
  public void readComplete() {
    player.clientConnection().flush();
  }

  @Override
  public void writabilityChanged() {
    // This handler sits on the backend channel. When the backend channel's writability
    // changes, toggle auto-read on the client channel: if the backend can't accept writes
    // (buffer full), stop reading from the client to apply back-pressure.
    player.clientConnection().setAutoRead(backendConnection.channel().isWritable());
  }

  // ---------------------------------------------------------------------------
  // Packet handlers
  // ---------------------------------------------------------------------------

  private void handleKeepAlive(KeepAlive keepAlive) {
    // Echo back to backend immediately — proxy responds on behalf of the player.
    backendConnection.writeAndFlush(keepAlive);
    // Never forward backend KeepAlive to client — proxy manages its own.
  }

  private void handleJoinGame(JoinGame joinGame) {
    // Store the entity ID for future server-switch dimension tricks.
    player.setEntityId(joinGame.entityId());
    forwardToClient(joinGame);
  }

  @SuppressWarnings("unused")
  private void handleDisconnect(PlayDisconnect disconnect) {
    logger.info("Backend kicked player {} during play", player.username());
    player.scheduleBackendFailure();
  }

  private void handleStartConfiguration() {
    // Backend requests reconfiguration (e.g. registry reload).
    // Forward to client — the AcknowledgeConfiguration from the client will be
    // forwarded back to the backend via ClientPlaySessionHandler.
    player.clientConnection().writeAndFlush(new StartConfiguration());
  }

  private void handleBundleDelimiter(BundleDelimiter delimiter) {
    player.toggleBundle();
    forwardToClient(delimiter);
  }

  private void handlePluginMessage(PlayPluginMessage pluginMessage) {
    // TODO: Intercept minecraft:brand and rewrite. For now, forward all.
    forwardToClient(pluginMessage);
  }

  private void forwardToClient(Packet packet) {
    if (player.isSwitching()) {
      return;
    }
    // Use write() without flush — readComplete() will flush the batch.
    player.clientConnection().write(packet);
  }
}
