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
import dev.warp.api.server.Route;
import dev.warp.api.server.ServerInfo;
import dev.warp.api.server.ServerPingException;
import dev.warp.proxy.connection.LogThrottle;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.logging.LogCode;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger logger = LoggerFactory.getLogger(ServerRegistry.class);

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
  private final @Nullable String defaultTarget;
  private final List<String> fallbackOrder;
  private final ServerHealth health;
  private final ServerGroupRegistry groups;
  private volatile HostRoutes routes = HostRoutes.EMPTY;
  private final LogThrottle unknownTargetLog = new LogThrottle(Duration.ofMinutes(1));
  private final LogThrottle groupExhaustedLog = new LogThrottle(Duration.ofMinutes(1));

  /**
   * Creates a registry holding the given static servers, with the default cool-down for the
   * servers' circuit breakers.
   *
   * @param servers the static servers, by name (may be empty: servers are then registered at
   *     runtime)
   * @param defaultTarget the server or group players join first, or {@code null} for none
   * @param fallbackOrder the servers or groups tried in order when a player loses its server
   */
  public ServerRegistry(
      Map<String, InetSocketAddress> servers,
      @Nullable String defaultTarget,
      List<String> fallbackOrder) {
    this(servers, defaultTarget, fallbackOrder, new ServerHealth());
  }

  /**
   * Creates a registry holding the given static servers.
   *
   * <p>The default and the fallbacks name servers or groups; the configuration checked that they
   * exist, and a name that is neither at the time a player needs it is skipped.
   *
   * @param servers the static servers, by name (may be empty: servers are then registered at
   *     runtime)
   * @param defaultTarget the server or group players join first, or {@code null} for none
   * @param fallbackOrder the servers or groups tried in order when a player loses its server
   * @param health the servers' health, fed by their pings and the players' connection attempts
   */
  public ServerRegistry(
      Map<String, InetSocketAddress> servers,
      @Nullable String defaultTarget,
      List<String> fallbackOrder,
      ServerHealth health) {
    this.health = health;
    this.groups = new ServerGroupRegistry(this);
    for (var entry : servers.entrySet()) {
      register(new ServerInfo(normalize(entry.getKey()), entry.getValue()));
    }
    this.defaultTarget = defaultTarget == null ? null : normalize(defaultTarget);
    List<String> fallback = new ArrayList<>(fallbackOrder.size());
    for (String name : fallbackOrder) {
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
        health.forget(removed.name());
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
        health.forget(backend.name());
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

  // ---------------------------------------------------------------------------
  // Where players go
  // ---------------------------------------------------------------------------

  /**
   * Returns the server groups, and the servers' health they balance over.
   *
   * @return the group registry
   */
  public ServerGroupRegistry groups() {
    return groups;
  }

  /**
   * Returns the servers' health: their circuit breakers and ping round trips.
   *
   * @return the health
   */
  public ServerHealth health() {
    return health;
  }

  /**
   * Returns the routes of the configuration, matched against each client's virtual host.
   *
   * @return the routes
   */
  public HostRoutes routes() {
    return routes;
  }

  /**
   * Sets the routes of the configuration.
   *
   * @param routes the routes
   */
  public void setRoutes(HostRoutes routes) {
    this.routes = routes;
  }

  /**
   * Returns the name of the server or group players join first, as configured.
   *
   * @return the name, or {@code null} for none
   */
  public @Nullable String defaultTarget() {
    return defaultTarget;
  }

  /**
   * Returns the names of the servers or groups tried in order when a player loses its server.
   *
   * @return the names, lowercase, unmodifiable
   */
  public List<String> fallbackOrder() {
    return fallbackOrder;
  }

  /**
   * Returns the server a player joining the proxy connects to, when no route matched: the default
   * server or group, else the fallback order, preferring the servers whose circuit breaker is
   * closed, and taking any registered one when none is.
   *
   * @return the server, or {@code null} if none of them is registered
   */
  public @Nullable BackendServer initialServer() {
    return initialServer(null);
  }

  /**
   * Returns the server a player joining the proxy connects to.
   *
   * <p>A matched route decides first: its target server or group, healthy or, failing that, any
   * registered one. A route whose target is neither a server nor a group (a capture that named
   * nothing registered) is logged and ignored. Then the default and the fallback order, as {@link
   * #initialServer()}.
   *
   * @param route the route the client's host matched, or {@code null} for none
   * @return the server, or {@code null} if none of them is registered
   */
  public @Nullable BackendServer initialServer(@Nullable Route route) {
    if (route != null) {
      BackendServer routed = resolve(route.target(), Set.of(), true);
      if (routed == null) {
        routed = resolve(route.target(), Set.of(), false);
      }
      if (routed != null) {
        return routed;
      }
      if (getServer(route.target()) == null && groups.getGroup(route.target()) == null) {
        long suppressed = unknownTargetLog.tryAcquire();
        if (suppressed >= 0) {
          logger.warn(
              LogCode.ROUTE_TARGET_UNKNOWN.marker(),
              "Route '{}' sends players to '{}', which is neither a server nor a group: they"
                  + " go to the default instead{}",
              route.host(),
              route.target(),
              LogThrottle.suffix(suppressed, "route"));
        }
      }
    }
    if (defaultTarget != null) {
      BackendServer server = resolve(defaultTarget, Set.of(), true);
      if (server != null) {
        return server;
      }
    }
    BackendServer fallback = nextFallback(Set.of(), true);
    if (fallback != null) {
      return fallback;
    }
    if (defaultTarget != null) {
      BackendServer server = resolve(defaultTarget, Set.of(), false);
      if (server != null) {
        return server;
      }
    }
    return nextFallback(Set.of(), false);
  }

  /**
   * Returns the server a player handed off by another proxy was on, if this proxy has it and its
   * circuit breaker is closed: the player joins it first rather than the default. A group of that
   * name does not count: the player was on one server, not on whichever a balancer would pick.
   *
   * @param name the server's name, as the other proxy calls it
   * @return the server, or {@code null} if it is not registered here, or not healthy
   */
  public @Nullable BackendServer handoffServer(String name) {
    String key = name.toLowerCase(Locale.ROOT);
    return getServer(key) == null ? null : resolve(key, Set.of(), true);
  }

  /**
   * Returns the first server of the fallback order that is registered and not excluded, preferring
   * those whose circuit breaker is closed, and taking any when none is. A group of the fallback
   * order stands for the server its balancer picks.
   *
   * @param excludedServers names of the servers to skip (lowercase)
   * @return the server, or {@code null} if every fallback is excluded or unregistered
   */
  public @Nullable BackendServer nextFallback(Set<String> excludedServers) {
    BackendServer healthy = nextFallback(excludedServers, true);
    return healthy != null ? healthy : nextFallback(excludedServers, false);
  }

  private @Nullable BackendServer nextFallback(Set<String> excludedServers, boolean healthyOnly) {
    for (String name : fallbackOrder) {
      BackendServer server = resolve(name, excludedServers, healthyOnly);
      if (server != null) {
        return server;
      }
    }
    return null;
  }

  /**
   * Resolves a server or group name to a server a player may be sent to.
   *
   * @param name the name, lowercase
   * @param excluded names of the servers to skip, lowercase
   * @param healthyOnly whether to skip the servers whose breaker is open
   * @return the server, or {@code null} if the name resolves to nothing usable
   */
  public @Nullable BackendServer resolve(String name, Set<String> excluded, boolean healthyOnly) {
    BackendServer server = servers.get(name);
    if (server != null) {
      if (excluded.contains(name) || (healthyOnly && !health.allows(name))) {
        return null;
      }
      health.picked(name);
      return server;
    }
    BackendGroup group = groups.getGroup(name);
    if (group == null) {
      return null;
    }
    BackendServer chosen = group.choose(excluded, healthyOnly);
    if (chosen == null && !healthyOnly) {
      long suppressed = groupExhaustedLog.tryAcquire();
      if (suppressed >= 0) {
        logger.warn(
            LogCode.GROUP_EXHAUSTED.marker(),
            "Group '{}' has no server to send a player to{}",
            name,
            LogThrottle.suffix(suppressed, "group"));
      }
    }
    return chosen;
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
