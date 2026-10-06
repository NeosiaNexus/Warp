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

import dev.warp.api.server.ServerInfo;

/**
 * How a player moves from one backend to another, chosen once per connection from the client's
 * protocol version.
 *
 * <ul>
 *   <li>{@link ReconfigurationSwitch}, from 1.20.2: the proxy sends the client back to the
 *       configuration phase, which resets it, and logs it in to the new server from there.
 *   <li>{@link RespawnSwitch}, before 1.20.2: there is no configuration phase. The player stays on
 *       its server while the proxy logs it in to the new one; the new server's Join Game, followed
 *       by a Respawn, then moves the client over, and the proxy clears what the previous server
 *       left on it.
 * </ul>
 *
 * <p>{@link ConnectedPlayer} keeps what both share (the switch in progress, the fallback order, the
 * watchdog) and asks the strategy for the rest. Every method runs on the player's event loop.
 */
sealed interface ServerSwitch permits ReconfigurationSwitch, RespawnSwitch {

  /**
   * Returns the strategy for the player's protocol version.
   *
   * @param player the player
   * @return the configuration phase switch from 1.20.2, the respawn switch before
   */
  static ServerSwitch of(ConnectedPlayer player) {
    return player.protocolVersion().supportsConfigurationState()
        ? new ReconfigurationSwitch(player)
        : new RespawnSwitch(player);
  }

  /**
   * Starts moving a player that plays on a server to {@code target}, already recorded as the
   * player's pending switch target.
   *
   * @param target the server to move to
   */
  void start(ServerInfo target);

  /**
   * Moves the player to {@code fallback} after {@code failedServer} refused it, kicked it or went
   * down, whether the player was on it or joining it.
   *
   * @param failedServer the server that failed
   * @param fallback the next server in the fallback order
   */
  void fallBack(String failedServer, ServerInfo fallback);

  /**
   * Takes a backend whose connection is up: its login has started.
   *
   * @param backend the new backend
   */
  void connected(BackendConnection backend);

  /**
   * Reacts to a connection that could not be opened.
   *
   * @param target the server that could not be reached
   */
  void connectFailed(ServerInfo target);

  /**
   * Moves a backend that logged the player in to its next state.
   *
   * @param backend the backend connection
   */
  void loggedIn(MinecraftConnection backend);

  /**
   * Reacts to a backend that refused the player, kicked it, or closed. A backend can report several
   * times (a kick, then the closed connection), and backends the player has left report too: only
   * the first report of a backend the player depends on counts.
   *
   * @param backend the backend connection
   */
  void failed(MinecraftConnection backend);

  /** Reacts to a switch that did not complete within {@link ConnectedPlayer}'s time limit. */
  void timedOut();

  /** Releases what the strategy holds when the player disconnects. */
  void close();
}
