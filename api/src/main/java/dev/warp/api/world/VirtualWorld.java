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
package dev.warp.api.world;

import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * The proxy's own world: a void with one platform, inside the proxy, where a player can stand
 * without any backend. The proxy puts unknown players there for the anti-bot's world check and
 * keeps players there when no server can take them; plugins use it to hold a player (a queue, a
 * maintenance screen, a lobby of their own) and release it to a server later.
 *
 * <p>A player in the world is a full {@link Player}: it receives messages, action bars, titles,
 * boss bars and sounds as on any server, and {@link Player#currentServer()} is empty. Nothing it
 * sends reaches a server. Leaving the world is a server switch like any other: the player joins the
 * server in the same session, through the configuration phase (1.20.2+) or a respawn (older
 * clients), and events fire as for every server change.
 *
 * <p>The world exists for every client version the proxy accepts, 1.7.10 to the latest. Modded
 * clients (Forge, NeoForge) refuse a vanilla-looking world and are never put in it by the proxy
 * itself; {@link #supports} says whether a given player can be.
 */
public interface VirtualWorld {

  /**
   * Returns whether this player can be put in the world: its client version has a world layout and
   * its platform is not a modded one.
   *
   * @param player the player
   * @return {@code true} when {@link #hold} can place it
   */
  boolean supports(Player player);

  /**
   * Puts the player in the world, leaving the server it plays on, if any. A player already in the
   * world stays there. The future completes once the player stands in the world, or fails if it
   * left meanwhile or {@link #supports} is false. The player keeps waiting there until {@link
   * #release}, {@link Player#connect}, or it disconnects; the proxy's own retries for a player held
   * for want of a server stop once a plugin holds it.
   *
   * @param player the player
   * @return completed when the player is in the world
   */
  CompletableFuture<Void> hold(Player player);

  /**
   * Returns whether the player is in the world right now.
   *
   * @param player the player
   * @return {@code true} while it stands in the world
   */
  boolean holds(Player player);

  /**
   * Returns the players in the world right now: held by a plugin, waiting for a server, or being
   * verified.
   *
   * @return an unmodifiable snapshot
   */
  Collection<Player> players();

  /**
   * Sends the player from the world to a server, through the usual server switch and its events.
   * Equivalent to {@link Player#connect} for a player in the world; a player elsewhere is switched
   * all the same.
   *
   * @param player the player
   * @param server where it goes
   * @return the outcome of the connection
   */
  CompletableFuture<ConnectionResult> release(Player player, RegisteredServer server);

  /**
   * Sends the player from the world to the server it would have joined on login: the one its route
   * or the default order names, through {@code PlayerChooseInitialServerEvent} when the player
   * never reached a server, else the fallback order. With no server available the player stays in
   * the world, held for one as a player whose server went down is, and the result waits until it
   * reaches a server or leaves.
   *
   * @param player the player
   * @return the outcome of the connection
   */
  CompletableFuture<ConnectionResult> release(Player player);
}
