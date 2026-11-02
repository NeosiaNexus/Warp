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
 * Fired after {@link PostLoginEvent} to choose the first server a player joins. The proxy proposes
 * its default server (or the first registered server of its fallback order); handlers may pick
 * another. With no server left, the player is disconnected.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface PlayerChooseInitialServerEvent {

  /**
   * Returns the player.
   *
   * @return the player
   */
  Player player();

  /**
   * Returns the server the player will join.
   *
   * @return the server, or empty if none is available (the player will be disconnected)
   */
  Optional<RegisteredServer> initialServer();

  /**
   * Chooses the server the player joins.
   *
   * @param server the server, or {@code null} to disconnect the player
   */
  void setInitialServer(@Nullable RegisteredServer server);
}
