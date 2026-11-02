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
package dev.warp.api.player;

import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerConnection;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.text.Component;

/**
 * A player connected to the proxy, from the moment its login was allowed until it disconnects.
 *
 * <p>Every method is safe to call from any thread. Methods that send something to the client take
 * effect on its connection's thread, in the order they were called.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface Player extends InboundConnection {

  /**
   * Returns the player's UUID: Mojang's in online mode, derived from the name in offline mode, or
   * whatever a plugin set in {@link dev.warp.api.event.player.GameProfileRequestEvent}.
   *
   * @return the UUID
   */
  UUID uniqueId();

  /**
   * Returns the player's name.
   *
   * @return the name
   */
  String username();

  /**
   * Returns the player's profile, as forwarded to backend servers.
   *
   * @return the profile
   */
  GameProfile profile();

  /**
   * Returns an identifier of this session, distinct for every login of the same account, so that
   * data keyed by session never mixes two logins of one player.
   *
   * @return the session identifier
   */
  UUID sessionId();

  /**
   * Returns whether the player was authenticated with Mojang.
   *
   * @return {@code true} in online mode
   */
  boolean isOnlineMode();

  /**
   * Returns the server the player plays on. It stays the same during a switch until the new server
   * took over; see {@link #pendingServer()} for the one being switched to.
   *
   * @return the connection to the current server, or empty before the player reached its first
   *     server
   */
  Optional<ServerConnection> currentServer();

  /**
   * Returns the server the player is being moved to.
   *
   * @return the server, or empty when no switch is in progress
   */
  Optional<RegisteredServer> pendingServer();

  /**
   * Returns the round trip time between the proxy and the client, measured with keep-alives.
   *
   * @return the latency in milliseconds, or {@code -1} before the first measure
   */
  long ping();

  /**
   * Moves the player to a server. The player keeps playing on its current server until the new one
   * accepted it; if the new server cannot be reached or refuses it, the player stays.
   *
   * @param server the server to move to
   * @return completed with the outcome once known; never completes exceptionally
   */
  CompletableFuture<ConnectionResult> connect(RegisteredServer server);

  /**
   * Disconnects the player with a reason shown on its screen.
   *
   * @param reason the reason
   */
  void disconnect(Component reason);
}
