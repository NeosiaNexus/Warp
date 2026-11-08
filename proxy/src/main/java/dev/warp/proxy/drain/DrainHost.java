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
package dev.warp.proxy.drain;

import dev.warp.api.event.player.PlayerDrainEvent;
import dev.warp.api.lifecycle.DrainStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * What the drain needs from the proxy: its players, its plugins' say, and its shutdown.
 *
 * @param <P> the players
 */
public interface DrainHost<P extends DrainCandidate> {

  /**
   * Returns a snapshot of the connected players.
   *
   * @return the players
   */
  List<P> players();

  /**
   * Returns how many players are connected, logging in included.
   *
   * @return the count
   */
  int playerCount();

  /**
   * Asks the plugins what to do with a player.
   *
   * @param player the player
   * @param reason why the drain asks now
   * @param fallback the decision when no plugin changes it
   * @return completed with the decision; never exceptionally
   */
  CompletableFuture<PlayerDrainEvent.Decision> decide(
      P player, PlayerDrainEvent.Reason reason, PlayerDrainEvent.Decision fallback);

  /**
   * Called on the drain's thread each time the drain enters a phase.
   *
   * @param status the drain's status as it entered the phase
   */
  void phaseChanged(DrainStatus status);

  /**
   * Phase 3: disconnects the players left and stops the proxy. Called once, on the drain's thread,
   * once the drain's future completed. The host may hand the work to a thread of its own: the
   * drain's thread is a daemon, which the JVM does not wait for.
   *
   * @param status the drain's final status
   */
  void shutdown(DrainStatus status);
}
