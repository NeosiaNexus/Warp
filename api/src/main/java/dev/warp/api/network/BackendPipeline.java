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
package dev.warp.api.network;

import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;

/**
 * The pipeline of a connection the proxy opens to a backend server for a player, set up before it
 * connects: the first server, a switch and a fallback alike. Nothing has been sent yet: the
 * handshake the proxy sends first goes through the handlers added here.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface BackendPipeline extends ConnectionPipeline {

  /**
   * Returns the player the connection is for.
   *
   * @return the player
   */
  Player player();

  /**
   * Returns the server the connection leads to.
   *
   * @return the server
   */
  RegisteredServer server();
}
