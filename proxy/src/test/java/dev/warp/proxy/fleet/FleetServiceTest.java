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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.warp.api.event.fleet.FleetNodeJoinEvent;
import dev.warp.api.event.fleet.FleetNodeLeaveEvent;
import dev.warp.api.event.fleet.FleetPlayerJoinEvent;
import dev.warp.api.event.fleet.FleetPlayerLeaveEvent;
import dev.warp.api.event.fleet.FleetPlayerServerChangeEvent;
import dev.warp.api.event.fleet.FleetStoreStateEvent;
import dev.warp.api.event.player.DisconnectEvent;
import dev.warp.api.event.player.PostLoginEvent;
import dev.warp.api.event.player.ServerConnectedEvent;
import dev.warp.api.fleet.FleetMessage;
import dev.warp.api.fleet.FleetPlayer;
import dev.warp.api.fleet.NodeState;
import dev.warp.api.fleet.SendResult;
import dev.warp.api.fleet.StoreState;
import dev.warp.api.server.RegisteredServer;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.config.DuplicateLoginPolicy;
import dev.warp.proxy.config.FleetConfig;
import dev.warp.proxy.connection.ConnectedPlayer;
import dev.warp.proxy.connection.WarpPlayer;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.i18n.Message;
import dev.warp.proxy.metrics.MetricRegistry;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.server.ServerRegistry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FleetService")
class FleetServiceTest {

  private static final UUID STEVE = UUID.nameUUIDFromBytes("steve".getBytes());
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final SharedMemoryStore store = new SharedMemoryStore();
  private final List<Node> nodes = new CopyOnWriteArrayList<>();

  @AfterEach
  void close() {
    for (Node node : nodes) {
      node.fleet.close();
    }
  }

  /** One proxy of the fleet: its registries, its bus, its fleet, and the events it saw. */
  final class Node {
    final String id;
    final PlayerRegistry players = new PlayerRegistry();
    final ServerRegistry servers = new ServerRegistry(Map.of(), null, List.of());
    final WarpEventBus events = new WarpEventBus();
    final SharedMemoryStore.Handle handle = (SharedMemoryStore.Handle) store.handle();
    final List<Object> seen = new CopyOnWriteArrayList<>();
    final FleetService fleet;
    private volatile boolean draining;

    Node(
        String id,
        @Nullable String publicAddress,
        DuplicateLoginPolicy policy,
        FleetConfig.OnStoreDown onStoreDown) {
      this.id = id;
      FleetConfig config =
          new FleetConfig(
              new FleetConfig.Store(
                  FleetConfig.StoreType.VALKEY,
                  new FleetConfig.Valkey("shared", false, null, null, 0)),
              "test",
              id,
              publicAddress,
              Duration.ofMillis(50),
              Duration.ofMillis(200),
              Duration.ofMillis(200),
              Duration.ofSeconds(2),
              onStoreDown,
              true);
      PluginEventBus watcher = new PluginEventBus(events, "test", null, _ -> {});
      for (Class<?> type :
          List.of(
              FleetNodeJoinEvent.class,
              FleetNodeLeaveEvent.class,
              FleetPlayerJoinEvent.class,
              FleetPlayerLeaveEvent.class,
              FleetPlayerServerChangeEvent.class,
              FleetStoreStateEvent.class)) {
        var _ = watcher.subscribe(type, seen::add);
      }
      BooleanSupplier drainingNow = () -> draining;
      this.fleet =
          new FleetService(
              config,
              handle,
              players,
              servers,
              events,
              new MetricRegistry(0),
              policy,
              true,
              "t",
              drainingNow);
      fleet.start().join();
      nodes.add(this);
    }

    /** Registers a player here and settles it with the fleet, as a login does. */
    ConnectedPlayer login(UUID uuid, String name) {
      ConnectedPlayer player = mock(ConnectedPlayer.class);
      when(player.uuid()).thenReturn(uuid);
      when(player.username()).thenReturn(name);
      players.register(player, true);
      FleetService.Claim claim = fleet.claim(player).join();
      assertInstanceOf(FleetService.Claim.Allowed.class, claim, claim.toString());
      WarpPlayer api = mock(WarpPlayer.class);
      when(api.uniqueId()).thenReturn(uuid);
      when(api.connectedPlayer()).thenReturn(player);
      when(player.api()).thenReturn(api);
      events.fire(new PostLoginEvent(api)).join();
      return player;
    }

    /** Makes this node drain, as its next heartbeat says. */
    void drain() {
      draining = true;
    }

    <E> E event(Class<E> type) {
      await(() -> seen.stream().anyMatch(type::isInstance));
      return seen.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }
  }

  Node node(String id) {
    return new Node(id, null, DuplicateLoginPolicy.KICK_EXISTING, FleetConfig.OnStoreDown.ALLOW);
  }

  static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + TIMEOUT);
      }
      Thread.onSpinWait();
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  @Nested
  @DisplayName("nodes")
  class Nodes {

    @Test
    @DisplayName("should see each other, the local node first")
    void seeEachOther() {
      Node a = node("a");
      Node b = node("b");

      await(() -> a.fleet.nodes().size() == 2 && b.fleet.nodes().size() == 2);

      assertEquals("a", a.fleet.local().id());
      assertEquals("a", a.fleet.nodes().iterator().next().id());
      assertEquals("b", a.fleet.node("b").orElseThrow().id());
      assertEquals(NodeState.READY, a.fleet.node("b").orElseThrow().state());
      assertEquals("b", a.event(FleetNodeJoinEvent.class).node().id());
      assertEquals(StoreState.CONNECTED, a.fleet.storeState());
    }

    @Test
    @DisplayName("should drop a node that says goodbye, with its players")
    void goodbye() {
      Node a = node("a");
      Node b = node("b");
      a.login(STEVE, "Steve");
      await(() -> b.fleet.player(STEVE).isPresent());

      a.fleet.close();

      await(() -> b.fleet.node("a").isEmpty());
      assertTrue(b.fleet.player(STEVE).isEmpty());
      assertEquals(
          FleetNodeLeaveEvent.Reason.SHUTDOWN, b.event(FleetNodeLeaveEvent.class).reason());
      assertEquals(NodeState.DOWN, b.event(FleetNodeLeaveEvent.class).node().state());
      assertEquals(STEVE, b.event(FleetPlayerLeaveEvent.class).player().uuid());
    }

    @Test
    @DisplayName("should reap a node whose heartbeat expired, and free its players")
    void expired() {
      Node a = node("a");
      Node b = node("b");
      a.login(STEVE, "Steve");
      await(() -> b.fleet.player(STEVE).isPresent());
      a.handle.become(StoreState.DEGRADED); // a stops heartbeating, as a crash or a partition
      store.kill("a");

      await(() -> b.fleet.node("a").isEmpty());

      assertEquals(FleetNodeLeaveEvent.Reason.EXPIRED, b.event(FleetNodeLeaveEvent.class).reason());
      assertTrue(b.fleet.player(STEVE).isEmpty());
      assertTrue(b.handle.players("a").join().isEmpty(), "reaped");
    }

    @Test
    @DisplayName("should pick the least loaded ready node with an address as drain target")
    void transferTarget() {
      Node a =
          new Node(
              "a", "a.example:25566", DuplicateLoginPolicy.AUTO, FleetConfig.OnStoreDown.ALLOW);
      Node b = new Node("b", "b.example", DuplicateLoginPolicy.AUTO, FleetConfig.OnStoreDown.ALLOW);
      Node c = node("c");
      b.login(STEVE, "Steve");
      await(
          () ->
              c.fleet.node("b").map(n -> n.players() == 1).orElse(false)
                  && c.fleet.node("a").isPresent());

      assertEquals(new DrainConfig.Target("a.example", 25566), c.fleet.transferTarget());

      a.drain();
      await(() -> c.fleet.node("a").map(n -> n.state() == NodeState.DRAINING).orElse(false));
      assertEquals(new DrainConfig.Target("b.example", 25565), c.fleet.transferTarget());
      assertEquals(new DrainConfig.Target("b.example", 25565), a.fleet.transferTarget());
    }
  }

  @Nested
  @DisplayName("players")
  class Players {

    @Test
    @DisplayName("should show a player of another node, move it and forget it")
    void lifecycle() {
      Node a = node("a");
      Node b = node("b");
      ConnectedPlayer steve = a.login(STEVE, "Steve");

      await(() -> b.fleet.player(STEVE).isPresent());
      FleetPlayer seen = b.fleet.player("steve").orElseThrow();
      assertEquals("a", seen.proxyId());
      assertEquals("Steve", seen.name());
      assertEquals(Optional.empty(), seen.serverIfAny());
      assertEquals(STEVE, b.event(FleetPlayerJoinEvent.class).player().uuid());
      await(() -> b.fleet.playerCount() == 1);
      assertEquals(1, a.fleet.playerCount());
      assertEquals(1, b.fleet.players().size());

      RegisteredServer lobby = mock(RegisteredServer.class);
      when(lobby.name()).thenReturn("lobby");
      a.events.fire(new ServerConnectedEvent(steve.api(), lobby, null)).join();
      await(() -> b.fleet.player(STEVE).flatMap(FleetPlayer::serverIfAny).isPresent());
      assertEquals("lobby", b.fleet.player(STEVE).orElseThrow().server());
      assertEquals(1, b.fleet.playerCount("lobby"));
      assertNull(b.event(FleetPlayerServerChangeEvent.class).previousServer());

      a.players.unregister(steve);
      a.events
          .fire(new DisconnectEvent(steve.api(), DisconnectEvent.Cause.CLIENT_DISCONNECTED))
          .join();
      await(() -> b.fleet.player(STEVE).isEmpty());
      assertEquals(STEVE, b.event(FleetPlayerLeaveEvent.class).player().uuid());
      assertTrue(a.handle.players("a").join().isEmpty());
    }

    @Test
    @DisplayName("should repair a view that missed a notice at the next reconcile")
    void reconcile() {
      Node a = node("a");
      Node b = node("b");
      await(() -> b.fleet.node("a").isPresent());
      b.handle.become(StoreState.DEGRADED); // b misses what a publishes now
      await(() -> b.fleet.storeState() == StoreState.DEGRADED);
      a.login(STEVE, "Steve");

      b.handle.become(StoreState.CONNECTED);

      await(() -> b.fleet.player(STEVE).isPresent());
      assertEquals(StoreState.CONNECTED, b.fleet.storeState());
      assertEquals(StoreState.DEGRADED, b.event(FleetStoreStateEvent.class).current());
    }

    @Test
    @DisplayName("should kick the session on the other node when the policy kicks the existing one")
    void duplicateKick() {
      Node a = node("a");
      Node b = node("b");
      ConnectedPlayer onA = a.login(STEVE, "Steve");
      await(() -> b.fleet.node("a").isPresent());

      ConnectedPlayer onB = b.login(STEVE, "Steve");

      verify(onA, timeout(TIMEOUT.toMillis())).disconnectDuplicate();
      assertEquals("b", b.fleet.player(STEVE).orElseThrow().proxyId());
      assertEquals(STEVE, onB.uuid());
    }

    @Test
    @DisplayName("should refuse the newcomer when the policy keeps the existing session")
    void duplicateRefuse() {
      Node a = node("a");
      Node b = new Node("b", null, DuplicateLoginPolicy.REFUSE_NEW, FleetConfig.OnStoreDown.ALLOW);
      a.login(STEVE, "Steve");
      await(() -> b.fleet.node("a").isPresent());
      ConnectedPlayer onB = mock(ConnectedPlayer.class);
      when(onB.uuid()).thenReturn(STEVE);
      when(onB.username()).thenReturn("Steve");
      b.players.register(onB, false);

      FleetService.Claim claim = b.fleet.claim(onB).join();

      assertEquals(new FleetService.Claim.Refused(Message.ALREADY_CONNECTED), claim);
      assertEquals("a", a.fleet.player(STEVE).orElseThrow().proxyId());
    }

    @Test
    @DisplayName("should follow on-store-down while the store is unreachable")
    void storeDown() {
      Node allow = node("a");
      Node refuse =
          new Node("b", null, DuplicateLoginPolicy.AUTO, FleetConfig.OnStoreDown.REFUSE_NEW_LOGINS);
      allow.handle.become(StoreState.DEGRADED);
      refuse.handle.become(StoreState.DEGRADED);
      await(
          () ->
              allow.fleet.storeState() == StoreState.DEGRADED
                  && refuse.fleet.storeState() == StoreState.DEGRADED);
      ConnectedPlayer player = mock(ConnectedPlayer.class);
      when(player.uuid()).thenReturn(STEVE);
      when(player.username()).thenReturn("Steve");

      assertEquals(new FleetService.Claim.Allowed(), allow.fleet.claim(player).join());
      assertEquals(
          new FleetService.Claim.Refused(Message.FLEET_UNAVAILABLE),
          refuse.fleet.claim(player).join());
    }

    @Test
    @DisplayName("should send, kick and message a player through its own node")
    void remoteActions() {
      Node a = node("a");
      Node b = node("b");
      ConnectedPlayer steve = a.login(STEVE, "Steve");
      await(() -> b.fleet.player(STEVE).isPresent());

      assertEquals(SendResult.UNKNOWN_SERVER, b.fleet.send(STEVE, "lobby").join());
      assertEquals(SendResult.UNKNOWN_PLAYER, b.fleet.send(UUID.randomUUID(), "lobby").join());
      assertTrue(b.fleet.message(STEVE, Component.text("hi")).join());
      verify(steve, timeout(TIMEOUT.toMillis())).sendMessage(Component.text("hi"));
      assertTrue(b.fleet.kick(STEVE, Component.text("bye")).join());
      verify(steve, timeout(TIMEOUT.toMillis())).disconnect(Component.text("bye"));
      assertFalse(b.fleet.kick(UUID.randomUUID(), Component.text("bye")).join());
    }
  }

  @Nested
  @DisplayName("bus")
  class Bus {

    @Test
    @DisplayName("should deliver a published message to every node, the sender included")
    void publish() {
      Node a = node("a");
      Node b = node("b");
      List<FleetMessage> heardOnB = new CopyOnWriteArrayList<>();
      List<FleetMessage> heardOnA = new CopyOnWriteArrayList<>();
      var _ = b.fleet.bus().subscribe("chat", heardOnB::add);
      var _ = a.fleet.bus().subscribe("chat", heardOnA::add);

      a.fleet.bus().publish("chat", "hello".getBytes(StandardCharsets.UTF_8));

      await(() -> !heardOnB.isEmpty() && !heardOnA.isEmpty());
      assertEquals("a", heardOnB.getFirst().senderProxyId());
      assertEquals("chat", heardOnB.getFirst().channel());
      assertEquals("hello", new String(heardOnB.getFirst().payload(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("should answer a request from the node's responder, and fail without one")
    void request() {
      Node a = node("a");
      Node b = node("b");
      await(() -> a.fleet.node("b").isPresent());
      var _ =
          b.fleet
              .bus()
              .respond(
                  "echo",
                  request ->
                      CompletableFuture.completedFuture(
                          (request.senderProxyId()
                                  + ":"
                                  + new String(request.payload(), StandardCharsets.UTF_8))
                              .getBytes(StandardCharsets.UTF_8)));

      byte[] reply =
          a.fleet
              .bus()
              .request("b", "echo", "ping".getBytes(StandardCharsets.UTF_8), TIMEOUT)
              .join();

      assertEquals("a:ping", new String(reply, StandardCharsets.UTF_8));
      CompletableFuture<byte[]> none = a.fleet.bus().request("b", "nobody", new byte[0], TIMEOUT);
      assertThrows(Exception.class, none::join);
      CompletableFuture<byte[]> unknown =
          a.fleet.bus().request("zzz", "echo", new byte[0], TIMEOUT);
      assertTrue(unknown.isCompletedExceptionally());
    }

    @Test
    @DisplayName("should keep the proxy's own channels and the payload cap")
    void guards() {
      Node a = node("a");

      assertThrows(
          IllegalArgumentException.class, () -> a.fleet.bus().publish("warp:kick", new byte[0]));
      assertThrows(
          IllegalArgumentException.class, () -> a.fleet.bus().subscribe("warp:x", _ -> {}));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              a.fleet.bus().respond("warp:x", _ -> CompletableFuture.completedFuture(new byte[0])));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              a.fleet.bus().publish("big", new byte[dev.warp.api.fleet.FleetBus.MAX_PAYLOAD + 1]));
    }
  }

  @Nested
  @DisplayName("single node")
  class SingleNode {

    @Test
    @DisplayName("should be this proxy alone, with every call answered locally")
    void alone() {
      PlayerRegistry players = new PlayerRegistry();
      FleetService fleet =
          FleetService.singleNode(
              players,
              new ServerRegistry(Map.of(), null, List.of()),
              new WarpEventBus(),
              new MetricRegistry(0),
              DuplicateLoginPolicy.AUTO,
              true);
      try {
        ConnectedPlayer steve = mock(ConnectedPlayer.class);
        when(steve.uuid()).thenReturn(STEVE);
        when(steve.username()).thenReturn("Steve");
        players.register(steve, true);

        assertEquals(StoreState.SINGLE_NODE, fleet.storeState());
        assertEquals(1, fleet.nodes().size());
        assertEquals(new FleetService.Claim.Allowed(), fleet.claim(steve).join());
        assertEquals(1, fleet.playerCount());
        assertEquals("Steve", fleet.player("STEVE").orElseThrow().name());
        assertEquals(fleet.local().id(), fleet.player(STEVE).orElseThrow().proxyId());
        assertEquals(SendResult.UNKNOWN_PLAYER, fleet.send(UUID.randomUUID(), "lobby").join());
        assertEquals(SendResult.UNKNOWN_SERVER, fleet.send(STEVE, "lobby").join());
        assertFalse(fleet.kick(UUID.randomUUID(), Component.text("x")).join());
        assertNull(fleet.transferTarget());
        assertTrue(fleet.local().players() == 1 && fleet.local().state() == NodeState.READY);
      } finally {
        fleet.close();
      }
    }
  }
}
