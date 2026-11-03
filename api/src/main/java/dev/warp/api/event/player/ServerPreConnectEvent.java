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

import dev.warp.api.event.ResultedEvent;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;

import java.util.Optional;

/**
 * Fired before the proxy connects a player to a server: its first server, a switch, or a fallback.
 * Handlers may send the player elsewhere or deny the connection; on a switch, a denied player stays
 * where it is.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface ServerPreConnectEvent extends ResultedEvent<ServerPreConnectEvent.Result> {

  /**
   * Returns the player.
   *
   * @return the player
   */
  Player player();

  /**
   * Returns the server the connection was requested to.
   *
   * @return the original target
   */
  RegisteredServer originalServer();

  /**
   * Returns the server the player plays on.
   *
   * @return the current server, or empty on the player's first connection
   */
  Optional<RegisteredServer> previousServer();

  /** Where the connection goes. The default allows the original server. */
  sealed interface Result {}

  /**
   * Connect the player to a server.
   *
   * @param server the server, the original one or another
   */
  record Allowed(RegisteredServer server) implements Result {}

  /** Do not connect the player. On its first connection, the player is disconnected. */
  record Denied() implements Result {}
}
