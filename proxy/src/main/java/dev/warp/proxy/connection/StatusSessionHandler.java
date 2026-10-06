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

import java.util.Objects;

import org.jspecify.annotations.Nullable;
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
   * The version name every client sees: the range of versions Warp supports. A client only shows it
   * when the advertised protocol is not its own, as the reason it cannot join.
   */
  static final String VERSION_NAME =
      "Warp " + ProtocolVersion.oldest().name() + "-" + ProtocolVersion.latest().name();

  /**
   * The JSON status response for each supported protocol, indexed by protocol number. Built once,
   * so that answering a ping builds no string (Velocity builds a fresh JSON string per ping).
   */
  private static final @Nullable String[] STATUS_JSON_BY_PROTOCOL = buildStatusJsonByProtocol();

  private final MinecraftConnection connection;

  /** The JSON this client gets, advertising its own protocol when Warp supports it. */
  private final String statusJson;

  /** Guards against duplicate StatusRequest packets. */
  private boolean sentResponse;

  /**
   * Creates the handler of one server list ping.
   *
   * @param connection the client connection
   * @param clientVersion the version the client announced in its handshake, or {@code null} when
   *     Warp does not support it
   */
  StatusSessionHandler(MinecraftConnection connection, @Nullable ProtocolVersion clientVersion) {
    this.connection = connection;
    this.statusJson = statusJson(clientVersion);
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

    connection.writeAndFlush(new StatusResponse(statusJson));
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
   * Returns the status JSON for a client: it advertises the client's own protocol when Warp
   * supports it, so that the server list shows Warp as compatible, and the latest one otherwise, as
   * Velocity does.
   *
   * @param clientVersion the client's version, or {@code null} when Warp does not support it
   * @return the JSON status response
   */
  static String statusJson(@Nullable ProtocolVersion clientVersion) {
    ProtocolVersion advertised = clientVersion != null ? clientVersion : ProtocolVersion.latest();
    return Objects.requireNonNull(STATUS_JSON_BY_PROTOCOL[advertised.protocol()]);
  }

  /**
   * Builds the JSON status response of every supported protocol.
   *
   * <p>Format follows the Minecraft protocol specification:
   *
   * <pre>{@code
   * {
   *   "version": { "name": "Warp <oldest>-<latest>", "protocol": <protocol> },
   *   "players": { "max": 0, "online": 0 },
   *   "description": { "text": "A Warp Proxy" }
   * }
   * }</pre>
   */
  private static @Nullable String[] buildStatusJsonByProtocol() {
    int maxProtocol =
        ProtocolVersion.values().stream().mapToInt(ProtocolVersion::protocol).max().orElseThrow();
    @Nullable String[] json = new String[maxProtocol + 1];
    for (ProtocolVersion version : ProtocolVersion.values()) {
      json[version.protocol()] =
          "{"
              + "\"version\":{\"name\":\""
              + VERSION_NAME
              + "\",\"protocol\":"
              + version.protocol()
              + "},"
              + "\"players\":{\"max\":0,\"online\":0},"
              + "\"description\":{\"text\":\"A Warp Proxy\"}"
              + "}";
    }
    return json;
  }
}
