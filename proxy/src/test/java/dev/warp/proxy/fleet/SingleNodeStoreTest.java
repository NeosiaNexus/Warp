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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.fleet.NodeState;
import dev.warp.api.fleet.StoreState;
import dev.warp.api.fleet.spi.ClaimResult;
import dev.warp.api.fleet.spi.FleetStoreContext;
import dev.warp.api.fleet.spi.NodeRecord;
import dev.warp.api.fleet.spi.PlayerRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SingleNodeStore")
class SingleNodeStoreTest {

  private static final UUID STEVE = UUID.nameUUIDFromBytes("steve".getBytes());

  private final SingleNodeStore store = new SingleNodeStore();

  @BeforeEach
  void connect() {
    store.connect(new FleetStoreContext("main", "a", Runnable::run)).join();
    store.heartbeat(node("a", 1)).join();
  }

  private static NodeRecord node(String id, long incarnation) {
    return new NodeRecord(id, incarnation, null, NodeState.READY, 0, "test", Instant.EPOCH);
  }

  private static PlayerRecord steve(String node, long incarnation) {
    return new PlayerRecord(STEVE, "Steve", node, incarnation, null, Instant.EPOCH);
  }

  @Nested
  @DisplayName("ownership")
  class Ownership {

    @Test
    @DisplayName("should claim an unowned player, then report the live owner")
    void claimThenOwned() {
      assertInstanceOf(ClaimResult.Claimed.class, store.claim(steve("a", 1)).join());

      ClaimResult second = store.claim(steve("b", 7)).join();

      assertEquals(new ClaimResult.Owned("a", 1), second);
    }

    @Test
    @DisplayName("should treat a record of a dead incarnation as unowned")
    void deadOwner() {
      store.claim(steve("a", 1)).join();
      store.heartbeat(node("a", 2)).join(); // the node restarted

      assertInstanceOf(ClaimResult.Claimed.class, store.claim(steve("b", 1)).join());
    }

    @Test
    @DisplayName("should release and move only for the owning incarnation")
    void releaseAndMove() {
      store.claim(steve("a", 1)).join();

      store.move(STEVE, "a", 99, "lobby").join();
      store.release(STEVE, "a", 99).join();
      assertEquals(null, store.players("a").join().iterator().next().server());

      store.move(STEVE, "a", 1, "lobby").join();
      assertEquals("lobby", store.players("a").join().iterator().next().server());
      store.release(STEVE, "a", 1).join();
      assertTrue(store.players("a").join().isEmpty());
    }

    @Test
    @DisplayName("should reap a node's players and heartbeat for one incarnation only")
    void reap() {
      store.claim(steve("a", 1)).join();
      store.heartbeat(node("b", 3)).join();
      UUID alex = UUID.nameUUIDFromBytes("alex".getBytes());
      store.claim(new PlayerRecord(alex, "Alex", "b", 3, null, Instant.EPOCH)).join();

      assertEquals(0, store.reap("a", 2).join());
      assertEquals(1, store.reap("a", 1).join());

      assertTrue(store.players("a").join().isEmpty());
      assertEquals(1, store.nodes().join().size());
      assertEquals("b", store.nodes().join().iterator().next().id());
    }

    @Test
    @DisplayName("should hand out increasing incarnations")
    void incarnations() {
      long first = store.nextIncarnation("a").join();
      long second = store.nextIncarnation("a").join();

      assertTrue(second > first);
    }
  }

  @Nested
  @DisplayName("channels")
  class Channels {

    @Test
    @DisplayName("should deliver a published message to the channel's subscribers only")
    void publish() {
      List<String> heard = new ArrayList<>();
      store.subscribe("x", bytes -> heard.add("x:" + new String(bytes)));
      store.subscribe("y", bytes -> heard.add("y:" + new String(bytes)));

      store.publish("x", "hi".getBytes()).join();

      assertEquals(List.of("x:hi"), heard);
    }

    @Test
    @DisplayName("should stop delivering once unsubscribed")
    void unsubscribe() {
      List<String> heard = new ArrayList<>();
      var subscription = store.subscribe("x", bytes -> heard.add(new String(bytes)));
      subscription.unsubscribe();

      store.publish("x", "hi".getBytes()).join();

      assertTrue(heard.isEmpty());
      assertEquals(false, subscription.isActive());
    }

    @Test
    @DisplayName("should always be a single node")
    void state() {
      assertEquals(StoreState.SINGLE_NODE, store.state());
    }
  }
}
