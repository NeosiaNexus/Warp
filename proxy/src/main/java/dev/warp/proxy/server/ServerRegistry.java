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
package dev.warp.proxy.server;

import dev.warp.api.server.ServerInfo;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Immutable registry of backend servers known to the proxy.
 *
 * <p>Server names are case-insensitive (stored and looked up in lowercase). The registry is built
 * once at startup from the configuration and shared across all connections.
 */
public final class ServerRegistry {

  private final Map<String, ServerInfo> servers;
  private final ServerInfo defaultServer;

  /**
   * Creates a registry from a map of server name to address and a default server name.
   *
   * @param servers map of server name to address (at least one entry required)
   * @param defaultServerName the name of the default server (must exist in the map)
   * @throws IllegalArgumentException if the map is empty or the default server is not found
   */
  public ServerRegistry(Map<String, InetSocketAddress> servers, String defaultServerName) {
    if (servers.isEmpty()) {
      throw new IllegalArgumentException("At least one server must be configured");
    }

    Map<String, ServerInfo> registry = new LinkedHashMap<>();
    for (var entry : servers.entrySet()) {
      String key = entry.getKey().toLowerCase(Locale.ROOT);
      registry.put(key, new ServerInfo(key, entry.getValue()));
    }
    this.servers = Map.copyOf(registry);

    String defaultKey = defaultServerName.toLowerCase(Locale.ROOT);
    @Nullable ServerInfo resolved = this.servers.get(defaultKey);
    if (resolved == null) {
      throw new IllegalArgumentException(
          "Default server '"
              + defaultServerName
              + "' not found in server list: "
              + servers.keySet());
    }
    this.defaultServer = resolved;
  }

  /**
   * Returns the server with the given name, or {@code null} if not found.
   *
   * @param name the server name (case-insensitive)
   * @return the server info, or {@code null}
   */
  public @Nullable ServerInfo getServer(String name) {
    return servers.get(name.toLowerCase(Locale.ROOT));
  }

  /**
   * Returns the default server that players connect to on join.
   *
   * @return the default server
   */
  public ServerInfo defaultServer() {
    return defaultServer;
  }

  /**
   * Returns all registered servers.
   *
   * @return unmodifiable collection of all servers
   */
  public Collection<ServerInfo> allServers() {
    return servers.values();
  }
}
