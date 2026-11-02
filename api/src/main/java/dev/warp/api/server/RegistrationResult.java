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

import java.util.Objects;

/**
 * What {@link ServerRegistry#register(ServerInfo)} did with a server: added it, found it already
 * registered as it is, or replaced the address registered under its name.
 *
 * <p>Whatever happened, {@link #server()} is the server registered under the name once the call
 * returns, so a discovery loop can call {@code register} on every refresh and act only on what
 * changed.
 */
public sealed interface RegistrationResult {

  /**
   * Returns the server registered under the name once the call returned.
   *
   * @return the registered server
   */
  RegisteredServer server();

  /**
   * The name was free: the server is now registered.
   *
   * @param server the new server
   */
  record Added(RegisteredServer server) implements RegistrationResult {

    /**
     * Checks the server.
     *
     * @param server the new server
     */
    public Added {
      Objects.requireNonNull(server, "server");
    }
  }

  /**
   * The same name was already registered at the same address: nothing changed, and no event fired.
   *
   * @param server the server already registered, the same instance as before the call
   */
  record Unchanged(RegisteredServer server) implements RegistrationResult {

    /**
     * Checks the server.
     *
     * @param server the server already registered
     */
    public Unchanged {
      Objects.requireNonNull(server, "server");
    }
  }

  /**
   * The name was registered at another address, as when a Kubernetes pod comes back with a new IP:
   * new connections go to the new address. The players on the previous server stay on their
   * existing connections, counted on {@code previous}, which is no longer registered.
   *
   * @param server the new server, at the new address
   * @param previous the server it replaced, at the old address
   */
  record Replaced(RegisteredServer server, RegisteredServer previous)
      implements RegistrationResult {

    /**
     * Checks both servers.
     *
     * @param server the new server, at the new address
     * @param previous the server it replaced, at the old address
     */
    public Replaced {
      Objects.requireNonNull(server, "server");
      Objects.requireNonNull(previous, "previous");
    }
  }
}
