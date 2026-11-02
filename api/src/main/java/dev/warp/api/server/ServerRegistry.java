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
 * <p>Every change is atomic and changes are applied one at a time: two plugins (or a plugin's two
 * discovery paths) registering the same server at the same address both get the same server,
 * without error. Each change fires {@link dev.warp.api.event.server.ServerRegisteredEvent} or
 * {@link dev.warp.api.event.server.ServerUnregisteredEvent}, delivered in the order the changes
 * happened. Lookups never wait for a change.
 *
 * <p>The servers a plugin registers belong to the proxy, not to the plugin: they stay registered
 * when the plugin is disabled, as players may be playing on them and another plugin may have
 * registered them too. A plugin that wants its servers gone unregisters them itself.
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
   * Registers a server under its name: an upsert that reports what it did.
   *
   * <ul>
   *   <li>{@link RegistrationResult.Added}: the name was free.
   *   <li>{@link RegistrationResult.Unchanged}: the name was registered at the same address; the
   *       existing server is kept, and no event fires. Calling this on every discovery refresh is
   *       cheap and safe.
   *   <li>{@link RegistrationResult.Replaced}: the name was registered at another address (a
   *       restarted Kubernetes pod or Agones game server with a new IP). New connections go to the
   *       new address at once; the players on the previous server keep their existing connections,
   *       counted on the previous instance, which is no longer registered. The name is never
   *       unknown in between, unlike unregistering then registering again.
   * </ul>
   *
   * <p>Addresses are compared as {@link java.net.InetSocketAddress#equals} does: a host name the
   * address resolved to another IP counts as another address.
   *
   * @param info the server; its name is stored in lowercase
   * @return what happened, and the server now registered under the name
   */
  RegistrationResult register(ServerInfo info);

  /**
   * Unregisters a server by name. Its players keep playing on it, and it keeps counting them, but
   * no new player can be sent to it.
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
