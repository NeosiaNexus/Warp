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

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.status.PingRequest;
import dev.warp.protocol.packet.status.PongResponse;
import dev.warp.protocol.packet.status.StatusPacket;
import dev.warp.protocol.packet.status.StatusRequest;
import dev.warp.protocol.packet.status.StatusResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the server list ping (SLP) flow after a handshake with {@code nextState = 1}.
 *
 * <p>Expected packet flow:
 *
 * <pre>{@code
 * C → S: StatusRequest        (empty — triggers response)
 * S → C: StatusResponse       (JSON with version, players, MOTD)
 * C → S: PingRequest          (long payload — client timestamp)
 * S → C: PongResponse         (echo payload)
 *        [connection closes]
 * }</pre>
 *
 * <p>The handler enforces ordering: a {@link StatusRequest} must be received before any {@link
 * PingRequest}, and duplicate requests are ignored to prevent abuse.
 */
final class StatusSessionHandler implements SessionHandler {

  private static final Logger logger = LoggerFactory.getLogger(StatusSessionHandler.class);

  /**
   * Cached JSON status response. Computed once since {@link ProtocolVersion#latest()} is constant
   * for the lifetime of the JVM. This eliminates per-ping String concatenation — a weakness in
   * Velocity which allocates a fresh JSON string on every SLP request.
   */
  private static final String CACHED_STATUS_JSON = buildStatusJson();

  private final MinecraftConnection connection;

  /** Guards against duplicate StatusRequest packets. */
  private boolean sentResponse;

  StatusSessionHandler(MinecraftConnection connection) {
    this.connection = connection;
  }

  @Override
  public void handle(Packet packet) {
    if (!(packet instanceof StatusPacket statusPacket)) {
      logger.warn("Unexpected packet in STATUS state: {}", packet.getClass().getSimpleName());
      connection.close();
      return;
    }

    // Exhaustive switch on the sealed StatusPacket hierarchy.
    switch (statusPacket) {
      case StatusRequest ignored -> handleStatusRequest();
      case PingRequest ping -> handlePingRequest(ping);
      // Clientbound packets should never arrive from a client.
      case StatusResponse ignored -> connection.close();
      case PongResponse ignored -> connection.close();
    }
  }

  private void handleStatusRequest() {
    if (sentResponse) {
      // Duplicate — ignore silently per protocol convention.
      return;
    }
    sentResponse = true;

    connection.writeAndFlush(new StatusResponse(CACHED_STATUS_JSON));
  }

  private void handlePingRequest(PingRequest ping) {
    if (!sentResponse) {
      // PingRequest without a prior StatusRequest — protocol violation.
      connection.close();
      return;
    }

    // Echo the payload and close after the write reaches the socket.
    connection.writeAndClose(new PongResponse(ping.payload()));
  }

  /**
   * Builds the JSON status response.
   *
   * <p>Format follows the Minecraft protocol specification:
   *
   * <pre>{@code
   * {
   *   "version": { "name": "Warp <latest>", "protocol": <latest_protocol> },
   *   "players": { "max": 0, "online": 0 },
   *   "description": { "text": "A Warp Proxy" }
   * }
   * }</pre>
   */
  private static String buildStatusJson() {
    ProtocolVersion latest = ProtocolVersion.latest();
    return "{"
        + "\"version\":{\"name\":\"Warp "
        + latest.name()
        + "\",\"protocol\":"
        + latest.protocol()
        + "},"
        + "\"players\":{\"max\":0,\"online\":0},"
        + "\"description\":{\"text\":\"A Warp Proxy\"}"
        + "}";
  }
}
