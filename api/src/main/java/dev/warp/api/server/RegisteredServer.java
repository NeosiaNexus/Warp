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
package dev.warp.api.server;

import dev.warp.api.player.Player;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * A backend server the proxy can send players to.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface RegisteredServer {

  /**
   * Returns the server's name and address.
   *
   * @return the server info
   */
  ServerInfo info();

  /**
   * Returns the server's name, in lowercase.
   *
   * @return the name
   */
  default String name() {
    return info().name();
  }

  /**
   * Returns a snapshot of the players playing on this server. A player counts on a server from the
   * moment it plays there until it plays elsewhere or leaves; during a switch it still counts on
   * the server it is leaving.
   *
   * @return the players, unmodifiable
   */
  Collection<Player> players();

  /**
   * Returns how many players play on this server, without building a snapshot.
   *
   * @return the player count
   */
  int playerCount();

  /**
   * Returns whether this server is still registered. An unregistered server keeps its players (they
   * keep playing on it) but new players cannot be sent to it.
   *
   * @return {@code true} while registered
   */
  boolean isRegistered();

  /**
   * Asks the server for its server list entry, as a client refreshing its server list would.
   *
   * @return completed with the answer, or exceptionally if the server cannot be reached or does not
   *     answer in time
   */
  CompletableFuture<ServerPing> ping();

  /**
   * Asks the server for its server list entry, announcing a protocol version in the handshake.
   *
   * <p>A vanilla server answers with its own version whatever it is asked. A server that translates
   * versions itself (ViaVersion installed on the backend) answers with the version it is asked for
   * whenever it can translate it: to learn the version it really runs, ask with {@code -1}, a
   * version no client has, as Velocity's own ping does. {@link #ping()} announces the newest
   * version the proxy supports.
   *
   * @param protocolVersion the protocol version number to announce, or {@code -1} for none
   * @return completed with the answer, or exceptionally if the server cannot be reached or does not
   *     answer in time
   */
  CompletableFuture<ServerPing> ping(int protocolVersion);
}
