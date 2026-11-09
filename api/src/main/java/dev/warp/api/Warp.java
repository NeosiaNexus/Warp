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
package dev.warp.api;

import dev.warp.api.antibot.AntiBot;
import dev.warp.api.command.CommandSource;
import dev.warp.api.fleet.Fleet;
import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.player.Player;
import dev.warp.api.plugin.PluginManager;
import dev.warp.api.server.ServerGroupRegistry;
import dev.warp.api.server.ServerRegistry;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Entry point to the Warp API: the proxy itself.
 *
 * <p>Plugins receive it in their constructor (see {@link dev.warp.api.plugin.PluginContext}); code
 * that cannot be injected can use {@link WarpProvider#get()}.
 *
 * <p>Implemented by the proxy; plugins must not implement it. Methods are added as the API grows.
 */
public interface Warp {

  /**
   * Returns the proxy version string.
   *
   * @return the version string (e.g. {@code "0.1.0-beta.2"})
   */
  String version();

  /**
   * Returns the plugins the proxy found.
   *
   * @return the plugin manager
   */
  PluginManager plugins();

  /**
   * Returns a snapshot of the connected players.
   *
   * @return the players, unmodifiable
   */
  Collection<Player> players();

  /**
   * Returns the player connected with a UUID.
   *
   * @param uniqueId the UUID
   * @return the player, or empty if none is connected with it
   */
  Optional<Player> player(UUID uniqueId);

  /**
   * Returns the player connected with a name, ignoring case.
   *
   * @param username the name
   * @return the player, or empty if none is connected with it
   */
  Optional<Player> player(String username);

  /**
   * Returns how many players are connected, without building a snapshot.
   *
   * @return the player count
   */
  int playerCount();

  /**
   * Returns the backend servers.
   *
   * @return the server registry
   */
  ServerRegistry servers();

  /**
   * Returns the server groups players are balanced over, and the built-in balancing strategies.
   *
   * @return the group registry
   */
  ServerGroupRegistry groups();

  /**
   * Returns the proxy's built-in anti-bot: whether it is under attack, lockdown, the identities it
   * trusts, and the extension points for shared stores and extra checks.
   *
   * @return the anti-bot
   */
  AntiBot antiBot();

  /**
   * Returns the fleet: every proxy that shares this proxy's store and every player on them. Without
   * a store configured, it is this proxy alone, and the same code works on one node and on many.
   *
   * @return the fleet
   */
  Fleet fleet();

  /**
   * Returns the console, as the command source that runs the commands typed at it. It has every
   * permission, and it is an audience: a component sent to it is logged, rendered in colour on the
   * console and as plain text in the log file.
   *
   * @return the console
   */
  CommandSource console();

  // ---------------------------------------------------------------------------
  // Drain (readiness holds belong to plugins: see PluginContext#holdReadiness)
  // ---------------------------------------------------------------------------

  /**
   * Starts draining the proxy, as {@code POST /drain} or SIGTERM would: load balancers are told to
   * stop sending players here, the players are moved to {@code drain.transfer-to} at the configured
   * rate (a {@link dev.warp.api.event.player.PlayerDrainEvent} lets plugins keep some), the proxy
   * waits for those kept to leave, then shuts down. Logins are refused or accepted as {@code
   * drain.new-players} says. Each phase fires a {@link dev.warp.api.event.proxy.ProxyDrainEvent}.
   *
   * <p>The drain cannot be cancelled. Calling this again while draining returns the same future.
   *
   * @return completed with the final status, its outcome set, once every player left or the
   *     time-out expired, just before the proxy shuts down; never completes exceptionally
   * @throws IllegalStateException if the proxy has not finished starting
   */
  CompletableFuture<DrainStatus> drain();

  /**
   * Returns whether the proxy is draining, from the moment the drain is asked for.
   *
   * @return {@code true} once a drain started
   */
  boolean isDraining();

  /**
   * Returns where the drain stands, or a status in {@link DrainPhase#NONE} when the proxy is not
   * draining.
   *
   * @return a snapshot of the drain's status
   */
  DrainStatus drainStatus();
}
