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
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.ChatAcknowledgement;
import dev.warp.protocol.packet.play.ChatCommand;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.LegacyChatMessage;
import dev.warp.protocol.packet.play.PlayClientSettings;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.protocol.packet.play.PlayPluginMessage;
import dev.warp.protocol.packet.play.PlayerInfo;
import dev.warp.protocol.packet.play.PlayerInfoRemove;
import dev.warp.protocol.packet.play.PlayerInfoUpdate;
import dev.warp.protocol.packet.play.ResourcePackResponse;
import dev.warp.protocol.packet.play.Respawn;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.play.TabCompleteRequest;
import dev.warp.protocol.packet.play.TabCompleteResponse;
import dev.warp.protocol.packet.play.TabListHeaderFooter;
import dev.warp.protocol.packet.play.Transfer;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles packets from the backend in PLAY state (CLIENTBOUND).
 *
 * <p>Most backend packets are blind-forwarded to the client as the frames they arrived in: no
 * deserialization, no allocation and, when both connections use compression, no inflate or deflate
 * either. Only the handful of packet types the proxy acts on are decoded.
 *
 * <p>A backend's packets reach the client only while the client plays on it ({@link
 * ConnectedPlayer#isPlayingOn}): this handler claims the client when it is installed, and a server
 * switch hands the client to the next backend. Whatever a backend the player is leaving still sends
 * is dropped.
 *
 * <h3>Hot path</h3>
 *
 * <p>{@link #handleBlind(ByteBuf)} is the hot path. It hands each frame to {@link
 * MinecraftConnection#forward}, which writes it to the client verbatim whenever the client accepts
 * it. Combined with write batching (one {@code flush()} per {@code channelReadComplete}), this
 * gives optimal throughput.
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
  public void activated() {
    // Installed once the client plays on this backend: its packets reach the client from now on,
    // and the client is read at the pace of this backend. The backend the client leaves may have
    // paused it (writabilityChanged), and no longer resumes it once the client is gone.
    player.playOn(backendConnection);
    player.clientConnection().setAutoRead(backendConnection.channel().isWritable());
  }

  @Override
  public void disconnected() {
    if (player.isPlayingOn(backendConnection)) {
      logger.info("Backend disconnected for player {}", player.username());
    } else {
      logger.debug("Backend left by {} closed", player.username());
    }
    player.scheduleBackendFailure(backendConnection);
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
      case PlayDisconnect _ -> handleDisconnect();
      case SystemChatMessage chatMessage -> forwardToClient(chatMessage);
      case StartConfiguration _ -> handleStartConfiguration();
      case Transfer transfer -> forwardToClient(transfer);
      case BundleDelimiter delimiter -> handleBundleDelimiter(delimiter);
      case PlayPluginMessage pluginMessage -> handlePluginMessage(pluginMessage);
      case TabCompleteResponse response -> forwardToClient(response);
      // Decoded before 1.20.2 only, to clear them from the client on a server switch.
      case PlayerInfo info -> forwardTracked(info);
      case PlayerInfoUpdate update -> forwardTracked(update);
      case PlayerInfoRemove remove -> forwardTracked(remove);
      case BossBar bossBar -> forwardTracked(bossBar);
      case TabListHeaderFooter headerFooter -> forwardToClient(headerFooter);
      case ClearTitles clearTitles -> forwardToClient(clearTitles);
      // Serverbound packets should never arrive from a backend: a protocol violation, ignored.
      case ChatCommand _,
          ChatAcknowledgement _,
          LegacyChatMessage _,
          PlayClientSettings _,
          ResourcePackResponse _,
          AcknowledgeConfiguration _,
          TabCompleteRequest _ -> {}
    }
  }

  // ---------------------------------------------------------------------------
  // Blind forwarding hot path
  // ---------------------------------------------------------------------------

  @Override
  public void handleBlind(ByteBuf buf) {
    // A backend the client no longer plays on (left in a server switch) may still send PLAY
    // packets: drop them, the client is elsewhere or not in PLAY at all.
    if (!player.isPlayingOn(backendConnection)) {
      buf.release();
      return;
    }
    player.clientConnection().forward(buf, backendConnection);
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
    if (player.isPlayingOn(backendConnection)) {
      player.clientConnection().setAutoRead(backendConnection.channel().isWritable());
    }
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
    // From 1.20.2 the Join Game ends the configuration phase of a join or a switch; before, the
    // RespawnSwitch takes the first one, and a server sending another one moves its own player.
    if (player.isPlayingOn(backendConnection)) {
      player.setEntityId(joinGame.entityId());
    }
    // From 26.2 the client takes the server's online mode from the Join Game. The backend runs
    // offline behind the proxy: the client must see the proxy's mode instead, as before 26.2.
    forwardToClient(
        joinGame.withOnlineMode(player.loginContext().onlineMode(), player.protocolVersion()));
  }

  private void handleDisconnect() {
    logger.info("Backend kicked player {} during play", player.username());
    player.scheduleBackendFailure(backendConnection);
  }

  private void handleStartConfiguration() {
    // Backend requests reconfiguration (e.g. registry reload).
    // Forward to client — the AcknowledgeConfiguration from the client will be
    // forwarded back to the backend via ClientPlaySessionHandler.
    player.clientConnection().writeAndFlush(new StartConfiguration());
  }

  private void handleBundleDelimiter(BundleDelimiter delimiter) {
    if (player.isPlayingOn(backendConnection)) {
      player.toggleBundle();
      player.clientConnection().write(delimiter);
    }
  }

  private void handlePluginMessage(PlayPluginMessage pluginMessage) {
    // TODO: Intercept minecraft:brand and rewrite. For now, forward all.
    forwardToClient(pluginMessage);
  }

  /** Forwards a packet that changes what a Join Game does not clear, and follows the change. */
  private void forwardTracked(PlayPacket packet) {
    if (player.isPlayingOn(backendConnection)) {
      player.leftovers().track(packet);
      player.clientConnection().write(packet);
    }
  }

  private void forwardToClient(Packet packet) {
    if (player.isPlayingOn(backendConnection)) {
      // write() without flush: readComplete() flushes the batch.
      player.clientConnection().write(packet);
    }
  }
}
