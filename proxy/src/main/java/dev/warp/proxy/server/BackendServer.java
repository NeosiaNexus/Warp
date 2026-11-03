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

import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerInfo;
import dev.warp.api.server.ServerPing;
import dev.warp.proxy.connection.ConnectedPlayer;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

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
public final class BackendServer implements RegisteredServer {

  private final ServerInfo info;
  private final ServerRegistry registry;
  private final Set<ConnectedPlayer> players = ConcurrentHashMap.newKeySet();

  /**
   * Creates a server with no players.
   *
   * @param info the server's name and address
   * @param registry the registry that knows whether it is registered, and pings it
   */
  BackendServer(ServerInfo info, ServerRegistry registry) {
    this.info = info;
    this.registry = registry;
  }

  @Override
  public ServerInfo info() {
    return info;
  }

  @Override
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
   * Returns a snapshot of the players playing on this server, as the proxy sees them.
   *
   * @return the players, unmodifiable
   */
  public Set<ConnectedPlayer> connectedPlayers() {
    return Set.copyOf(players);
  }

  /**
   * Returns the first player playing on this server that {@code test} accepts, without copying the
   * players: the way to reach a server, whose plugin messages only travel over a player's
   * connection.
   *
   * @param test tried on each player in turn, until it accepts one
   * @return the player accepted, or {@code null} if none was
   */
  public @Nullable ConnectedPlayer anyPlayer(Predicate<ConnectedPlayer> test) {
    for (ConnectedPlayer player : players) {
      if (test.test(player)) {
        return player;
      }
    }
    return null;
  }

  @Override
  public Collection<Player> players() {
    List<Player> snapshot = players.stream().<Player>map(ConnectedPlayer::api).toList();
    return snapshot;
  }

  @Override
  public int playerCount() {
    return players.size();
  }

  @Override
  public boolean isRegistered() {
    return registry.getServer(info.name()) == this;
  }

  @Override
  public CompletableFuture<ServerPing> ping() {
    return registry.pinger().ping(info, registry.pingTimeout());
  }

  @Override
  public CompletableFuture<ServerPing> ping(Duration timeout) {
    return registry.pinger().ping(info, checkTimeout(timeout));
  }

  @Override
  public CompletableFuture<ServerPing> ping(int protocolVersion) {
    return registry.pinger().ping(info, protocolVersion, registry.pingTimeout());
  }

  /**
   * Checks a ping time-out.
   *
   * @param timeout the time-out
   * @return the time-out
   * @throws IllegalArgumentException if it is zero or negative
   */
  static Duration checkTimeout(Duration timeout) {
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("A ping time-out must be positive, got " + timeout);
    }
    return timeout;
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
