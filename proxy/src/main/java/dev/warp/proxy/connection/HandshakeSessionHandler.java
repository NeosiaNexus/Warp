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
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.handshake.HandshakePacket;
import dev.warp.protocol.packet.login.LoginDisconnect;

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
 * <p>Supported transitions:
 *
 * <ul>
 *   <li>{@code nextState = 1} → {@link StatusSessionHandler} (server list ping), any version
 *   <li>{@code nextState = 2} → {@link LoginSessionHandler}, supported versions only
 *   <li>{@code nextState = 3} → {@link LoginSessionHandler} for a player that a server sent here
 *       with a Transfer packet: from 1.20.5, and only if {@code accepts-transfers} is on. Otherwise
 *       the client is refused in the login state with vanilla's translated reason, as a vanilla
 *       server does. A transfer from an older protocol, which has none, closes the connection.
 * </ul>
 */
final class HandshakeSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(HandshakeSessionHandler.class);

  /** Vanilla's reason for refusing a transfer, translated by the client (1.20.5+). */
  static final String TRANSFERS_DISABLED = "multiplayer.disconnect.transfers_disabled";

  private final MinecraftConnection connection;
  private final ServerLoginContext loginContext;

  HandshakeSessionHandler(MinecraftConnection connection, ServerLoginContext loginContext) {
    this.connection = connection;
    this.loginContext = loginContext;
  }

  @Override
  public void handle(Packet packet) {
    if (!(packet instanceof HandshakePacket handshakePacket)) {
      logger.warn("Unexpected packet in HANDSHAKE state: {}", packet.getClass().getSimpleName());
      connection.close();
      return;
    }

    // Exhaustive switch on the sealed HandshakePacket hierarchy.
    switch (handshakePacket) {
      case Handshake handshake -> handleHandshake(handshake);
    }
  }

  private void handleHandshake(Handshake handshake) {
    // Resolve the protocol version. Unknown versions get null — the proxy still
    // handles status pings from unknown clients (shows MOTD with version mismatch).
    ProtocolVersion version = ProtocolVersion.byProtocolId(handshake.protocolVersion());
    if (version != null) {
      connection.setVersion(version);
    }

    HandshakeIntent intent = HandshakeIntent.byId(handshake.nextState());
    if (intent == null) {
      logger.warn(
          "Unknown nextState {} from {}",
          handshake.nextState(),
          connection.channel().remoteAddress());
      connection.close();
      return;
    }
    connection.setHandshakeIntent(intent);

    switch (intent) {
      case STATUS -> {
        connection.setState(ProtocolState.STATUS);
        connection.setSessionHandler(new StatusSessionHandler(connection, version));
      }
      case LOGIN -> login(handshake, version);
      case TRANSFER -> transfer(handshake, version);
    }
  }

  private void login(Handshake handshake, @Nullable ProtocolVersion version) {
    if (version == null) {
      logger.warn(
          "Unsupported protocol version {} from {}",
          handshake.protocolVersion(),
          connection.channel().remoteAddress());
      connection.close();
      return;
    }
    connection.setState(ProtocolState.LOGIN);
    connection.setSessionHandler(new LoginSessionHandler(connection, loginContext));
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
      logger.warn(
          "Transfer handshake from {} declares {}, which predates transfers (1.20.5)",
          connection.channel().remoteAddress(),
          version);
      connection.close();
      return;
    }
    if (version == null || loginContext.acceptsTransfers()) {
      login(handshake, version);
      return;
    }
    logger.info(
        "Refused a transfer from {}: accepts-transfers is off",
        connection.channel().remoteAddress());
    // In the login state, whose disconnect packet the client reads: the handshake state has none,
    // and a disconnect written in it never reaches the player (Velocity, Gate).
    connection.setState(ProtocolState.LOGIN);
    connection.writeAndClose(LoginDisconnect.ofTranslation(TRANSFERS_DISABLED));
  }
}
