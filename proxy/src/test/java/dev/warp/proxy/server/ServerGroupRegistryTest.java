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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.player.Player;
import dev.warp.api.server.Balancer;
import dev.warp.api.server.Route;
import dev.warp.api.server.ServerConnection;
import dev.warp.api.server.ServerGroup;
import dev.warp.api.server.ServerInfo;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServerGroupRegistry")
class ServerGroupRegistryTest {

  private static final Duration COOLDOWN = Duration.ofSeconds(10);

  private final AtomicLong now = new AtomicLong();
  private final ServerHealth health = new ServerHealth(now::get, COOLDOWN);

  private ServerRegistry registry(
      @Nullable String defaultTarget, List<String> fallback, String... servers) {
    Map<String, InetSocketAddress> addresses = new java.util.LinkedHashMap<>();
    int port = 25565;
    for (String server : servers) {
      addresses.put(server, new InetSocketAddress("localhost", port++));
    }
    return new ServerRegistry(addresses, defaultTarget, fallback, health);
  }

  private static List<ServerGroup.Member> members(String... servers) {
    return List.of(servers).stream().map(ServerGroup.Member::of).toList();
  }

  @Nested
  @DisplayName("register()")
  class Register {

    @Test
    @DisplayName("should register a group under its lowercase name and list it sorted")
    void registers() {
      ServerRegistry servers = registry("lobbies", List.of(), "lobby1", "lobby2");
      ServerGroupRegistry groups = servers.groups();

      ServerGroup group =
          groups.register("Lobbies", members("Lobby1", "lobby2"), Balancers.roundRobin());

      assertEquals("lobbies", group.name());
      assertEquals(
          List.of("lobby1", "lobby2"),
          group.members().stream().map(ServerGroup.Member::server).toList());
      assertTrue(group.isRegistered());
      assertEquals(Optional.of(group), groups.group("LOBBIES"));
      assertEquals(List.of(group), List.copyOf(groups.groups()));
      assertEquals(2, group.servers().size());
    }

    @Test
    @DisplayName("should leave out unregistered members and use them once registered")
    void lateMembers() {
      ServerRegistry servers = registry("lobbies", List.of(), "lobby1");
      ServerGroup group =
          servers.groups().register("lobbies", members("lobby1", "lobby2"), Balancers.roundRobin());

      assertEquals(1, group.servers().size());
      servers.register(new ServerInfo("lobby2", new InetSocketAddress("localhost", 1)));
      assertEquals(2, group.servers().size());
    }

    @Test
    @DisplayName("should replace a group registered again, and unregister it only while current")
    void replaceAndUnregister() {
      ServerRegistry servers = registry(null, List.of(), "a", "b");
      ServerGroupRegistry groups = servers.groups();
      ServerGroup first = groups.register("g", members("a"), Balancers.roundRobin());
      ServerGroup second = groups.register("g", members("b"), Balancers.roundRobin());

      assertFalse(first.isRegistered());
      assertTrue(second.isRegistered());
      assertFalse(groups.unregister(first));
      assertTrue(groups.unregister(second));
      assertTrue(groups.groups().isEmpty());
      assertEquals(Optional.empty(), groups.unregister("g"));
    }

    @Test
    @DisplayName(
        "should reject a group without server, with a server twice, or named like a server")
    void rejectsBadGroups() {
      ServerRegistry servers = registry(null, List.of(), "lobby");
      ServerGroupRegistry groups = servers.groups();

      assertThrows(
          IllegalArgumentException.class,
          () -> groups.register("g", List.of(), Balancers.roundRobin()));
      assertThrows(
          IllegalArgumentException.class,
          () -> groups.register("g", members("lobby", "LOBBY"), Balancers.roundRobin()));
      assertThrows(
          IllegalArgumentException.class,
          () -> groups.register("lobby", members("lobby"), Balancers.roundRobin()));
    }

    @Test
    @DisplayName("should hand out the built-in strategies by name")
    void builtIns() {
      ServerGroupRegistry groups = registry(null, List.of()).groups();

      assertTrue(groups.balancer("least-connections").isPresent());
      assertTrue(groups.balancer("nope").isEmpty());
      assertNotNull(groups.fill(5));
      assertEquals(Set.copyOf(Balancers.NAMES), groups.strategies());
      ServerGroup group =
          groups.register("g", members("x"), groups.balancer("round-robin").orElseThrow());
      assertFalse(group.strategy().isEmpty());
    }
  }

  @Nested
  @DisplayName("choose()")
  class Choose {

    @Test
    @DisplayName("should never pick the server the player is on")
    void skipsCurrentServer() {
      ServerRegistry servers = registry(null, List.of(), "a", "b");
      ServerGroup group = servers.groups().register("g", members("a", "b"), Balancers.roundRobin());
      Player player = mock(Player.class);
      ServerConnection connection = mock(ServerConnection.class);
      when(connection.server()).thenReturn(servers.getServer("a"));
      when(player.currentServer()).thenReturn(Optional.of(connection));

      for (int i = 0; i < 4; i++) {
        assertEquals("b", group.choose(player).orElseThrow().name());
      }
      assertEquals(Optional.empty(), group.choose(List.of("A", "b")));
    }

    @Test
    @DisplayName("should skip a server whose breaker is open, and come back to it once probed")
    void skipsOpenBreaker() {
      ServerRegistry servers = registry(null, List.of(), "a", "b");
      ServerGroup group =
          servers.groups().register("g", members("a", "b"), Balancers.leastConnections());
      health.connectFailed("a");

      assertEquals("b", group.choose(Set.of()).orElseThrow().name());

      now.addAndGet(COOLDOWN.toNanos());
      assertEquals("a", group.choose(Set.of()).orElseThrow().name(), "the probe");
      assertEquals("b", group.choose(Set.of()).orElseThrow().name(), "while the probe is out");
      health.connectSucceeded("a");
      assertEquals("a", group.choose(Set.of()).orElseThrow().name());
    }

    @Test
    @DisplayName("should hand the balancer what the proxy knows, and respect its refusal")
    void candidatesAndRefusal() {
      ServerRegistry servers = registry(null, List.of(), "a", "b");
      health.pingSucceeded("b", Duration.ofMillis(7));
      Balancer inspecting =
          candidates -> {
            assertEquals(2, candidates.size());
            assertEquals(3, candidates.get(0).weight());
            assertEquals(0, candidates.get(0).connections());
            assertEquals(Optional.empty(), candidates.get(0).latency());
            assertEquals(Optional.of(Duration.ofMillis(7)), candidates.get(1).latency());
            return Optional.empty();
          };
      ServerGroup group =
          servers
              .groups()
              .register(
                  "g",
                  List.of(new ServerGroup.Member("a", 3), ServerGroup.Member.of("b")),
                  inspecting);

      assertEquals(Optional.empty(), group.choose(Set.of()));
    }
  }

  @Nested
  @DisplayName("as a destination")
  class Destination {

    @Test
    @DisplayName("should be the default server")
    void defaultGroup() {
      ServerRegistry servers = registry("lobbies", List.of(), "lobby1", "lobby2");
      servers.groups().register("lobbies", members("lobby1", "lobby2"), Balancers.roundRobin());

      BackendServer first = servers.initialServer();
      BackendServer second = servers.initialServer();

      assertNotNull(first);
      assertNotNull(second);
      assertEquals(Set.of("lobby1", "lobby2"), Set.of(first.name(), second.name()));
    }

    @Test
    @DisplayName("should be a fallback entry, excluding the servers that failed")
    void fallbackGroup() {
      ServerRegistry servers = registry("game", List.of("lobbies"), "game", "lobby1", "lobby2");
      servers
          .groups()
          .register("lobbies", members("lobby1", "lobby2"), Balancers.leastConnections());

      BackendServer fallback = servers.nextFallback(Set.of("game", "lobby1"));

      assertNotNull(fallback);
      assertEquals("lobby2", fallback.name());
      assertNull(servers.nextFallback(Set.of("game", "lobby1", "lobby2")));
    }

    @Test
    @DisplayName("should prefer a healthy fallback over an unhealthy default, then take any")
    void healthAwareDefault() {
      ServerRegistry servers = registry("lobby", List.of("lobby", "backup"), "lobby", "backup");
      health.pingFailed("lobby");

      BackendServer chosen = servers.initialServer();
      assertNotNull(chosen);
      assertEquals("backup", chosen.name());

      health.pingFailed("backup");
      BackendServer any = servers.initialServer();
      assertNotNull(any);
      assertEquals("lobby", any.name(), "better to try than to turn the player away");
    }

    @Test
    @DisplayName("should follow a route to a server or a group, and fall to the default otherwise")
    void routes() {
      ServerRegistry servers = registry("lobby", List.of(), "lobby", "kitpvp", "sw1", "sw2");
      servers.groups().register("skywars", members("sw1", "sw2"), Balancers.roundRobin());

      BackendServer direct = servers.initialServer(new Route("kitpvp.example.com", "kitpvp"));
      BackendServer grouped = servers.initialServer(new Route("skywars.example.com", "skywars"));
      BackendServer unknown = servers.initialServer(new Route("*.example.com", "nothing"));

      assertNotNull(direct);
      assertEquals("kitpvp", direct.name());
      assertNotNull(grouped);
      assertTrue(Set.of("sw1", "sw2").contains(grouped.name()));
      assertNotNull(unknown);
      assertEquals("lobby", unknown.name());
    }

    @Test
    @DisplayName("should forget a server's health when it is unregistered")
    void forgetsHealth() {
      ServerRegistry servers = registry("lobby", List.of(), "lobby");
      health.pingFailed("lobby");

      servers.unregister("lobby");

      assertTrue(health.allows("lobby"));
    }
  }
}
