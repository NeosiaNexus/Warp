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
package dev.warp.api.server;

import dev.warp.api.player.Player;

/**
 * A player's connection to the backend server it plays on.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface ServerConnection {

  /**
   * Returns the server.
   *
   * @return the server
   */
  RegisteredServer server();

  /**
   * Returns the player.
   *
   * @return the player
   */
  Player player();

  /**
   * Returns whether the connection is still open.
   *
   * @return {@code true} while open
   */
  boolean isActive();
}
