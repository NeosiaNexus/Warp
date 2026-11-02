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

import java.util.Collection;
import java.util.Optional;

/**
 * The backend servers known to the proxy, which plugins can change while it runs.
 *
 * <p>Every operation is atomic: two plugins (or a plugin's two discovery paths) registering the
 * same server at the same address both get the same server, without error.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface ServerRegistry {

  /**
   * Returns a server by name.
   *
   * @param name the name, in any case
   * @return the server, or empty if none is registered under that name
   */
  Optional<RegisteredServer> server(String name);

  /**
   * Returns a snapshot of every registered server, sorted by name.
   *
   * @return the servers, unmodifiable
   */
  Collection<RegisteredServer> servers();

  /**
   * Registers a server, or returns the one already registered under its name at the same address.
   *
   * @param info the server; its name is stored in lowercase
   * @return the registered server
   * @throws IllegalArgumentException if another address is registered under that name
   */
  RegisteredServer register(ServerInfo info);

  /**
   * Unregisters a server by name. Its players keep playing on it.
   *
   * @param name the name, in any case
   * @return the server that was registered, or empty if none was
   */
  Optional<RegisteredServer> unregister(String name);

  /**
   * Unregisters a server only if it is still registered as this instance, so that a stale call
   * cannot remove a server registered again since.
   *
   * @param server the server
   * @return {@code true} if it was unregistered
   */
  boolean unregister(RegisteredServer server);
}
