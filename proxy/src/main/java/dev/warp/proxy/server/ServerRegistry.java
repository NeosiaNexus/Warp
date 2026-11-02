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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The backend servers known to the proxy, which can change while it runs.
 *
 * <p>The configuration seeds the registry with its static servers; plugins and service discovery
 * register and unregister servers at runtime (a Kubernetes or Agones network starts game servers on
 * demand). Every operation is atomic and lock-free: registering a server that is already registered
 * with the same address is a no-op that returns the existing server, so two discovery paths racing
 * on the same server (a pub/sub event and a periodic poll) cannot fail each other.
 *
 * <p>Server names are case-insensitive (stored and looked up in lowercase). The default server and
 * the fallback order are kept as names and resolved on each use, so they follow servers that come
 * and go.
 */
public final class ServerRegistry {

  private final Map<String, BackendServer> servers = new ConcurrentHashMap<>();
  private final @Nullable String defaultServerName;
  private final List<String> fallbackOrder;

  /**
   * Creates a registry holding the given static servers.
   *
   * @param servers the static servers, by name (may be empty: servers are then registered at
   *     runtime)
   * @param defaultServerName the server players join first, or {@code null} for none
   * @param fallbackServerNames the servers tried in order when a player loses its server
   * @throws IllegalArgumentException if the default server is not one of {@code servers}
   */
  public ServerRegistry(
      Map<String, InetSocketAddress> servers,
      @Nullable String defaultServerName,
      List<String> fallbackServerNames) {
    for (var entry : servers.entrySet()) {
      register(new ServerInfo(normalize(entry.getKey()), entry.getValue()));
    }

    this.defaultServerName = defaultServerName == null ? null : normalize(defaultServerName);
    if (this.defaultServerName != null && !this.servers.containsKey(this.defaultServerName)) {
      throw new IllegalArgumentException(
          "Default server '"
              + defaultServerName
              + "' not found in server list: "
              + servers.keySet());
    }

    List<String> fallback = new ArrayList<>(fallbackServerNames.size());
    for (String name : fallbackServerNames) {
      fallback.add(normalize(name));
    }
    this.fallbackOrder = List.copyOf(fallback);
  }

  // ---------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------

  /**
   * Registers a server, or returns the one already registered under its name at the same address.
   *
   * @param info the server; its name is stored in lowercase
   * @return the registered server
   * @throws IllegalArgumentException if another address is registered under the same name
   */
  public BackendServer register(ServerInfo info) {
    ServerInfo normalized =
        info.name().equals(normalize(info.name()))
            ? info
            : new ServerInfo(normalize(info.name()), info.address());
    BackendServer server =
        servers.computeIfAbsent(normalized.name(), _ -> new BackendServer(normalized));
    if (!server.address().equals(normalized.address())) {
      throw new IllegalArgumentException(
          "Server '"
              + normalized.name()
              + "' is already registered at "
              + server.address()
              + ", not "
              + normalized.address());
    }
    return server;
  }

  /**
   * Unregisters a server. Its players keep playing on it; new connections can no longer reach it
   * through the registry.
   *
   * @param name the server's name, in any case
   * @return the server that was registered, or {@code null} if none was
   */
  public @Nullable BackendServer unregister(String name) {
    return servers.remove(normalize(name));
  }

  /**
   * Unregisters a server only if it is still the given instance, so that a stale unregistration
   * cannot remove a server registered again since.
   *
   * @param server the server to unregister
   * @return {@code true} if it was registered and is no longer
   */
  public boolean unregister(BackendServer server) {
    return servers.remove(server.name(), server);
  }

  // ---------------------------------------------------------------------------
  // Lookup
  // ---------------------------------------------------------------------------

  /**
   * Returns the server registered under a name.
   *
   * @param name the name, in any case
   * @return the server, or {@code null} if none is registered under that name
   */
  public @Nullable BackendServer getServer(String name) {
    return servers.get(normalize(name));
  }

  /**
   * Returns the registered server for a server info, only if it is still registered with that exact
   * name and address.
   *
   * @param info the server info a connection was opened with
   * @return the registered server, or {@code null} if it was unregistered or replaced
   */
  public @Nullable BackendServer registered(ServerInfo info) {
    BackendServer server = servers.get(normalize(info.name()));
    return server != null && server.address().equals(info.address()) ? server : null;
  }

  /**
   * Returns a snapshot of every registered server.
   *
   * @return the servers, unmodifiable, sorted by name
   */
  public List<BackendServer> allServers() {
    List<BackendServer> snapshot = new ArrayList<>(servers.values());
    snapshot.sort((a, b) -> a.name().compareTo(b.name()));
    return List.copyOf(snapshot);
  }

  /**
   * Returns the server a player joining the proxy connects to: the default server if it is
   * registered, else the first registered server of the fallback order.
   *
   * @return the server, or {@code null} if none of them is registered
   */
  public @Nullable BackendServer initialServer() {
    if (defaultServerName != null) {
      BackendServer server = servers.get(defaultServerName);
      if (server != null) {
        return server;
      }
    }
    return nextFallback(Set.of());
  }

  /**
   * Returns the first registered server of the fallback order that is not excluded.
   *
   * @param excludedServers names of the servers to skip (lowercase)
   * @return the server, or {@code null} if every fallback is excluded or unregistered
   */
  public @Nullable BackendServer nextFallback(Set<String> excludedServers) {
    for (String name : fallbackOrder) {
      if (excludedServers.contains(name)) {
        continue;
      }
      BackendServer server = servers.get(name);
      if (server != null) {
        return server;
      }
    }
    return null;
  }

  private static String normalize(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
