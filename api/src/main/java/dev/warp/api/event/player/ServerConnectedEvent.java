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
 * Fired when a server accepted the player (its login succeeded there), before the player plays on
 * it. The client may still be loading: send nothing to it yet, wait for {@link
 * ServerPostConnectEvent}.
 *
 * @param player the player
 * @param server the server that accepted it
 * @param previous the server it plays on until the switch completes, or {@code null} on its first
 *     connection
 */
public record ServerConnectedEvent(
    Player player, RegisteredServer server, @Nullable RegisteredServer previous) {

  /**
   * Returns the server the player is leaving.
   *
   * @return it, or empty on the player's first connection
   */
  public Optional<RegisteredServer> previousServer() {
    return Optional.ofNullable(previous);
  }
}
