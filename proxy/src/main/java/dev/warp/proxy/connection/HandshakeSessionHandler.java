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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the initial handshake from a connecting client.
 *
 * <p>The client sends a single {@link Handshake} packet declaring the protocol version and intended
 * next state (status or login). This handler validates the request, updates the decoder/encoder
 * state, and transitions to the appropriate session handler.
 *
 * <p>Supported transitions:
 *
 * <ul>
 *   <li>{@code nextState = 1} → {@link StatusSessionHandler} (server list ping)
 *   <li>{@code nextState = 2} → login (not yet implemented — connection is closed)
 *   <li>{@code nextState = 3} → transfer (1.20.5+ — not yet implemented)
 * </ul>
 */
final class HandshakeSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(HandshakeSessionHandler.class);

  /** Handshake nextState value for status (server list ping). */
  private static final int STATUS_NEXT_STATE = 1;

  /** Handshake nextState value for login. */
  private static final int LOGIN_NEXT_STATE = 2;

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

    switch (handshake.nextState()) {
      case STATUS_NEXT_STATE -> {
        connection.setState(ProtocolState.STATUS);
        connection.setSessionHandler(new StatusSessionHandler(connection));
      }
      case LOGIN_NEXT_STATE -> {
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
      default -> {
        logger.warn(
            "Unknown nextState {} from {}",
            handshake.nextState(),
            connection.channel().remoteAddress());
        connection.close();
      }
    }
  }
}
