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
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.legacy.LegacyDisconnect;
import dev.warp.protocol.legacy.LegacyLogin;
import dev.warp.protocol.legacy.LegacyPing;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.handshake.HandshakePacket;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.proxy.i18n.Message;
import dev.warp.proxy.i18n.PlayerMessages;
import dev.warp.proxy.limit.Refusal;

import io.netty.channel.ChannelFutureListener;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the initial handshake from a connecting client.
 *
 * <p>The client sends a single {@link Handshake} packet declaring the protocol version and intended
 * next state. This handler validates the request, records the {@link HandshakeIntent} on the
 * connection, updates the decoder/encoder state, and transitions to the appropriate session
 * handler.
 *
 * <p>Before any handshake, a client of Minecraft 1.6 or older sends a legacy ping or login, which
 * {@link dev.warp.protocol.legacy.LegacyPingDecoder} recognizes: a ping is answered with the server
 * list entry, a login with the versions the proxy speaks.
 *
 * <p>Supported transitions:
 *
 * <ul>
 *   <li>{@code nextState = 1} → {@link StatusSessionHandler} (server list ping), any version
 *   <li>{@code nextState = 2} → {@link LoginSessionHandler}, supported versions only: another
 *       version gets vanilla's outdated client or server reason, with the versions that can join
 *   <li>{@code nextState = 3} → {@link LoginSessionHandler} for a player that a server sent here
 *       with a Transfer packet: from 1.20.5, and only if {@code accepts-transfers} is on. Otherwise
 *       the client is refused in the login state with vanilla's translated reason, as a vanilla
 *       server does. A transfer from an older protocol, which has none, closes the connection.
 * </ul>
 *
 * <p>A handshake no client sends (an unknown next state, any other packet) is counted as a {@link
 * Refusal} and reset, as are refused versions and transfers: never logged one by one, since anyone
 * can send them as fast as they like.
 */
final class HandshakeSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(HandshakeSessionHandler.class);

  /** What a client of Minecraft 1.6 or older is told when it tries to join. */
  static final String OUTDATED_CLIENT =
      "Outdated client: this server supports Minecraft "
          + ProtocolVersion.oldest().name()
          + " to "
          + ProtocolVersion.latest().name();

  /** The versions that can join, the argument of vanilla's outdated client and server reasons. */
  static final String SUPPORTED_VERSIONS =
      ProtocolVersion.oldest().name() + "-" + ProtocolVersion.latest().name();

  /**
   * Vanilla's reason for a client older than the server: "Outdated client! Please use %s" up to
   * 1.20.x, "Incompatible client! Please use %s" since.
   */
  static final String OUTDATED_CLIENT_KEY = "multiplayer.disconnect.outdated_client";

  /**
   * Vanilla's reason for a client newer than the server: "Outdated server! I'm still on %s" up to
   * 1.20.x, "Incompatible client! Please use %s" since.
   */
  static final String OUTDATED_SERVER_KEY = "multiplayer.disconnect.outdated_server";

  private final MinecraftConnection connection;
  private final ServerLoginContext loginContext;

  HandshakeSessionHandler(MinecraftConnection connection, ServerLoginContext loginContext) {
    this.connection = connection;
    this.loginContext = loginContext;
  }

  @Override
  public void handle(Packet packet) {
    if (packet instanceof LegacyPing ping) {
      StatusSessionHandler.answerLegacy(connection, loginContext, ping);
      return;
    }
    if (packet instanceof LegacyLogin) {
      refuseLegacyLogin();
      return;
    }
    if (!(packet instanceof HandshakePacket handshakePacket)) {
      refuse(Refusal.UNEXPECTED_PACKET, packet.getClass().getSimpleName());
      return;
    }

    // Exhaustive switch on the sealed HandshakePacket hierarchy.
    switch (handshakePacket) {
      case Handshake handshake -> handleHandshake(handshake);
    }
  }

  /**
   * Tells a client of Minecraft 1.6 or older, trying to join, which versions the proxy speaks: it
   * shows the reason on its disconnect screen, rather than a lost connection.
   */
  private void refuseLegacyLogin() {
    logger.debug("Refusing a Minecraft 1.6 or older client from {}", connection.remoteAddress());
    var _ =
        connection
            .channel()
            .writeAndFlush(new LegacyDisconnect(OUTDATED_CLIENT))
            .addListener(ChannelFutureListener.CLOSE);
  }

  private void handleHandshake(Handshake handshake) {
    // Resolve the protocol version. Unknown versions get null — the proxy still
    // handles status pings from unknown clients (shows MOTD with version mismatch).
    ProtocolVersion version = ProtocolVersion.byProtocolId(handshake.protocolVersion());
    if (version != null) {
      connection.setVersion(version);
    }

    connection.setVirtualHost(handshake.serverAddress());

    HandshakeIntent intent = HandshakeIntent.byId(handshake.nextState());
    if (intent == null) {
      refuse(Refusal.INVALID_HANDSHAKE, "next state " + handshake.nextState());
      return;
    }
    connection.setHandshakeIntent(intent);

    ConnectionGuard guard = connection.guard();
    switch (intent) {
      case STATUS -> {
        if (guard != null) {
          guard.statusStarted();
        }
        connection.setState(ProtocolState.STATUS);
        connection.setSessionHandler(
            new StatusSessionHandler(connection, loginContext, handshake.protocolVersion()));
      }
      case LOGIN -> login(handshake, version);
      case TRANSFER -> transfer(handshake, version);
    }
  }

  private void login(Handshake handshake, @Nullable ProtocolVersion version) {
    if (version == null) {
      refuseVersion(handshake.protocolVersion());
      return;
    }
    ConnectionGuard guard = connection.guard();
    if (guard != null) {
      guard.loginStarted();
    }
    connection.setState(ProtocolState.LOGIN);
    // Plugin handlers go in before the first login packet is read.
    if (!loginContext
        .pipelines()
        .initializeClient(connection, version.protocol(), new WarpInboundConnection(connection))) {
      return; // a plugin failed to set the connection up, which closed it
    }
    connection.setSessionHandler(new LoginSessionHandler(connection, loginContext));
  }

  /**
   * Tells a client of a protocol the proxy does not speak, as a vanilla server does, that its game
   * is too old or too new, with the versions that can join: the client translates the reason.
   * Counted, not logged: any connection can claim any protocol.
   */
  private void refuseVersion(int protocol) {
    loginContext.limits().refused(Refusal.UNSUPPORTED_VERSION, connection.remoteAddress());
    logger.debug("Refusing protocol {} from {}", protocol, connection.remoteAddress());
    boolean older = protocol < ProtocolVersion.oldest().protocol();
    Component reason =
        Component.translatable(
            older ? OUTDATED_CLIENT_KEY : OUTDATED_SERVER_KEY, Component.text(SUPPORTED_VERSIONS));
    // In the login state, whose disconnect packet the client reads; JSON in every version, written
    // as the nearest version Warp speaks would read it.
    ProtocolVersion nearest = older ? ProtocolVersion.oldest() : ProtocolVersion.latest();
    connection.setVersion(nearest);
    connection.setState(ProtocolState.LOGIN);
    connection.writeAndClose(LoginDisconnect.of(reason, nearest));
  }

  /**
   * Closes a connection whose handshake no client sends, counted under {@code reason} instead of a
   * log line.
   */
  private void refuse(Refusal reason, String what) {
    loginContext.limits().refused(reason, connection.remoteAddress());
    logger.debug("Refused {} in HANDSHAKE: {}", connection.remoteAddress(), what);
    connection.reset();
  }

  /**
   * Logs in a player that a Transfer packet sent here, or refuses it. The version is checked first,
   * as vanilla does: an unsupported client is turned away as such, whether transfers are accepted
   * or not.
   */
  private void transfer(Handshake handshake, @Nullable ProtocolVersion version) {
    if (version != null && !HandshakeIntent.TRANSFER.existsIn(version)) {
      // Not a real client: no version before 1.20.5 has a Transfer packet, nor a translation for
      // the refusal. Close, as for any other handshake no client sends.
      refuse(Refusal.INVALID_HANDSHAKE, "a transfer from " + version + ", before transfers");
      return;
    }
    if (version == null || loginContext.acceptsTransfers()) {
      login(handshake, version);
      return;
    }
    // Counted, not logged: anyone can send a transfer handshake.
    loginContext.limits().refused(Refusal.TRANSFER_REFUSED, connection.remoteAddress());
    logger.debug(
        "Refused a transfer from {}: accepts-transfers is off", connection.remoteAddress());
    // In the login state, whose disconnect packet the client reads: the handshake state has none,
    // and a disconnect written in it never reaches the player (Velocity, Gate).
    connection.setState(ProtocolState.LOGIN);
    PlayerMessages messages = loginContext.messages();
    connection.writeAndClose(
        LoginDisconnect.of(
            messages.render(Message.TRANSFERS_DISABLED.of(), messages.defaultLocale(), version),
            version));
  }
}
