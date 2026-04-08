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

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Describes a backend server known to the proxy.
 *
 * <p>Each server has a unique name used for identification in commands and configuration, and an
 * address pointing to the backend Minecraft server.
 *
 * @param name unique server name (e.g. "lobby", "survival")
 * @param address the server's network address and port
 */
public record ServerInfo(String name, InetSocketAddress address) {

  /** Validates that name and address are non-null and name is not blank. */
  public ServerInfo {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(address, "address");
    if (name.isBlank()) {
      throw new IllegalArgumentException("Server name must not be blank");
    }
  }
}
