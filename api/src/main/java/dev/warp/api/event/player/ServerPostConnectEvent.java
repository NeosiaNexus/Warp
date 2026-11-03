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
package dev.warp.api.event.player;

import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Fired once the player plays on a server: it is in the world, and messages, titles or a transfer
 * sent now reach it. Fired on the first connection too.
 *
 * @param player the player
 * @param server the server it now plays on
 * @param previous the server it left, or {@code null} on its first connection
 */
public record ServerPostConnectEvent(
    Player player, RegisteredServer server, @Nullable RegisteredServer previous) {

  /**
   * Returns the server the player left.
   *
   * @return it, or empty on the player's first connection
   */
  public Optional<RegisteredServer> previousServer() {
    return Optional.ofNullable(previous);
  }

  /**
   * Returns whether this is the player's first server since it logged in.
   *
   * @return {@code true} on the first connection
   */
  public boolean isInitialJoin() {
    return previous == null;
  }
}
