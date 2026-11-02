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
import dev.warp.proxy.connection.ConnectedPlayer;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A backend server registered with the proxy, and the players playing on it.
 *
 * <p>A player counts on a server from the moment its packets flow from that server (the server's
 * play session handler is active) until it plays on another server or leaves the proxy. A player
 * switching servers through the configuration phase still counts on the server it is leaving until
 * the new one takes over, so a server never loses a player it may get back.
 *
 * <p>Unregistering a server does not kick its players: they keep playing on it and it keeps
 * counting them, but {@link ServerRegistry} no longer returns it. Registering the same name again
 * creates a new instance with its own players.
 */
public final class BackendServer {

  private final ServerInfo info;
  private final Set<ConnectedPlayer> players = ConcurrentHashMap.newKeySet();

  /**
   * Creates a server with no players.
   *
   * @param info the server's name and address
   */
  BackendServer(ServerInfo info) {
    this.info = info;
  }

  /**
   * Returns the server's name and address.
   *
   * @return the server info
   */
  public ServerInfo info() {
    return info;
  }

  /**
   * Returns the server's name, in lowercase.
   *
   * @return the name
   */
  public String name() {
    return info.name();
  }

  /**
   * Returns the server's address.
   *
   * @return the address
   */
  public InetSocketAddress address() {
    return info.address();
  }

  /**
   * Returns a snapshot of the players playing on this server.
   *
   * @return the players, unmodifiable
   */
  public Set<ConnectedPlayer> players() {
    return Set.copyOf(players);
  }

  /**
   * Returns how many players play on this server, without copying them.
   *
   * @return the player count
   */
  public int playerCount() {
    return players.size();
  }

  /**
   * Counts a player on this server.
   *
   * @param player the player now playing here
   */
  public void addPlayer(ConnectedPlayer player) {
    players.add(player);
  }

  /**
   * Stops counting a player on this server.
   *
   * @param player the player that left
   */
  public void removePlayer(ConnectedPlayer player) {
    players.remove(player);
  }

  @Override
  public String toString() {
    return "BackendServer[" + info.name() + " " + info.address() + "]";
  }
}
