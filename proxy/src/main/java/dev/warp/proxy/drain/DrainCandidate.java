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

import dev.warp.api.player.TransferResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.kyori.adventure.text.Component;

/** A player as the drain sees it: what it can do with one. Thread-safe. */
public interface DrainCandidate {

  /**
   * Returns an identifier of the player's session, distinct for every login.
   *
   * @return the session identifier
   */
  UUID sessionId();

  /**
   * Returns the player's name, for logs.
   *
   * @return the name
   */
  String username();

  /**
   * Returns whether the player is still connected.
   *
   * @return {@code true} while connected
   */
  boolean isActive();

  /**
   * Returns whether the client can be transferred: Minecraft 1.20.5 and newer.
   *
   * @return {@code true} if the client supports transfers
   */
  boolean supportsTransfers();

  /**
   * Transfers the player, through its own event loop.
   *
   * @param host the host
   * @param port the port
   * @return completed with the outcome; never exceptionally
   */
  CompletableFuture<TransferResult> transfer(String host, int port);

  /**
   * Disconnects the player, through its own event loop.
   *
   * @param reason shown to the player
   */
  void kick(Component reason);
}
