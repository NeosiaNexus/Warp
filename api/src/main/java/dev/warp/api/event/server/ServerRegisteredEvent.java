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
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Fired after a server was registered under a name that was free, or under a name registered at
 * another address (a replacement). Not fired when a registration changed nothing, nor for the
 * servers of {@code warp.conf}, registered before any plugin loads.
 *
 * <p>The registry's events are delivered one after another, in the order the registry changed; a
 * handler may read the registry, which may have changed again since.
 *
 * @param server the server now registered
 * @param previous the server it replaced, at the old address, or {@code null} when the name was
 *     free; its players stay on it until they leave or move
 */
public record ServerRegisteredEvent(RegisteredServer server, @Nullable RegisteredServer previous) {

  /**
   * Checks the server.
   *
   * @param server the server now registered
   * @param previous the server it replaced, or {@code null} when the name was free
   */
  public ServerRegisteredEvent {
    Objects.requireNonNull(server, "server");
  }

  /**
   * Returns the server this registration replaced.
   *
   * @return the previous server, or empty when the name was free
   */
  public Optional<RegisteredServer> replacedServer() {
    return Optional.ofNullable(previous);
  }
}
