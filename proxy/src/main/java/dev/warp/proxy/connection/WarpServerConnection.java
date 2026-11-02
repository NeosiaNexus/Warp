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

import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerConnection;
import dev.warp.proxy.server.BackendServer;

/**
 * A player's connection to the server it plays on, as plugins see it.
 *
 * @param owner the player
 * @param backend the server
 */
record WarpServerConnection(ConnectedPlayer owner, BackendServer backend)
    implements ServerConnection {

  @Override
  public RegisteredServer server() {
    return backend;
  }

  @Override
  public Player player() {
    return owner.api();
  }

  @Override
  public boolean isActive() {
    BackendConnection connection = owner.backendConnection();
    return owner.currentServer() == backend && connection != null && connection.isActive();
  }
}
