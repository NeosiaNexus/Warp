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
package dev.warp.proxy.fleet;

import dev.warp.api.event.Subscription;
import dev.warp.api.event.fleet.FleetNodeJoinEvent;
import dev.warp.api.event.fleet.FleetNodeLeaveEvent;
import dev.warp.api.event.fleet.FleetPlayerJoinEvent;
import dev.warp.api.event.fleet.FleetPlayerLeaveEvent;
import dev.warp.api.event.fleet.FleetPlayerServerChangeEvent;
import dev.warp.api.event.fleet.FleetStoreStateEvent;
import dev.warp.api.event.player.DisconnectEvent;
import dev.warp.api.event.player.PostLoginEvent;
import dev.warp.api.event.player.ServerConnectedEvent;
import dev.warp.api.event.proxy.ProxyDrainEvent;
import dev.warp.api.fleet.Fleet;
import dev.warp.api.fleet.FleetBus;
import dev.warp.api.fleet.FleetMessage;
import dev.warp.api.fleet.FleetPlayer;
import dev.warp.api.fleet.NodeState;
import dev.warp.api.fleet.ProxyNode;
import dev.warp.api.fleet.SendResult;
import dev.warp.api.fleet.StoreState;
import dev.warp.api.fleet.spi.ClaimResult;
import dev.warp.api.fleet.spi.FleetStore;
import dev.warp.api.fleet.spi.FleetStoreContext;
import dev.warp.api.fleet.spi.NodeRecord;
import dev.warp.api.fleet.spi.PlayerRecord;
import dev.warp.api.metrics.Metrics;
import dev.warp.api.player.ConnectionResult;
import dev.warp.proxy.config.ConfigurationException;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.config.DuplicateLoginPolicy;
import dev.warp.proxy.config.FleetConfig;
import dev.warp.proxy.connection.ConnectedPlayer;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.i18n.Message;
import dev.warp.proxy.logging.LogCode;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.server.BackendServer;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This proxy's place in the fleet: its node id and incarnation, its heartbeat, the replica of the
 * other proxies and their players, the presence of its own players in the store, the duplicate
 * login policy across proxies, and the bus.
 *
 * <p>Threads: everything the store does is asynchronous; the heartbeat runs on a virtual thread of
 * its own, the refresh and reconcile loop on another, store listeners and the bus on virtual
 * threads from {@link #executor}. The API answers from the replica without I/O, so a connection's
 * event loop never waits. The replica is changed under {@link #lock}, never on an event loop.
 */
public final class FleetService implements Fleet, AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(FleetService.class);
  private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

  /** How long a store call may take before the fleet gives up on it, outside of claims. */
  private static final Duration STORE_TIMEOUT = Duration.ofSeconds(5);

  /** How long a request to another proxy on the fleet's own channels may take. */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /** How long a newcomer waits for the owner to let go before trying again. */
  private static final long KICK_RETRY_MILLIS = 100;

  // The proxy's own channels (prefixed with FleetBusImpl.RESERVED, so plugins cannot take them).
  static final String CHANNEL_DUPLICATE = "warp:duplicate";
  static final String CHANNEL_SEND = "warp:send";
  static final String CHANNEL_KICK = "warp:kick";
  static final String CHANNEL_MESSAGE = "warp:message";

  // Notice kinds on the players and nodes channels.
  private static final String PLAYER_JOIN = "join";
  private static final String PLAYER_LEAVE = "leave";
  private static final String PLAYER_MOVE = "move";
  private static final String NODE_JOIN = "join";
  private static final String NODE_GOODBYE = "goodbye";
  private static final String NODE_REAPED = "reaped";
  private static final String NODE_DRAIN = "drain";

  /** The outcome of {@link #claim}: whether the login goes on. */
  public sealed interface Claim {
    /** The player is this proxy's. */
    record Allowed() implements Claim {}

    /**
     * The player stays where it is.
     *
     * @param message what the newcomer is told
     */
    record Refused(Message message) implements Claim {}
  }

  private final FleetConfig config;
  private final FleetStore store;
  private final PlayerRegistry players;
  private final ServerRegistry servers;
  private final WarpEventBus events;
  private final PluginEventBus own;
  private final DuplicateLoginPolicy policy;
  private final boolean onlineMode;
  private final String version;
  private final BooleanSupplier draining;
  private final FleetMetrics metrics;
  private final String nodeId;
  private final @Nullable String publicAddress;
  private final Instant started = Instant.now();
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("warp-fleet-", 0).factory());
  private final FleetBusImpl bus;
  private final Object lock = new Object();

  /** This node's incarnation, 0 until the store handed one out. */
  private volatile long incarnation;

  /** The other nodes, by id, as last seen. */
  private final Map<String, ProxyNode> remoteNodes = new ConcurrentHashMap<>();

  /** The players of the other nodes. */
  private final Map<UUID, FleetPlayer> remotePlayers = new ConcurrentHashMap<>();

  private final Map<String, FleetPlayer> remoteByName = new ConcurrentHashMap<>();

  /** This node's players, as claimed in the store. */
  private final Map<UUID, PlayerRecord> local = new ConcurrentHashMap<>();

  private final List<Subscription> subscriptions = new ArrayList<>();
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile StoreState state;
  private volatile @Nullable Thread loop;
  private volatile @Nullable Thread refresher;
  private volatile boolean conflicted;

  /**
   * Creates the fleet.
   *
   * @param config the fleet section
   * @param store the store, connected by {@link #start}
   * @param players this proxy's players
   * @param servers this proxy's backends, for {@link #send}
   * @param events the event bus
   * @param metrics the metric registry
   * @param policy what happens when a player logs in twice
   * @param onlineMode whether players are authenticated
   * @param version this proxy's version, for the other nodes
   * @param draining whether this proxy drains, for its heartbeat
   */
  public FleetService(
      FleetConfig config,
      FleetStore store,
      PlayerRegistry players,
      ServerRegistry servers,
      WarpEventBus events,
      Metrics metrics,
      DuplicateLoginPolicy policy,
      boolean onlineMode,
      String version,
      BooleanSupplier draining) {
    this.config = config;
    this.store = store;
    this.players = players;
    this.servers = servers;
    this.events = events;
    this.own = new PluginEventBus(events, "warp", null, subscriptions::add);
    this.policy = policy;
    this.onlineMode = onlineMode;
    this.version = version;
    this.draining = draining;
    this.metrics = new FleetMetrics(metrics);
    this.nodeId = config.nodeId() != null ? config.nodeId() : defaultNodeId();
    this.publicAddress = config.publicAddress();
    this.state = store.state();
    this.bus =
        new FleetBusImpl(
            nodeId,
            this::key,
            store,
            executor,
            id -> id.equals(nodeId) || remoteNodes.containsKey(id),
            this.metrics);
  }

  /**
   * Creates and starts a fleet of this proxy alone, on a {@link SingleNodeStore}: what the proxy
   * runs without a store, and what tests use.
   *
   * @param players this proxy's players
   * @param servers this proxy's backends
   * @param events the event bus
   * @param metrics the metric registry
   * @param policy what happens when a player logs in twice
   * @param onlineMode whether players are authenticated
   * @return the fleet, started
   */
  public static FleetService singleNode(
      PlayerRegistry players,
      ServerRegistry servers,
      WarpEventBus events,
      Metrics metrics,
      DuplicateLoginPolicy policy,
      boolean onlineMode) {
    FleetService fleet =
        new FleetService(
            FleetConfig.DEFAULTS,
            new SingleNodeStore(),
            players,
            servers,
            events,
            metrics,
            policy,
            onlineMode,
            "test",
            () -> false);
    fleet.start().join();
    return fleet;
  }

  /**
   * Returns the node id used when {@code fleet.node-id} is empty: the host name, which is the pod
   * name on Kubernetes.
   *
   * @return the host name, or {@code warp} when it cannot be read
   */
  public static String defaultNodeId() {
    String fromEnv = System.getenv("HOSTNAME");
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.trim();
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "warp";
    }
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Subscribes to the notices, connects the store, takes an incarnation, reaps this node's previous
   * lives, writes the first heartbeat and starts the heartbeat and refresh loops. An unreachable
   * store leaves the fleet degraded, to resync when the store is back.
   *
   * @return completed once the first heartbeat is written, or once the store is known to be down
   */
  public CompletableFuture<Void> start() {
    if (!running.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(null);
    }
    store.onStateChange(this::onStoreState);
    metrics.storeUp(true);
    var _ = own.subscribe(PostLoginEvent.class, this::onPostLogin);
    var _ = own.subscribe(ServerConnectedEvent.class, this::onServerConnected);
    var _ = own.subscribe(DisconnectEvent.class, this::onDisconnect);
    var _ = own.subscribe(ProxyDrainEvent.class, _ -> announceDrain());
    bus.respondInternal(CHANNEL_DUPLICATE, this::answerDuplicate);
    bus.respondInternal(CHANNEL_SEND, this::answerSend);
    bus.respondInternal(CHANNEL_KICK, this::answerKick);
    bus.respondInternal(CHANNEL_MESSAGE, this::answerMessage);
    CompletableFuture<Void> started = new CompletableFuture<>();
    executor.execute(
        () -> {
          CompletableFuture<Void> connected =
              store.connect(new FleetStoreContext(config.network(), nodeId, executor));
          // Subscribed before the store answers: a store that is down at boot (a Service with no
          // endpoints yet) subscribes when it connects, instead of leaving this node deaf to the
          // notices and its inbox until a restart.
          synchronized (lock) {
            subscriptions.add(store.subscribe(key("players"), this::onPlayerNotice));
            subscriptions.add(store.subscribe(key("nodes"), this::onNodeNotice));
            subscriptions.add(bus.openInbox());
          }
          try {
            connected.get(STORE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (store.state() != StoreState.DEGRADED) {
              resync();
            } else {
              degraded();
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (ExecutionException | TimeoutException | RuntimeException e) {
            logger.warn(
                LogCode.FLEET_STORE_UNAVAILABLE.marker(),
                "The fleet store is unreachable, this proxy runs alone until it is back: {}",
                cause(e));
            degraded();
          }
          Thread beat =
              Thread.ofVirtual().name("warp-fleet-heartbeat").unstarted(this::heartbeatLoop);
          Thread refresh =
              Thread.ofVirtual().name("warp-fleet-refresh").unstarted(this::refreshLoop);
          this.loop = beat;
          this.refresher = refresh;
          beat.start();
          refresh.start();
          started.complete(null);
        });
    return started;
  }

  /**
   * Leaves the fleet: says goodbye, reaps this node's records, stops the loop and closes the store.
   * The players are still connected when this runs; they disconnect with the proxy, and their
   * records go with the node.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    running.set(false);
    for (Thread thread : new Thread[] {loop, refresher}) {
      if (thread != null) {
        thread.interrupt();
      }
    }
    long inc = incarnation;
    if (inc != 0 && state != StoreState.DEGRADED) {
      try {
        publishNode(NODE_GOODBYE);
        timed("reap", store.reap(nodeId, inc)).get(STORE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException | TimeoutException | RuntimeException e) {
        logger.warn(
            LogCode.FLEET_STORE_UNAVAILABLE.marker(),
            "Could not say goodbye to the fleet, the other proxies reap this node after {}: {}",
            config.heartbeatTtl(),
            cause(e));
      }
    }
    synchronized (lock) {
      for (Subscription subscription : subscriptions) {
        subscription.unsubscribe();
      }
      subscriptions.clear();
    }
    store.close();
    executor.shutdownNow();
  }

  /**
   * The heartbeat loop, on a virtual thread of its own: one heartbeat every interval and nothing
   * else, so that a slow store never delays it past {@code heartbeat-ttl} while the refresh loop
   * reads the other nodes. The peers reap a node whose heartbeat is late, players and all.
   */
  private void heartbeatLoop() {
    while (running.get() && !conflicted) {
      if (!pause()) {
        return;
      }
      try {
        heartbeat();
      } catch (RuntimeException e) {
        logger.warn(
            LogCode.FLEET_STORE_UNAVAILABLE.marker(), "Fleet heartbeat failed: {}", cause(e));
      }
    }
  }

  /** The refresh loop, on a virtual thread: the nodes every interval, the players every n. */
  private void refreshLoop() {
    long every = Math.max(1, config.reconcile().toMillis() / config.heartbeat().toMillis());
    long tick = 0;
    while (running.get() && !conflicted) {
      if (!pause()) {
        return;
      }
      tick++;
      try {
        refreshNodes();
        if (tick % every == 0) {
          reconcilePlayers();
        }
      } catch (RuntimeException e) {
        logger.warn(LogCode.FLEET_STORE_UNAVAILABLE.marker(), "Fleet refresh failed: {}", cause(e));
      }
    }
  }

  /**
   * Sleeps one heartbeat interval, then says whether the loop's work is due: not while stopping,
   * degraded or interrupted.
   *
   * @return {@code false} when the loop must end
   */
  private boolean pause() {
    while (true) {
      try {
        Thread.sleep(config.heartbeat());
      } catch (InterruptedException e) {
        return false;
      }
      if (!running.get()) {
        return false;
      }
      if (state != StoreState.DEGRADED) {
        return true;
      }
    }
  }

  private void onStoreState(StoreState now) {
    StoreState before = state;
    if (before == now) {
      return;
    }
    state = now;
    metrics.storeUp(now != StoreState.DEGRADED);
    var _ = events.fire(new FleetStoreStateEvent(before, now));
    if (now == StoreState.CONNECTED) {
      logger.info("The fleet store is back: resynchronising");
      executor.execute(this::resync);
    } else if (now == StoreState.DEGRADED) {
      Throwable failure =
          store instanceof dev.warp.proxy.fleet.valkey.ValkeyFleetStore valkey
              ? valkey.lastFailure()
              : null;
      logger.warn(
          LogCode.FLEET_STORE_UNAVAILABLE.marker(),
          "The fleet store is unreachable{}: the view of the other proxies is frozen, logins"
              + " follow fleet.on-store-down = {}",
          failure == null ? "" : " (" + cause(failure) + ")",
          config.onStoreDown().name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
  }

  private void degraded() {
    onStoreState(StoreState.DEGRADED);
  }

  /**
   * Brings this node up to date with the store: a fresh incarnation if none, this node's previous
   * lives reaped, the first heartbeat, the local players claimed again, the replica rebuilt.
   */
  private void resync() {
    try {
      if (incarnation == 0) {
        long inc = await("incr", store.nextIncarnation(nodeId));
        this.incarnation = inc;
        if (store.state() != StoreState.SINGLE_NODE) {
          logJoined(inc);
        }
      }
      reapPreviousLives();
      heartbeat();
      for (PlayerRecord record : local.values()) {
        PlayerRecord mine = record.incarnation() == incarnation ? record : withIncarnation(record);
        local.put(mine.uuid(), mine);
        replayClaim(mine);
      }
      if (state != StoreState.CONNECTED && store.state() != StoreState.SINGLE_NODE) {
        onStoreState(StoreState.CONNECTED);
      }
      publishNode(NODE_JOIN);
      refreshNodes();
      reconcilePlayers();
    } catch (RuntimeException e) {
      logger.warn(
          LogCode.FLEET_STORE_UNAVAILABLE.marker(),
          "The fleet store did not answer while resynchronising: {}",
          cause(e));
      degraded();
    }
  }

  private void logJoined(long inc) {
    logger.info(
        "Joined fleet network {} as node {} (incarnation {}){}",
        config.network(),
        nodeId,
        inc,
        publicAddress == null
            ? ", no public address for transfers"
            : ", transfers to " + publicAddress);
  }

  private PlayerRecord withIncarnation(PlayerRecord record) {
    return new PlayerRecord(
        record.uuid(), record.name(), nodeId, incarnation, record.server(), record.since());
  }

  /** Reaps the records a previous life of this node id left: a crash, then a restart. */
  private void reapPreviousLives() {
    Set<Long> incarnations = new HashSet<>();
    for (PlayerRecord record : await("players", store.players(nodeId))) {
      if (record.incarnation() != incarnation) {
        incarnations.add(record.incarnation());
      }
    }
    for (NodeRecord node : await("nodes", store.nodes())) {
      if (node.id().equals(nodeId) && node.incarnation() != incarnation) {
        if (node.incarnation() > incarnation) {
          conflict(node);
          return;
        }
        incarnations.add(node.incarnation());
      }
    }
    for (long old : incarnations) {
      int reaped = await("reap", store.reap(nodeId, old));
      metrics.reaped(reaped);
      if (reaped > 0) {
        logger.info(
            "Reaped {} player records of this node's previous life (incarnation {})", reaped, old);
      }
    }
  }

  private void conflict(NodeRecord other) {
    if (conflicted) {
      return;
    }
    conflicted = true;
    logger.error(
        LogCode.FLEET_NODE_ID_CONFLICT.marker(),
        "Another proxy heartbeats as node {} with a newer incarnation ({} against this one's {}):"
            + " two proxies share fleet.node-id. This proxy leaves the fleet; set a distinct node-id",
        nodeId,
        other.incarnation(),
        incarnation);
  }

  // ---------------------------------------------------------------------------
  // Heartbeat and replica
  // ---------------------------------------------------------------------------

  private NodeRecord localRecord() {
    return new NodeRecord(
        nodeId,
        incarnation,
        publicAddress,
        draining.getAsBoolean() ? NodeState.DRAINING : NodeState.READY,
        players.count(),
        version,
        started);
  }

  private void heartbeat() {
    if (incarnation == 0) {
      return;
    }
    await("heartbeat", store.heartbeat(localRecord()));
  }

  /** Reads the live nodes: new ones join, missing ones expired and are reaped. */
  private void refreshNodes() {
    Collection<NodeRecord> live = await("nodes", store.nodes());
    Instant now = Instant.now();
    Map<String, NodeRecord> byId = new HashMap<>();
    for (NodeRecord node : live) {
      if (node.id().equals(nodeId)) {
        if (node.incarnation() > incarnation && incarnation != 0) {
          conflict(node);
        }
        continue;
      }
      byId.put(node.id(), node);
    }
    synchronized (lock) {
      for (NodeRecord node : byId.values()) {
        ProxyNode seen = toNode(node, now);
        ProxyNode before = remoteNodes.put(node.id(), seen);
        if (before == null) {
          metrics.corrected(FleetMetrics.Correction.NODE_ADDED);
          logger.info(
              "Fleet node {} is up (incarnation {}, {} players)",
              node.id(),
              node.incarnation(),
              node.players());
          var _ = events.fire(new FleetNodeJoinEvent(seen));
        } else if (before.incarnation() != seen.incarnation()) {
          // Restarted between two refreshes: its old players are gone.
          dropPlayersOf(node.id());
        }
      }
      for (String id : List.copyOf(remoteNodes.keySet())) {
        ProxyNode gone = remoteNodes.get(id);
        if (!byId.containsKey(id) && gone != null) {
          int reaped = await("reap", store.reap(id, gone.incarnation()));
          metrics.reaped(reaped);
          metrics.corrected(FleetMetrics.Correction.NODE_REMOVED);
          logger.warn(
              LogCode.FLEET_NODE_EXPIRED.marker(),
              "Fleet node {} stopped heartbeating (incarnation {}): reaped, {} player records removed",
              id,
              gone.incarnation(),
              reaped);
          publishNodeAbout(NODE_REAPED, id, gone.incarnation());
          removeNode(id, FleetNodeLeaveEvent.Reason.EXPIRED);
        }
      }
      updateGauges();
    }
  }

  /** Compares the other nodes' players, and this node's own set, with the store. */
  private void reconcilePlayers() {
    for (String id : List.copyOf(remoteNodes.keySet())) {
      ProxyNode node = remoteNodes.get(id);
      if (node == null) {
        continue;
      }
      Collection<PlayerRecord> stored = await("players", store.players(id));
      synchronized (lock) {
        Set<UUID> seen = new HashSet<>();
        for (PlayerRecord record : stored) {
          if (record.incarnation() != node.incarnation()) {
            continue;
          }
          seen.add(record.uuid());
          FleetPlayer current = remotePlayers.get(record.uuid());
          FleetPlayer fresh = toPlayer(record);
          if (current == null) {
            metrics.corrected(FleetMetrics.Correction.PLAYER_ADDED);
            putRemote(fresh);
            var _ = events.fire(new FleetPlayerJoinEvent(fresh));
          } else if (!Objects.equals(current.server(), fresh.server())
              || !current.proxyId().equals(fresh.proxyId())) {
            metrics.corrected(FleetMetrics.Correction.PLAYER_MOVED);
            putRemote(fresh);
            var _ = events.fire(new FleetPlayerServerChangeEvent(fresh, current.server()));
          }
        }
        for (FleetPlayer player : List.copyOf(remotePlayers.values())) {
          if (player.proxyId().equals(id) && !seen.contains(player.uuid())) {
            metrics.corrected(FleetMetrics.Correction.PLAYER_REMOVED);
            removeRemote(player);
            var _ = events.fire(new FleetPlayerLeaveEvent(player));
          }
        }
      }
    }
    // This node's own set: what the store has for us must be what we have.
    if (incarnation == 0) {
      return;
    }
    Collection<PlayerRecord> mine = await("players", store.players(nodeId));
    Set<UUID> stored = new HashSet<>();
    for (PlayerRecord record : mine) {
      if (record.incarnation() != incarnation) {
        continue;
      }
      stored.add(record.uuid());
      if (!local.containsKey(record.uuid())) {
        await("release", store.release(record.uuid(), nodeId, incarnation));
        metrics.corrected(FleetMetrics.Correction.OWN_RELEASED);
      }
    }
    for (PlayerRecord record : local.values()) {
      if (!stored.contains(record.uuid())) {
        replayClaim(record);
      }
    }
    synchronized (lock) {
      updateGauges();
    }
  }

  /**
   * Claims one of this node's players again, after a resync or a record the store lost: a claim
   * another node holds is a duplicate session the store did not prevent, said out loud.
   */
  private void replayClaim(PlayerRecord mine) {
    ClaimResult result = await("claim", store.claim(mine));
    if (result instanceof ClaimResult.Owned(String owner, long inc) && !owner.equals(nodeId)) {
      metrics.corrected(FleetMetrics.Correction.OWN_CONFLICT);
      logger.warn(
          LogCode.FLEET_PRESENCE_CONFLICT.marker(),
          "{} is connected here and claimed by {} (incarnation {}): the store lost a write;"
              + " both stay until one leaves",
          mine.name(),
          owner,
          inc);
    } else {
      metrics.corrected(FleetMetrics.Correction.OWN_REPLAYED);
    }
  }

  private void updateGauges() {
    int ready = draining.getAsBoolean() ? 0 : 1;
    int drainingCount = 1 - ready;
    for (ProxyNode node : remoteNodes.values()) {
      if (node.state() == NodeState.DRAINING) {
        drainingCount++;
      } else {
        ready++;
      }
    }
    metrics.nodes(ready, drainingCount);
    metrics.remotePlayers(remotePlayers.size());
  }

  private void removeNode(String id, FleetNodeLeaveEvent.Reason reason) {
    ProxyNode gone = remoteNodes.remove(id);
    if (gone == null) {
      return;
    }
    dropPlayersOf(id);
    var _ =
        events.fire(
            new FleetNodeLeaveEvent(
                new ProxyNode(
                    gone.id(),
                    gone.incarnation(),
                    gone.publicAddress(),
                    gone.players(),
                    NodeState.DOWN,
                    gone.lastSeen(),
                    gone.version()),
                reason));
  }

  private void dropPlayersOf(String id) {
    for (FleetPlayer player : List.copyOf(remotePlayers.values())) {
      if (player.proxyId().equals(id)) {
        removeRemote(player);
        var _ = events.fire(new FleetPlayerLeaveEvent(player));
      }
    }
  }

  private void putRemote(FleetPlayer player) {
    FleetPlayer before = remotePlayers.put(player.uuid(), player);
    if (before != null && !before.name().equalsIgnoreCase(player.name())) {
      remoteByName.remove(before.name().toLowerCase(Locale.ROOT), before);
    }
    remoteByName.put(player.name().toLowerCase(Locale.ROOT), player);
  }

  private void removeRemote(FleetPlayer player) {
    remotePlayers.remove(player.uuid(), player);
    remoteByName.remove(player.name().toLowerCase(Locale.ROOT), player);
  }

  // ---------------------------------------------------------------------------
  // Presence of this node's players
  // ---------------------------------------------------------------------------

  /**
   * Settles who owns an account across the fleet, once the player is registered here. Called on the
   * connection's event loop; the work runs on a fleet thread and the future completes there.
   *
   * @param player the player, registered on this proxy
   * @return completed with the outcome within {@code fleet.claim-timeout}, never exceptionally
   */
  public CompletableFuture<Claim> claim(ConnectedPlayer player) {
    PlayerRecord record =
        new PlayerRecord(
            player.uuid(),
            player.username(),
            nodeId,
            incarnation,
            player.currentServerName(),
            Instant.now());
    local.put(record.uuid(), record);
    if (store.state() == StoreState.SINGLE_NODE) {
      var _ = store.claim(record); // keeps the single-node store's view whole, for the API
      metrics.claim(FleetMetrics.ClaimOutcome.CLAIMED);
      return CompletableFuture.completedFuture(new Claim.Allowed());
    }
    if (state == StoreState.DEGRADED || incarnation == 0 || conflicted) {
      metrics.claim(FleetMetrics.ClaimOutcome.STORE_DOWN);
      return CompletableFuture.completedFuture(storeDown(record));
    }
    CompletableFuture<Claim> result = new CompletableFuture<>();
    executor.execute(() -> result.complete(claimBlocking(record)));
    return result;
  }

  private Claim storeDown(PlayerRecord record) {
    if (config.onStoreDown() == FleetConfig.OnStoreDown.ALLOW) {
      return new Claim.Allowed();
    }
    local.remove(record.uuid(), record);
    return new Claim.Refused(Message.FLEET_UNAVAILABLE);
  }

  /** The claim, on a fleet thread: it may wait for the owner to let go. */
  private Claim claimBlocking(PlayerRecord record) {
    long deadline = System.nanoTime() + config.claimTimeout().toNanos();
    try {
      boolean kicked = false;
      while (true) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          metrics.claim(FleetMetrics.ClaimOutcome.TIMEOUT);
          local.remove(record.uuid(), record);
          return new Claim.Refused(Message.ALREADY_CONNECTED_ELSEWHERE);
        }
        ClaimResult result = timed("claim", store.claim(record)).get(left, TimeUnit.NANOSECONDS);
        switch (result) {
          case ClaimResult.Claimed() -> {
            metrics.claim(
                kicked
                    ? FleetMetrics.ClaimOutcome.OWNED_KICKED
                    : FleetMetrics.ClaimOutcome.CLAIMED);
            return new Claim.Allowed();
          }
          case ClaimResult.Owned(String owner, long inc) when owner.equals(nodeId) -> {
            // Our own record, of a session this registry already settled: let go and take it.
            await("release", store.release(record.uuid(), owner, inc));
          }
          case ClaimResult.Owned(String owner, long inc) -> {
            if (!policy.kicksExisting(onlineMode)) {
              metrics.claim(FleetMetrics.ClaimOutcome.OWNED_REFUSED);
              local.remove(record.uuid(), record);
              return new Claim.Refused(Message.ALREADY_CONNECTED);
            }
            if (!kicked) {
              kicked = true;
              byte[] request =
                  new FleetCodec.Writer().string(record.uuid().toString()).lng(inc).toBytes();
              try {
                var _ =
                    bus.requestInternal(
                            owner,
                            CHANNEL_DUPLICATE,
                            request,
                            Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))
                        .get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
              } catch (ExecutionException | TimeoutException e) {
                // The owner did not answer: dead but not expired yet, or the request was lost.
                // The loop tries the claim again until the deadline, then refuses.
                logger.debug("{} did not release {}: {}", owner, record.name(), cause(e));
              }
            } else {
              Thread.sleep(Math.min(KICK_RETRY_MILLIS, Math.max(1, left / 1_000_000)));
            }
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      local.remove(record.uuid(), record);
      return new Claim.Refused(Message.FLEET_UNAVAILABLE);
    } catch (ExecutionException | TimeoutException | RuntimeException e) {
      logger.warn(
          LogCode.FLEET_CLAIM_FAILED.marker(),
          "The fleet store did not settle the login of {} in time: {}",
          record.name(),
          cause(e));
      metrics.claim(FleetMetrics.ClaimOutcome.TIMEOUT);
      return storeDown(record);
    }
  }

  /**
   * Gives up a player's record: a login refused after its claim, or a session that ended without a
   * disconnect event. Nothing happens when another session of this proxy holds the UUID now.
   *
   * @param player the player
   */
  public void release(ConnectedPlayer player) {
    if (players.get(player.uuid()) != null && players.get(player.uuid()) != player) {
      return; // a newcomer on this proxy took over the account: its record is the live one
    }
    PlayerRecord record = local.remove(player.uuid());
    if (record == null) {
      return;
    }
    executor.execute(
        () -> {
          try {
            await("release", store.release(record.uuid(), nodeId, record.incarnation()));
            publishPlayer(PLAYER_LEAVE, record, null);
          } catch (RuntimeException e) {
            logger.debug(
                "Release of {} deferred to the next reconcile: {}", record.name(), cause(e));
          }
        });
  }

  private void onPostLogin(PostLoginEvent event) {
    PlayerRecord record = local.get(event.player().uniqueId());
    if (record != null) {
      executor.execute(() -> publishPlayer(PLAYER_JOIN, record, null));
    }
  }

  private void onServerConnected(ServerConnectedEvent event) {
    UUID uuid = event.player().uniqueId();
    String server = event.server().name();
    PlayerRecord before = local.get(uuid);
    if (before == null) {
      return;
    }
    PlayerRecord moved =
        new PlayerRecord(
            before.uuid(), before.name(), nodeId, before.incarnation(), server, before.since());
    local.put(uuid, moved);
    executor.execute(
        () -> {
          try {
            await("move", store.move(uuid, nodeId, moved.incarnation(), server));
            publishPlayer(PLAYER_MOVE, moved, before.server());
          } catch (RuntimeException e) {
            logger.debug("Move of {} deferred to the next reconcile: {}", moved.name(), cause(e));
          }
        });
  }

  private void onDisconnect(DisconnectEvent event) {
    if (event.player() instanceof dev.warp.proxy.connection.WarpPlayer player) {
      release(player.connectedPlayer());
    }
  }

  // ---------------------------------------------------------------------------
  // Notices
  // ---------------------------------------------------------------------------

  private String key(String channel) {
    return "warp:" + config.network() + ":" + channel;
  }

  private void publishPlayer(String kind, PlayerRecord record, @Nullable String previous) {
    byte[] frame =
        new FleetCodec.Writer()
            .string(kind)
            .string(nodeId)
            .lng(record.incarnation())
            .string(record.uuid().toString())
            .string(record.name())
            .string(record.server() == null ? "" : record.server())
            .lng(record.since().toEpochMilli())
            .string(previous == null ? "" : previous)
            .toBytes();
    metrics.busOut();
    var _ = store.publish(key("players"), frame);
  }

  private void publishNode(String kind) {
    publishNodeAbout(kind, nodeId, incarnation);
  }

  private void publishNodeAbout(String kind, String id, long inc) {
    byte[] frame = new FleetCodec.Writer().string(kind).string(id).lng(inc).toBytes();
    metrics.busOut();
    var _ = store.publish(key("nodes"), frame);
  }

  private void onPlayerNotice(byte[] frame) {
    metrics.busIn();
    String kind;
    String node;
    long inc;
    UUID uuid;
    String name;
    String server;
    long since;
    String previous;
    try {
      FleetCodec.Reader reader = new FleetCodec.Reader(frame);
      kind = reader.string();
      node = reader.string();
      inc = reader.lng();
      uuid = UUID.fromString(reader.string());
      name = reader.string();
      server = reader.string();
      since = reader.lng();
      previous = reader.string();
    } catch (IllegalArgumentException e) {
      logger.warn(
          LogCode.FLEET_FRAME_INVALID.marker(),
          "Dropped a malformed player notice: {}",
          e.getMessage());
      return;
    }
    if (node.equals(nodeId)) {
      return;
    }
    FleetPlayer player =
        new FleetPlayer(
            uuid, name, node, server.isEmpty() ? null : server, Instant.ofEpochMilli(since));
    synchronized (lock) {
      ProxyNode owner = remoteNodes.get(node);
      if (owner == null || owner.incarnation() != inc) {
        // A node this proxy has not seen yet, or a stale notice: the next refresh settles it.
        return;
      }
      switch (kind) {
        case PLAYER_JOIN -> {
          putRemote(player);
          var _ = events.fire(new FleetPlayerJoinEvent(player));
        }
        case PLAYER_MOVE -> {
          putRemote(player);
          var _ =
              events.fire(
                  new FleetPlayerServerChangeEvent(player, previous.isEmpty() ? null : previous));
        }
        case PLAYER_LEAVE -> {
          FleetPlayer known = remotePlayers.get(uuid);
          if (known != null && known.proxyId().equals(node)) {
            removeRemote(known);
            var _ = events.fire(new FleetPlayerLeaveEvent(known));
          }
        }
        default -> {}
      }
      metrics.remotePlayers(remotePlayers.size());
    }
  }

  private void onNodeNotice(byte[] frame) {
    metrics.busIn();
    String kind;
    String id;
    long inc;
    try {
      FleetCodec.Reader reader = new FleetCodec.Reader(frame);
      kind = reader.string();
      id = reader.string();
      inc = reader.lng();
    } catch (IllegalArgumentException e) {
      logger.warn(
          LogCode.FLEET_FRAME_INVALID.marker(),
          "Dropped a malformed node notice: {}",
          e.getMessage());
      return;
    }
    if (id.equals(nodeId)) {
      if (inc > incarnation
          && incarnation != 0
          && (kind.equals(NODE_JOIN) || kind.equals(NODE_DRAIN))) {
        conflict(new NodeRecord(id, inc, null, NodeState.READY, 0, "", Instant.now()));
      } else if (kind.equals(NODE_REAPED) && inc == incarnation && incarnation != 0) {
        // A peer saw this heartbeat expire (a store stall, a pause) and dropped this node's
        // player records: back at once, players claimed again, rather than at the next reconcile.
        logger.warn(
            LogCode.FLEET_NODE_EXPIRED.marker(),
            "Another proxy reaped this node (incarnation {}) while it runs: the heartbeat was late,"
                + " resynchronising",
            inc);
        executor.execute(this::resync);
      }
      return;
    }
    switch (kind) {
      case NODE_JOIN -> executor.execute(this::refreshNodesQuietly);
      case NODE_GOODBYE, NODE_REAPED -> {
        synchronized (lock) {
          ProxyNode known = remoteNodes.get(id);
          if (known != null && known.incarnation() == inc) {
            logger.info("Fleet node {} left (incarnation {}, {})", id, inc, kind);
            removeNode(
                id,
                kind.equals(NODE_GOODBYE)
                    ? FleetNodeLeaveEvent.Reason.SHUTDOWN
                    : FleetNodeLeaveEvent.Reason.EXPIRED);
            updateGauges();
          }
        }
      }
      case NODE_DRAIN -> {
        synchronized (lock) {
          ProxyNode known = remoteNodes.get(id);
          if (known != null && known.incarnation() == inc) {
            remoteNodes.put(
                id,
                new ProxyNode(
                    known.id(),
                    known.incarnation(),
                    known.publicAddress(),
                    known.players(),
                    NodeState.DRAINING,
                    known.lastSeen(),
                    known.version()));
            updateGauges();
          }
        }
      }
      default -> {}
    }
  }

  private void refreshNodesQuietly() {
    try {
      refreshNodes();
    } catch (RuntimeException e) {
      logger.debug("Node refresh failed: {}", cause(e));
    }
  }

  /**
   * Picks where a draining proxy sends its next player: the ready proxy of the fleet with the
   * fewest players among those with a public address.
   *
   * @return the target, or {@code null} when no other proxy accepts transfers
   */
  public DrainConfig.@Nullable Target transferTarget() {
    ProxyNode best = null;
    for (ProxyNode node : remoteNodes.values()) {
      if (node.acceptsTransfers() && (best == null || node.players() < best.players())) {
        best = node;
      }
    }
    if (best == null || best.publicAddress() == null) {
      return null;
    }
    try {
      return DrainConfig.parseTarget(best.publicAddress());
    } catch (ConfigurationException e) {
      logger.debug(
          "Fleet node {} has an invalid public address {}", best.id(), best.publicAddress());
      return null;
    }
  }

  /** Tells the fleet this proxy drains: its heartbeat says so from now on. */
  public void announceDrain() {
    if (incarnation == 0 || state == StoreState.DEGRADED) {
      return;
    }
    executor.execute(
        () -> {
          try {
            heartbeat();
            publishNode(NODE_DRAIN);
          } catch (RuntimeException e) {
            logger.debug("Drain notice deferred to the next heartbeat: {}", cause(e));
          }
        });
  }

  // ---------------------------------------------------------------------------
  // Requests this node answers
  // ---------------------------------------------------------------------------

  private CompletableFuture<byte[]> answerDuplicate(FleetMessage request) {
    FleetCodec.Reader reader = new FleetCodec.Reader(request.payload());
    UUID uuid = UUID.fromString(reader.string());
    long inc = reader.lng();
    ConnectedPlayer player = players.get(uuid);
    if (player != null) {
      logger.info(
          "{} logged in on fleet node {}: disconnecting the session here",
          player.username(),
          request.senderProxyId());
      player.disconnectDuplicate();
    }
    PlayerRecord record = local.remove(uuid);
    long owned = record != null ? record.incarnation() : inc;
    return timed("release", store.release(uuid, nodeId, owned)).thenApply(_ -> new byte[] {1});
  }

  private CompletableFuture<byte[]> answerSend(FleetMessage request) {
    FleetCodec.Reader reader = new FleetCodec.Reader(request.payload());
    UUID uuid = UUID.fromString(reader.string());
    String server = reader.string();
    return sendLocal(uuid, server)
        .thenApply(result -> result.name().getBytes(StandardCharsets.UTF_8));
  }

  private CompletableFuture<byte[]> answerKick(FleetMessage request) {
    FleetCodec.Reader reader = new FleetCodec.Reader(request.payload());
    UUID uuid = UUID.fromString(reader.string());
    Component reason = GSON.deserialize(reader.string());
    return CompletableFuture.completedFuture(new byte[] {(byte) (kickLocal(uuid, reason) ? 1 : 0)});
  }

  private CompletableFuture<byte[]> answerMessage(FleetMessage request) {
    FleetCodec.Reader reader = new FleetCodec.Reader(request.payload());
    UUID uuid = UUID.fromString(reader.string());
    Component message = GSON.deserialize(reader.string());
    return CompletableFuture.completedFuture(
        new byte[] {(byte) (messageLocal(uuid, message) ? 1 : 0)});
  }

  private CompletableFuture<SendResult> sendLocal(UUID uuid, String server) {
    ConnectedPlayer player = players.get(uuid);
    if (player == null) {
      return CompletableFuture.completedFuture(SendResult.UNKNOWN_PLAYER);
    }
    BackendServer target = servers.getServer(server);
    if (target == null) {
      return CompletableFuture.completedFuture(SendResult.UNKNOWN_SERVER);
    }
    return player
        .requestConnection(target, false)
        .thenApply(
            result ->
                result.isSuccessful() || result instanceof ConnectionResult.InProgress
                    ? SendResult.SENT
                    : SendResult.FAILED)
        .exceptionally(_ -> SendResult.FAILED);
  }

  private boolean kickLocal(UUID uuid, Component reason) {
    ConnectedPlayer player = players.get(uuid);
    if (player == null) {
      return false;
    }
    player.disconnect(reason);
    return true;
  }

  private boolean messageLocal(UUID uuid, Component message) {
    ConnectedPlayer player = players.get(uuid);
    if (player == null) {
      return false;
    }
    player.sendMessage(message);
    return true;
  }

  // ---------------------------------------------------------------------------
  // Fleet API
  // ---------------------------------------------------------------------------

  /**
   * Returns this node's id.
   *
   * @return the id
   */
  public String nodeId() {
    return nodeId;
  }

  /**
   * Returns the network's name.
   *
   * @return {@code fleet.network}
   */
  public String network() {
    return config.network();
  }

  @Override
  public ProxyNode local() {
    return toNode(localRecord(), Instant.now());
  }

  @Override
  public Collection<ProxyNode> nodes() {
    List<ProxyNode> all = new ArrayList<>(remoteNodes.size() + 1);
    all.add(local());
    all.addAll(remoteNodes.values());
    return Collections.unmodifiableList(all);
  }

  @Override
  public Optional<ProxyNode> node(String id) {
    if (id.equals(nodeId)) {
      return Optional.of(local());
    }
    return Optional.ofNullable(remoteNodes.get(id));
  }

  @Override
  public Collection<FleetPlayer> players() {
    List<FleetPlayer> all = new ArrayList<>(players.count() + remotePlayers.size());
    for (ConnectedPlayer player : players.all()) {
      all.add(localPlayer(player));
    }
    all.addAll(remotePlayers.values());
    return Collections.unmodifiableList(all);
  }

  @Override
  public Optional<FleetPlayer> player(UUID uuid) {
    ConnectedPlayer here = players.get(uuid);
    if (here != null) {
      return Optional.of(localPlayer(here));
    }
    return Optional.ofNullable(remotePlayers.get(uuid));
  }

  @Override
  public Optional<FleetPlayer> player(String name) {
    ConnectedPlayer here = players.get(name);
    if (here != null) {
      return Optional.of(localPlayer(here));
    }
    return Optional.ofNullable(remoteByName.get(name.toLowerCase(Locale.ROOT)));
  }

  @Override
  public int playerCount() {
    int count = players.count();
    for (ProxyNode node : remoteNodes.values()) {
      count += node.players();
    }
    return count;
  }

  @Override
  public int playerCount(String server) {
    int count = 0;
    for (ConnectedPlayer player : players.all()) {
      if (server.equals(player.currentServerName())) {
        count++;
      }
    }
    for (FleetPlayer player : remotePlayers.values()) {
      if (server.equals(player.server())) {
        count++;
      }
    }
    return count;
  }

  @Override
  public CompletableFuture<SendResult> send(UUID uuid, String server) {
    if (players.get(uuid) != null) {
      return sendLocal(uuid, server);
    }
    FleetPlayer remote = remotePlayers.get(uuid);
    if (remote == null) {
      return CompletableFuture.completedFuture(SendResult.UNKNOWN_PLAYER);
    }
    byte[] request = new FleetCodec.Writer().string(uuid.toString()).string(server).toBytes();
    return bus.requestInternal(remote.proxyId(), CHANNEL_SEND, request, REQUEST_TIMEOUT)
        .handle(
            (reply, error) -> {
              if (error != null) {
                return unwrap(error) instanceof TimeoutException
                    ? SendResult.TIMED_OUT
                    : SendResult.FAILED;
              }
              try {
                return SendResult.valueOf(new String(reply, StandardCharsets.UTF_8));
              } catch (IllegalArgumentException e) {
                return SendResult.FAILED;
              }
            });
  }

  @Override
  public CompletableFuture<Boolean> kick(UUID uuid, Component reason) {
    if (players.get(uuid) != null) {
      return CompletableFuture.completedFuture(kickLocal(uuid, reason));
    }
    return remoteBoolean(uuid, CHANNEL_KICK, reason);
  }

  @Override
  public CompletableFuture<Boolean> message(UUID uuid, Component message) {
    if (players.get(uuid) != null) {
      return CompletableFuture.completedFuture(messageLocal(uuid, message));
    }
    return remoteBoolean(uuid, CHANNEL_MESSAGE, message);
  }

  private CompletableFuture<Boolean> remoteBoolean(UUID uuid, String channel, Component component) {
    FleetPlayer remote = remotePlayers.get(uuid);
    if (remote == null) {
      return CompletableFuture.completedFuture(false);
    }
    byte[] request =
        new FleetCodec.Writer().string(uuid.toString()).string(GSON.serialize(component)).toBytes();
    return bus.requestInternal(remote.proxyId(), channel, request, REQUEST_TIMEOUT)
        .handle((reply, error) -> error == null && reply.length == 1 && reply[0] == 1);
  }

  @Override
  public FleetBus bus() {
    return bus;
  }

  @Override
  public StoreState storeState() {
    return state;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private FleetPlayer localPlayer(ConnectedPlayer player) {
    PlayerRecord record = local.get(player.uuid());
    return new FleetPlayer(
        player.uuid(),
        player.username(),
        nodeId,
        player.currentServerName(),
        record != null ? record.since() : started);
  }

  private static ProxyNode toNode(NodeRecord record, Instant seen) {
    return new ProxyNode(
        record.id(),
        record.incarnation(),
        record.publicAddress(),
        record.players(),
        record.state(),
        seen,
        record.version());
  }

  private static FleetPlayer toPlayer(PlayerRecord record) {
    return new FleetPlayer(
        record.uuid(), record.name(), record.nodeId(), record.server(), record.since());
  }

  /** Times a store call for the metrics. */
  private <T> CompletableFuture<T> timed(String command, CompletableFuture<T> call) {
    long start = System.nanoTime();
    return call.whenComplete(
        (_, error) -> metrics.command(command, error == null, System.nanoTime() - start));
  }

  /** Awaits a store call on a fleet thread, within {@link #STORE_TIMEOUT}. */
  private <T> T await(String command, CompletableFuture<T> call) {
    try {
      return timed(command, call).get(STORE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the fleet store", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(command + ": " + cause(e), e.getCause());
    } catch (TimeoutException e) {
      throw new IllegalStateException(
          command + " timed out after " + STORE_TIMEOUT.toSeconds() + " s", e);
    }
  }

  private static Throwable unwrap(Throwable error) {
    Throwable cause = error;
    while ((cause instanceof ExecutionException
            || cause instanceof java.util.concurrent.CompletionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static String cause(Throwable error) {
    Throwable root = unwrap(error);
    String message = root.getMessage();
    return message == null ? root.getClass().getSimpleName() : message;
  }
}
