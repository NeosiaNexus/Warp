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
package dev.warp.proxy.server;

import dev.warp.api.server.ServerInfo;
import dev.warp.api.server.ServerPing;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Asks a backend server for its server list entry. */
@FunctionalInterface
public interface ServerPinger {

  /**
   * Pings a server.
   *
   * @param server the server
   * @param timeout the longest the whole ping may take, connection included; positive
   * @return completed with its answer, or exceptionally with a {@link
   *     dev.warp.api.server.ServerPingException} saying why it failed
   */
  CompletableFuture<ServerPing> ping(ServerInfo server, Duration timeout);

  /**
   * Pings a server, announcing a protocol version in the handshake.
   *
   * @param server the server
   * @param protocolVersion the protocol version number to announce, {@code -1} for none
   * @param timeout the longest the whole ping may take, connection included; positive
   * @return completed with its answer, or exceptionally with a {@link
   *     dev.warp.api.server.ServerPingException} saying why it failed
   */
  default CompletableFuture<ServerPing> ping(
      ServerInfo server, int protocolVersion, Duration timeout) {
    return ping(server, timeout);
  }
}
