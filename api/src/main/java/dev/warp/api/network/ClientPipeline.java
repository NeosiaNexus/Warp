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

import dev.warp.api.player.InboundConnection;

/**
 * The pipeline of a client connection that starts a login, set up once its handshake was read: the
 * protocol version and the host name it connected to are known, the login has not started.
 *
 * <p>Server list pings never reach initializers: they are answered by the proxy alone.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface ClientPipeline extends ConnectionPipeline {

  /**
   * Returns the connection, which becomes a {@link dev.warp.api.player.Player} if its login
   * succeeds.
   *
   * @return the connection
   */
  InboundConnection connection();
}
