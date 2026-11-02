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
package dev.warp.api.event.server;

import dev.warp.api.server.RegisteredServer;

import java.util.Objects;

/**
 * Fired after a server was unregistered. Its players keep playing on it; no new player can be sent
 * to it. Not fired when a registration replaces a server's address: {@link ServerRegisteredEvent}
 * then carries the server replaced.
 *
 * <p>The registry's events are delivered one after another, in the order the registry changed.
 *
 * @param server the server that was unregistered
 */
public record ServerUnregisteredEvent(RegisteredServer server) {

  /**
   * Checks the server.
   *
   * @param server the server that was unregistered
   */
  public ServerUnregisteredEvent {
    Objects.requireNonNull(server, "server");
  }
}
