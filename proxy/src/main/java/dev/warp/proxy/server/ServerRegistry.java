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

import dev.warp.api.event.server.ServerRegisteredEvent;
import dev.warp.api.event.server.ServerUnregisteredEvent;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.RegistrationResult;
import dev.warp.api.server.ServerInfo;
import dev.warp.api.server.ServerPingException;
import dev.warp.proxy.event.WarpEventBus;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.jspecify.annotations.Nullable;

/**
 * The backend servers known to the proxy, which can change while it runs.
 *
 * <p>The configuration seeds the registry with its static servers; plugins and service discovery
 * register and unregister servers at runtime (a Kubernetes or Agones network starts game servers on
 * demand). Registering is an upsert: a server already registered with the same address is kept
 * ({@link RegistrationResult.Unchanged}), so two discovery paths racing on the same server (a
 * pub/sub event and a periodic poll) cannot fail each other; a name registered at another address
 * is replaced ({@link RegistrationResult.Replaced}), new connections going to the new address while
 * the players of the old one stay on their connections.
 *
 * <p>Lookups are lock-free. Changes take a lock, held only to update the map and queue their event
 * on the registry's own lane of the event bus, so that events are delivered in the order of the
 * changes; registrations are rare and never made on a packet path.
 *
 * <p>Server names are case-insensitive (stored and looked up in lowercase). The default server and
 * the fallback order are kept as names and resolved on each use, so they follow servers that come
 * and go.
 */
public final class ServerRegistry implements dev.warp.api.server.ServerRegistry {

  /** How long a ping waits for its answer, unless the caller gives its own time-out. */
  public static final Duration DEFAULT_PING_TIMEOUT = Duration.ofSeconds(5);

  private final Map<String, BackendServer> servers = new ConcurrentHashMap<>();

  /** Serializes changes, so that their events are queued in the order they happened. */
  private final ReentrantLock changes = new ReentrantLock();

  private volatile ServerPinger pinger =
      (server, timeout) ->
          CompletableFuture.failedFuture(
              new ServerPingException(
                  server,
                  ServerPingException.Reason.UNREACHABLE,
                  "the proxy is not started: it cannot ping " + server.name(),
                  null));
  private volatile Duration pingTimeout = DEFAULT_PING_TIMEOUT;
  private volatile @Nullable WarpEventBus events;
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
   * Registers a server, keeps the one already registered under its name at the same address, or
   * replaces the one registered under its name at another address.
   *
   * @param info the server; its name is stored in lowercase
   * @return what happened, and the server now registered under the name
   */
  @Override
  public RegistrationResult register(ServerInfo info) {
    ServerInfo normalized =
        info.name().equals(normalize(info.name()))
            ? info
            : new ServerInfo(normalize(info.name()), info.address());
    changes.lock();
    try {
      BackendServer existing = servers.get(normalized.name());
      if (existing != null && existing.address().equals(normalized.address())) {
        return new RegistrationResult.Unchanged(existing);
      }
      BackendServer server = new BackendServer(normalized, this);
      servers.put(normalized.name(), server);
      notify(new ServerRegisteredEvent(server, existing));
      return existing == null
          ? new RegistrationResult.Added(server)
          : new RegistrationResult.Replaced(server, existing);
    } finally {
      changes.unlock();
    }
  }

  @Override
  public Optional<RegisteredServer> unregister(String name) {
    changes.lock();
    try {
      BackendServer removed = servers.remove(normalize(name));
      if (removed != null) {
        notify(new ServerUnregisteredEvent(removed));
      }
      return Optional.ofNullable(removed);
    } finally {
      changes.unlock();
    }
  }

  @Override
  public boolean unregister(RegisteredServer server) {
    if (!(server instanceof BackendServer backend)) {
      return false;
    }
    changes.lock();
    try {
      boolean removed = servers.remove(backend.name(), backend);
      if (removed) {
        notify(new ServerUnregisteredEvent(backend));
      }
      return removed;
    } finally {
      changes.unlock();
    }
  }

  /** Queues an event on the registry's lane, if plugins can hear it yet. Under {@link #changes}. */
  private void notify(Object event) {
    WarpEventBus bus = events;
    if (bus != null && bus.hasHandlers(event.getClass())) {
      var _ = bus.fire(event, this);
    }
  }

  /**
   * Sets the event bus the registry's changes are announced on, once plugins can listen. The
   * servers registered before (those of the configuration) fire nothing.
   *
   * @param events the event bus
   */
  public void setEventBus(WarpEventBus events) {
    this.events = events;
  }

  // ---------------------------------------------------------------------------
  // Lookup
  // ---------------------------------------------------------------------------

  @Override
  public Optional<RegisteredServer> server(String name) {
    return Optional.ofNullable(getServer(name));
  }

  @Override
  public Collection<RegisteredServer> servers() {
    return List.copyOf(allServers());
  }

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

  /**
   * Returns how servers are pinged.
   *
   * @return the pinger
   */
  ServerPinger pinger() {
    return pinger;
  }

  /**
   * Returns how long a ping waits when its caller gives no time-out.
   *
   * @return the default ping time-out
   */
  Duration pingTimeout() {
    return pingTimeout;
  }

  /**
   * Sets how servers are pinged, once the proxy can open connections.
   *
   * @param pinger the pinger
   */
  public void setPinger(ServerPinger pinger) {
    this.pinger = pinger;
  }

  /**
   * Sets how long {@link RegisteredServer#ping()} waits for an answer.
   *
   * @param timeout the default time-out, positive
   * @throws IllegalArgumentException if {@code timeout} is zero or negative
   */
  public void setPingTimeout(Duration timeout) {
    this.pingTimeout = BackendServer.checkTimeout(timeout);
  }

  private static String normalize(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
