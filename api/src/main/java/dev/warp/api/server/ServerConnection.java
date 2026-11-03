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

import dev.warp.api.messaging.ChannelIdentifier;
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

  /**
   * Sends a plugin message to the server, over this connection.
   *
   * @param channel the channel
   * @param data the payload, copied
   * @return {@code true} if it was sent; {@code false} if the connection is closed or not playing
   */
  boolean sendPluginMessage(ChannelIdentifier channel, byte[] data);
}
