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
package dev.warp.proxy.fleet.valkey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.fleet.NodeState;
import dev.warp.api.fleet.StoreState;
import dev.warp.api.fleet.spi.ClaimResult;
import dev.warp.api.fleet.spi.FleetStore;
import dev.warp.api.fleet.spi.FleetStoreContext;
import dev.warp.api.fleet.spi.NodeRecord;
import dev.warp.api.fleet.spi.PlayerRecord;
import dev.warp.proxy.config.FleetConfig;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Valkey store against a real Valkey in Docker. Skipped without Docker: then test by hand with
 * {@code docker run --rm -p 6379:6379 valkey/valkey:8-alpine}, two proxies with {@code
 * fleet.store.type = valkey} and distinct {@code node-id}s, and {@code /warp fleet} on each.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("ValkeyFleetStore against Valkey")
class ValkeyFleetStoreIT {

  @Container
  static final GenericContainer<?> VALKEY =
      new GenericContainer<>("valkey/valkey:8-alpine").withExposedPorts(6379);

  private static final UUID STEVE = UUID.nameUUIDFromBytes("steve".getBytes());
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static MultiThreadIoEventLoopGroup loops;

  private final List<ValkeyFleetStore> stores = new ArrayList<>();
  private final java.util.concurrent.ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();

  @BeforeAll
  static void loops() {
    loops = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
  }

  @AfterAll
  static void stopLoops() {
    loops.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS);
  }

  @AfterEach
  void close() {
    for (ValkeyFleetStore store : stores) {
      store.close();
    }
    executor.shutdownNow();
  }

  private FleetConfig config(Duration ttl) {
    return new FleetConfig(
        new FleetConfig.Store(
            FleetConfig.StoreType.VALKEY,
            new FleetConfig.Valkey(
                VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379), false, null, null, 0)),
        "it-" + UUID.randomUUID().toString().substring(0, 8),
        null,
        null,
        Duration.ofMillis(100),
        ttl,
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        FleetConfig.OnStoreDown.ALLOW,
        true);
  }

  private ValkeyFleetStore connect(FleetConfig config, String nodeId) {
    ValkeyFleetStore store =
        new ValkeyFleetStore(config, () -> loops, () -> NioSocketChannel.class);
    stores.add(store);
    store.connect(new FleetStoreContext(config.network(), nodeId, executor)).join();
    await(() -> store.state() == StoreState.CONNECTED);
    return store;
  }

  private static <T> T get(java.util.concurrent.CompletableFuture<T> future) {
    try {
      return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + TIMEOUT);
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private static NodeRecord node(String id, long inc) {
    return new NodeRecord(id, inc, "a.example:25565", NodeState.READY, 3, "1.0", Instant.EPOCH);
  }

  private static PlayerRecord steve(String node, long inc) {
    return new PlayerRecord(STEVE, "Steve", node, inc, null, Instant.ofEpochMilli(1000));
  }

  @Nested
  @DisplayName("records")
  class Records {

    @Test
    @DisplayName(
        "should hand out incarnations, write heartbeats and expire them on the store's clock")
    void heartbeats() {
      FleetConfig config = config(Duration.ofMillis(400));
      ValkeyFleetStore store = connect(config, "a");

      long first = get(store.nextIncarnation("a"));
      long second = get(store.nextIncarnation("a"));
      assertTrue(second > first);

      get(store.heartbeat(node("a", second)));
      Collection<NodeRecord> live = get(store.nodes());
      assertEquals(1, live.size());
      NodeRecord seen = live.iterator().next();
      assertEquals(node("a", second), seen);

      await(() -> get(store.nodes()).isEmpty());
    }

    @Test
    @DisplayName("should claim, refuse a live owner, release ownership and move atomically")
    void ownership() {
      ValkeyFleetStore store = connect(config(Duration.ofSeconds(10)), "a");
      get(store.heartbeat(node("a", 1)));
      get(store.heartbeat(node("b", 5)));

      assertInstanceOf(ClaimResult.Claimed.class, get(store.claim(steve("a", 1))));
      assertEquals(new ClaimResult.Owned("a", 1), get(store.claim(steve("b", 5))));

      get(store.move(STEVE, "b", 5, "lobby")); // not the owner: ignored
      get(store.move(STEVE, "a", 1, "lobby"));
      PlayerRecord stored = get(store.players("a")).iterator().next();
      assertEquals("lobby", stored.server());
      assertEquals("Steve", stored.name());
      assertEquals(Instant.ofEpochMilli(1000), stored.since());

      get(store.release(STEVE, "b", 5)); // not the owner: ignored
      assertEquals(1, get(store.players("a")).size());
      get(store.release(STEVE, "a", 1));
      assertTrue(get(store.players("a")).isEmpty());
      assertInstanceOf(ClaimResult.Claimed.class, get(store.claim(steve("b", 5))));
    }

    @Test
    @DisplayName("should treat a record of a dead incarnation as unowned, and reap a node")
    void deadOwnerAndReap() {
      ValkeyFleetStore store = connect(config(Duration.ofSeconds(10)), "a");
      get(store.heartbeat(node("a", 1)));
      get(store.claim(steve("a", 1)));
      UUID alex = UUID.nameUUIDFromBytes("alex".getBytes());
      get(store.claim(new PlayerRecord(alex, "Alex", "a", 1, "lobby", Instant.EPOCH)));

      get(store.heartbeat(node("a", 2))); // a restarted
      assertInstanceOf(ClaimResult.Claimed.class, get(store.claim(steve("b", 9))));

      assertEquals(1, get(store.reap("a", 1)), "Alex's record of incarnation 1; Steve is b's now");
      assertTrue(get(store.players("a")).isEmpty());
      assertEquals(1, get(store.nodes()).size(), "incarnation 2 still heartbeats");
      assertEquals(0, get(store.reap("a", 2)));
      assertTrue(get(store.nodes()).isEmpty());
    }

    @Test
    @DisplayName("should deliver published messages to subscribers on another connection")
    void pubsub() {
      FleetConfig config = config(Duration.ofSeconds(10));
      ValkeyFleetStore a = connect(config, "a");
      ValkeyFleetStore b = connect(config, "b");
      List<String> heard = new CopyOnWriteArrayList<>();
      var subscription =
          b.subscribe(
              "warp:" + config.network() + ":x",
              bytes -> heard.add(new String(bytes, StandardCharsets.UTF_8)));
      // SUBSCRIBE is asynchronous: publish until the first message lands.
      await(
          () -> {
            get(
                a.publish(
                    "warp:" + config.network() + ":x", "hi".getBytes(StandardCharsets.UTF_8)));
            return !heard.isEmpty();
          });

      assertEquals("hi", heard.getFirst());
      subscription.unsubscribe();
      int before = heard.size();
      get(a.publish("warp:" + config.network() + ":x", "late".getBytes(StandardCharsets.UTF_8)));
      get(a.publish("warp:" + config.network() + ":y", "other".getBytes(StandardCharsets.UTF_8)));
      assertTrue(heard.size() >= before);
      assertTrue(heard.stream().noneMatch("other"::equals));
    }
  }

  @Nested
  @DisplayName("connection")
  class Connection {

    @Test
    @DisplayName("should start degraded against a closed port and report the failure")
    void unreachable() {
      FleetConfig config =
          new FleetConfig(
              new FleetConfig.Store(
                  FleetConfig.StoreType.VALKEY,
                  new FleetConfig.Valkey("127.0.0.1:1", false, null, null, 0)),
              "down",
              null,
              null,
              Duration.ofSeconds(1),
              Duration.ofSeconds(5),
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              FleetConfig.OnStoreDown.ALLOW,
              true);
      ValkeyFleetStore store =
          new ValkeyFleetStore(config, () -> loops, () -> NioSocketChannel.class);
      stores.add(store);

      get(store.connect(new FleetStoreContext("down", "a", executor)));

      assertEquals(StoreState.DEGRADED, store.state());
      await(() -> store.lastFailure() != null);
      Boolean failed = get(store.nodes().handle((_, error) -> error != null));
      assertTrue(failed);
    }

    @Test
    @DisplayName("should refuse a wrong password and stay degraded")
    void badPassword() {
      ValkeyFleetStore admin = connect(config(Duration.ofSeconds(10)), "admin");
      // The default user has no password: an ACL user with one is what a wrong password fails on.
      try {
        VALKEY.execInContainer(
            "valkey-cli", "ACL", "SETUSER", "locked", "on", ">right", "~*", "&*", "+@all");
      } catch (java.io.IOException | InterruptedException e) {
        throw new AssertionError(e);
      }
      FleetConfig config = config(Duration.ofSeconds(10));
      FleetConfig withPassword =
          new FleetConfig(
              new FleetConfig.Store(
                  FleetConfig.StoreType.VALKEY,
                  new FleetConfig.Valkey(
                      config.store().valkey().address(), false, "locked", "wrong", 0)),
              config.network(),
              null,
              null,
              config.heartbeat(),
              config.heartbeatTtl(),
              config.reconcile(),
              config.claimTimeout(),
              config.onStoreDown(),
              true);
      ValkeyFleetStore store =
          new ValkeyFleetStore(withPassword, () -> loops, () -> NioSocketChannel.class);
      stores.add(store);

      get(store.connect(new FleetStoreContext(config.network(), "b", executor)));

      assertEquals(StoreState.DEGRADED, store.state());
      await(() -> store.lastFailure() != null);
      assertTrue(
          String.valueOf(store.lastFailure()).contains("WRONGPASS")
              || String.valueOf(store.lastFailure()).contains("AUTH"),
          String.valueOf(store.lastFailure()));
      assertNull(admin.lastFailure());
    }
  }

  /** A {@link FleetStore} contract check that the single-node store passes too: same answers. */
  @Test
  @DisplayName("should agree with the single-node store on the contract")
  void contract() {
    FleetStore valkey = connect(config(Duration.ofSeconds(10)), "a");
    get(valkey.heartbeat(node("a", 1)));
    assertInstanceOf(ClaimResult.Claimed.class, get(valkey.claim(steve("a", 1))));
    assertEquals(new ClaimResult.Owned("a", 1), get(valkey.claim(steve("a", 2))));
    get(valkey.release(STEVE, "a", 1));
    assertTrue(get(valkey.players("a")).isEmpty());
  }

  /** Two proxies on one Valkey: what F1's in-memory test proves, against the real store. */
  @Nested
  @DisplayName("two proxies")
  class TwoProxies {

    private dev.warp.proxy.fleet.FleetService fleet(
        FleetConfig base, String id, dev.warp.proxy.player.PlayerRegistry players) {
      FleetConfig config =
          new FleetConfig(
              base.store(),
              base.network(),
              id,
              id + ".example:25565",
              base.heartbeat(),
              base.heartbeatTtl(),
              base.reconcile(),
              base.claimTimeout(),
              base.onStoreDown(),
              true);
      ValkeyFleetStore store =
          new ValkeyFleetStore(config, () -> loops, () -> NioSocketChannel.class);
      stores.add(store);
      dev.warp.proxy.fleet.FleetService service =
          new dev.warp.proxy.fleet.FleetService(
              config,
              store,
              players,
              new dev.warp.proxy.server.ServerRegistry(java.util.Map.of(), null, List.of()),
              new dev.warp.proxy.event.WarpEventBus(),
              new dev.warp.proxy.metrics.MetricRegistry(0),
              dev.warp.proxy.config.DuplicateLoginPolicy.KICK_EXISTING,
              true,
              "it",
              () -> false);
      service.start().join();
      return service;
    }

    @Test
    @DisplayName("should share players, settle a duplicate login and pick a drain target")
    void endToEnd() {
      FleetConfig base = config(Duration.ofSeconds(2));
      dev.warp.proxy.player.PlayerRegistry playersA = new dev.warp.proxy.player.PlayerRegistry();
      dev.warp.proxy.player.PlayerRegistry playersB = new dev.warp.proxy.player.PlayerRegistry();
      dev.warp.proxy.fleet.FleetService a = fleet(base, "a", playersA);
      dev.warp.proxy.fleet.FleetService b = fleet(base, "b", playersB);
      try {
        await(
            () -> a.storeState() == StoreState.CONNECTED && b.storeState() == StoreState.CONNECTED);
        await(() -> a.nodes().size() == 2 && b.nodes().size() == 2);

        dev.warp.proxy.connection.ConnectedPlayer onA =
            org.mockito.Mockito.mock(dev.warp.proxy.connection.ConnectedPlayer.class);
        org.mockito.Mockito.when(onA.uuid()).thenReturn(STEVE);
        org.mockito.Mockito.when(onA.username()).thenReturn("Steve");
        playersA.register(onA, true);
        assertInstanceOf(
            dev.warp.proxy.fleet.FleetService.Claim.Allowed.class, a.claim(onA).join());

        // Without a join notice (no PostLoginEvent here), the reconcile shows it to b.
        await(() -> b.player(STEVE).isPresent());
        assertEquals("a", b.player("steve").orElseThrow().proxyId());
        await(() -> b.playerCount() == 1);
        assertEquals(
            new dev.warp.proxy.config.DrainConfig.Target("a.example", 25565), b.transferTarget());

        dev.warp.proxy.connection.ConnectedPlayer onB =
            org.mockito.Mockito.mock(dev.warp.proxy.connection.ConnectedPlayer.class);
        org.mockito.Mockito.when(onB.uuid()).thenReturn(STEVE);
        org.mockito.Mockito.when(onB.username()).thenReturn("Steve");
        playersB.register(onB, true);
        assertInstanceOf(
            dev.warp.proxy.fleet.FleetService.Claim.Allowed.class, b.claim(onB).join());
        org.mockito.Mockito.verify(onA, org.mockito.Mockito.timeout(TIMEOUT.toMillis()))
            .disconnectDuplicate();

        a.close();
        await(() -> b.node("a").isEmpty());
      } finally {
        a.close();
        b.close();
      }
    }
  }
}
