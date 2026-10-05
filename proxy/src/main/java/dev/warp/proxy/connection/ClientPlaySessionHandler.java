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
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.config.ClientInformation;
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
import dev.warp.proxy.server.ServerRegistry;

import java.util.stream.Collectors;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles packets from the client in PLAY state (SERVERBOUND).
 *
 * <p>Most client packets are blind-forwarded to the backend as the frames they arrived in — no
 * deserialization, no allocation, and compressed frames keep their original bytes once verified.
 * Only the handful of packet types the proxy acts on are decoded.
 *
 * <h3>Hot path</h3>
 *
 * <p>{@link #handleBlind(ByteBuf)} is the hot path. It hands each frame to {@link
 * MinecraftConnection#forward}, which writes it to the backend verbatim whenever the backend
 * accepts it. Combined with write batching (one {@code flush()} per {@code channelReadComplete}),
 * this gives optimal throughput.
 */
final class ClientPlaySessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(ClientPlaySessionHandler.class);

  private final ConnectedPlayer player;

  ClientPlaySessionHandler(ConnectedPlayer player) {
    this.player = player;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void activated() {
    player.switchComplete();
    player.startKeepAliveTask();
  }

  @Override
  public void deactivated() {
    player.cancelKeepAliveTask();
  }

  @Override
  public void disconnected() {
    player.disconnect();
  }

  // ---------------------------------------------------------------------------
  // Typed packet dispatch
  // ---------------------------------------------------------------------------

  @Override
  public void handle(Packet packet) {
    if (!(packet instanceof PlayPacket playPacket)) {
      logger.warn(
          "Unexpected packet in client PLAY handler: {}", packet.getClass().getSimpleName());
      return;
    }

    switch (playPacket) {
      case KeepAlive keepAlive -> handleKeepAlive(keepAlive);
      case ChatCommand chatCommand -> handleChatCommand(chatCommand);
      case PlayClientSettings settings -> handleClientSettings(settings);
      case ResourcePackResponse response -> forwardToBackend(response);
      case AcknowledgeConfiguration ignored -> handleAcknowledgeConfiguration();
      case TabCompleteRequest request -> forwardToBackend(request);
      case PlayPluginMessage pluginMessage -> handlePluginMessage(pluginMessage);
      // Clientbound packets should never arrive from a client — silently ignore.
      case PlayDisconnect ignored -> {
        /* protocol violation */
      }
      case JoinGame ignored -> {
        /* protocol violation */
      }
      case Respawn ignored -> {
        /* protocol violation */
      }
      case SystemChatMessage ignored -> {
        /* protocol violation */
      }
      case StartConfiguration ignored -> {
        /* protocol violation */
      }
      case Transfer ignored -> {
        /* protocol violation */
      }
      case BundleDelimiter ignored -> {
        /* protocol violation */
      }
      case TabCompleteResponse ignored -> {
        /* protocol violation */
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Blind forwarding hot path
  // ---------------------------------------------------------------------------

  @Override
  public void handleBlind(ByteBuf buf) {
    BackendConnection backend = player.backendConnection();
    if (backend != null && backend.isActive()) {
      backend.connection().forward(buf, player.clientConnection());
    } else {
      buf.release();
    }
  }

  // ---------------------------------------------------------------------------
  // Write batching + back-pressure
  // ---------------------------------------------------------------------------

  @Override
  public void readComplete() {
    BackendConnection backend = player.backendConnection();
    if (backend != null) {
      backend.connection().flush();
    }
  }

  @Override
  public void writabilityChanged() {
    // This handler sits on the client channel. When the client channel's writability
    // changes, toggle auto-read on the backend channel: if the client can't accept writes
    // (slow connection), stop reading from the backend to apply back-pressure.
    BackendConnection backend = player.backendConnection();
    if (backend != null && backend.isActive()) {
      backend.connection().setAutoRead(player.clientConnection().channel().isWritable());
    }
  }

  // ---------------------------------------------------------------------------
  // Packet handlers
  // ---------------------------------------------------------------------------

  private void handleKeepAlive(KeepAlive keepAlive) {
    if (!player.handleKeepAliveResponse(keepAlive.id())) {
      logger.warn("Invalid KeepAlive ID from player {}", player.username());
    }
    // Never forward client KeepAlive to backend — proxy manages its own.
  }

  private void handleChatCommand(ChatCommand chatCommand) {
    String command = chatCommand.command();
    if (command.equals("server") || command.startsWith("server ")) {
      handleServerCommand(command.length() > 7 ? command.substring(7).trim() : "");
      return;
    }
    forwardToBackend(chatCommand);
  }

  private void handleServerCommand(String args) {
    ServerRegistry registry = player.loginContext().serverRegistry();

    if (args.isEmpty()) {
      // List servers and current server.
      String current = player.currentServerName();
      String list =
          registry.allServers().stream()
              .map(s -> s.name().equals(current) ? "[" + s.name() + "]" : s.name())
              .collect(Collectors.joining(", "));
      player
          .clientConnection()
          .writeAndFlush(
              new SystemChatMessage(
                  dev.warp.protocol.packet.TextComponent.plainText(
                      "Servers: " + list, player.protocolVersion()),
                  false));
      return;
    }

    ServerInfo target = registry.getServer(args);
    if (target == null) {
      player
          .clientConnection()
          .writeAndFlush(
              new SystemChatMessage(
                  dev.warp.protocol.packet.TextComponent.plainText(
                      "Unknown server: " + args, player.protocolVersion()),
                  false));
      return;
    }

    player.switchServer(target);
  }

  private void handleClientSettings(PlayClientSettings settings) {
    // Cache as ClientInformation for replay during server switches.
    player.cacheClientSettings(
        new ClientInformation(
            settings.locale(),
            settings.viewDistance(),
            settings.chatMode(),
            settings.chatColors(),
            settings.displayedSkinParts(),
            settings.mainHand(),
            settings.enableTextFiltering(),
            settings.allowServerListings(),
            settings.particleStatus()));
    forwardToBackend(settings);
  }

  private void handlePluginMessage(PlayPluginMessage pluginMessage) {
    // TODO: Route specific channels (bungeecord:main, warp:main). For now, forward all.
    forwardToBackend(pluginMessage);
  }

  private void handleAcknowledgeConfiguration() {
    // Client acknowledges re-entering configuration state.
    ServerInfo target = player.pendingSwitchTarget();
    if (target != null && player.isSwitching()) {
      // Proxy-initiated server switch.
      player.onSwitchAcknowledged(target);
    } else {
      // Backend-initiated reconfiguration (e.g. data pack reload).
      // Transition both sides to CONFIG state and relay config packets.
      handleBackendReconfiguration();
    }
  }

  private void handleBackendReconfiguration() {
    BackendConnection backend = player.backendConnection();
    if (backend == null || !backend.isActive()) {
      return;
    }
    MinecraftConnection backendConn = backend.connection();

    // Transition client to CONFIG and install relay handler.
    player.clientConnection().setState(ProtocolState.CONFIGURATION);
    player
        .clientConnection()
        .setSessionHandler(new ClientConfigSessionHandler(player, backendConn));

    // Transition backend to CONFIG and forward the acknowledgment.
    backendConn
        .channel()
        .eventLoop()
        .execute(
            () -> {
              if (!backendConn.channel().isActive()) {
                return;
              }
              backendConn.writeAndFlush(new AcknowledgeConfiguration());
              backendConn.setState(ProtocolState.CONFIGURATION);
              backendConn.setSessionHandler(new BackendConfigSessionHandler(player, backendConn));
            });
  }

  private void forwardToBackend(Packet packet) {
    // Use write() without flush — readComplete() will flush the batch.
    BackendConnection backend = player.backendConnection();
    if (backend != null && backend.isActive()) {
      backend.connection().write(packet);
    }
  }
}
