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
package dev.warp.proxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import dev.warp.api.fleet.FleetPlayer;
import dev.warp.api.fleet.NodeState;
import dev.warp.api.fleet.ProxyNode;
import dev.warp.api.fleet.SendResult;
import dev.warp.api.fleet.StoreState;
import dev.warp.api.server.RegisteredServer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("the staff commands on a fleet")
class FleetCommandsTest {

  private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes());

  private final AdminFixture proxy = new AdminFixture();
  private final RegisteredServer lobby = proxy.server("lobby");
  private final ProxyNode local =
      new ProxyNode("local", 1, null, 1, NodeState.READY, Instant.EPOCH, "0.1");
  private final ProxyNode other =
      new ProxyNode("proxy-2", 4, "p2.example:25565", 2, NodeState.DRAINING, Instant.EPOCH, "0.1");
  private final FleetPlayer alex =
      new FleetPlayer(ALEX, "Alex", "proxy-2", "survival", Instant.EPOCH);

  @BeforeEach
  void network() {
    proxy.player("Steve", lobby);
    lenient().when(proxy.fleet.storeState()).thenReturn(StoreState.CONNECTED);
    lenient().when(proxy.fleet.local()).thenReturn(local);
    lenient().when(proxy.fleet.nodes()).thenReturn(List.of(local, other));
    lenient().when(proxy.fleet.playerCount()).thenReturn(3);
    lenient().when(proxy.fleet.playerCount(anyString())).thenReturn(0);
    lenient().when(proxy.fleet.playerCount("lobby")).thenReturn(2);
    lenient().when(proxy.fleet.player(anyString())).thenReturn(Optional.empty());
    lenient().when(proxy.fleet.player("Alex")).thenReturn(Optional.of(alex));
    lenient().when(proxy.fleet.player("alex")).thenReturn(Optional.of(alex));
  }

  @Nested
  @DisplayName("/glist")
  class Glist {

    @Test
    @DisplayName("should count the whole network, then list each proxy")
    void overview() {
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "glist");

      assertEquals(
          """
          3 players online (click a server, or /glist <server|all>, for names)
          - lobby 2
          - not on a server yet: 1
          - proxy local 1
          - proxy proxy-2 2\
          """,
          console.last());
    }
  }

  @Nested
  @DisplayName("/find")
  class Find {

    @Test
    @DisplayName("should locate a player of another proxy without personal details")
    void remote() {
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "find Alex");

      assertEquals("Alex is on proxy proxy-2, server survival", console.last());
    }

    @Test
    @DisplayName("should say when the remote player is between servers")
    void remoteConnecting() {
      when(proxy.fleet.player("Alex"))
          .thenReturn(Optional.of(new FleetPlayer(ALEX, "Alex", "proxy-2", null, Instant.EPOCH)));
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "find Alex");

      assertEquals("Alex is on proxy proxy-2, not on a server yet", console.last());
    }
  }

  @Nested
  @DisplayName("/send")
  class Send {

    @Test
    @DisplayName("should move a player of another proxy through that proxy")
    void remote() {
      when(proxy.fleet.send(eq(ALEX), eq("lobby")))
          .thenReturn(CompletableFuture.completedFuture(SendResult.SENT));
      AdminFixture.Source console = AdminFixture.console();

      assertTrue(proxy.run(console, "send Alex lobby"));

      assertEquals("Sent Alex to lobby on proxy proxy-2.", console.last());
    }

    @Test
    @DisplayName("should explain a failure on the other proxy")
    void remoteFailure() {
      when(proxy.fleet.send(eq(ALEX), eq("creative")))
          .thenReturn(CompletableFuture.completedFuture(SendResult.UNKNOWN_SERVER));
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "send player Alex creative");

      assertEquals(
          "Could not send Alex to creative on proxy proxy-2: that proxy knows no such server.",
          console.last());
    }
  }

  @Nested
  @DisplayName("/warp fleet")
  class WarpFleet {

    @Test
    @DisplayName("should list the network, its store and every proxy")
    void nodes() {
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "warp fleet");

      assertEquals(
          """
          Fleet  · store connected · 2 proxies
          - local (this proxy) ready incarnation 1, no public address, 0.1 1
          - proxy-2 draining incarnation 4, p2.example:25565, 0.1 2\
          """,
          console.last());
    }

    @Test
    @DisplayName("should say so when the proxy runs alone")
    void single() {
      when(proxy.fleet.storeState()).thenReturn(StoreState.SINGLE_NODE);
      AdminFixture.Source console = AdminFixture.console();

      proxy.run(console, "warp fleet");

      assertEquals(
          "No fleet store: this proxy (local) runs alone. Set fleet.store.type to join a network.",
          console.last());
    }
  }
}
