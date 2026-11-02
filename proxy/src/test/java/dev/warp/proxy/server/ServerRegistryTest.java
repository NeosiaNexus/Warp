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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.server.ServerInfo;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServerRegistry")
class ServerRegistryTest {

  private static final InetSocketAddress ADDR_1 = new InetSocketAddress("localhost", 25565);
  private static final InetSocketAddress ADDR_2 = new InetSocketAddress("localhost", 25566);
  private static final InetSocketAddress ADDR_3 = new InetSocketAddress("localhost", 25567);

  @Nested
  @DisplayName("nextFallback()")
  class NextFallback {

    @Test
    @DisplayName("should return first fallback server when no servers are excluded")
    void returnsFirstWhenNoneExcluded() {
      ServerRegistry registry = registry(List.of("lobby", "survival"));
      BackendServer result = registry.nextFallback(Set.of());

      assertNotNull(result);
      assertEquals("lobby", result.name());
    }

    @Test
    @DisplayName("should skip excluded servers and return next available")
    void skipsExcludedServers() {
      ServerRegistry registry = registry(List.of("lobby", "survival", "creative"));
      BackendServer result = registry.nextFallback(Set.of("lobby"));

      assertNotNull(result);
      assertEquals("survival", result.name());
    }

    @Test
    @DisplayName("should return null when all fallback servers are excluded")
    void returnsNullWhenAllExcluded() {
      ServerRegistry registry = registry(List.of("lobby", "survival"));
      BackendServer result = registry.nextFallback(Set.of("lobby", "survival"));

      assertNull(result);
    }

    @Test
    @DisplayName("should respect fallback order")
    void respectsOrder() {
      ServerRegistry registry = registry(List.of("survival", "lobby"));
      BackendServer result = registry.nextFallback(Set.of());

      assertNotNull(result);
      assertEquals("survival", result.name());
    }

    @Test
    @DisplayName("should return null for empty fallback list")
    void emptyFallbackList() {
      ServerRegistry registry = registry(List.of());
      BackendServer result = registry.nextFallback(Set.of());

      assertNull(result);
    }

    @Test
    @DisplayName("should skip multiple excluded servers at the beginning")
    void skipsMultipleExcludedAtBeginning() {
      ServerRegistry registry = registry(List.of("lobby", "survival", "creative"));
      BackendServer result = registry.nextFallback(Set.of("lobby", "survival"));

      assertNotNull(result);
      assertEquals("creative", result.name());
    }

    @Test
    @DisplayName("should skip fallback servers that are not registered")
    void skipsUnregisteredFallbacks() {
      ServerRegistry registry = registry(List.of("lobby", "survival"));
      registry.unregister("lobby");

      BackendServer result = registry.nextFallback(Set.of());

      assertNotNull(result);
      assertEquals("survival", result.name());
    }

    @Test
    @DisplayName("should use a fallback server registered after startup")
    void usesFallbackRegisteredLater() {
      ServerRegistry registry =
          new ServerRegistry(Map.of("lobby", ADDR_1), "lobby", List.of("lobby", "hub"));
      registry.register(new ServerInfo("hub", ADDR_2));

      BackendServer result = registry.nextFallback(Set.of("lobby"));

      assertNotNull(result);
      assertEquals("hub", result.name());
    }
  }

  @Nested
  @DisplayName("construction")
  class Construction {

    @Test
    @DisplayName("should accept no static server, for networks that register servers at runtime")
    void acceptsNoServers() {
      ServerRegistry registry = new ServerRegistry(Map.of(), null, List.of());

      assertTrue(registry.allServers().isEmpty());
      assertNull(registry.initialServer());
    }

    @Test
    @DisplayName("should reject a default server that is not among the static servers")
    void rejectsUnknownDefault() {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ServerRegistry(Map.of("lobby", ADDR_1), "hub", List.of()));
    }

    @Test
    @DisplayName("should store names in lowercase")
    void lowercasesNames() {
      ServerRegistry registry = new ServerRegistry(Map.of("Lobby", ADDR_1), "LOBBY", List.of());

      BackendServer lobby = registry.getServer("lObBy");

      assertNotNull(lobby);
      assertEquals("lobby", lobby.name());
    }
  }

  @Nested
  @DisplayName("register()")
  class Register {

    @Test
    @DisplayName("should make a new server reachable by name")
    void registersNewServer() {
      ServerRegistry registry = new ServerRegistry(Map.of(), null, List.of());

      BackendServer registered = registry.register(new ServerInfo("Game-1", ADDR_1));

      assertSame(registered, registry.getServer("game-1"));
      assertEquals("game-1", registered.name());
      assertEquals(ADDR_1, registered.address());
    }

    @Test
    @DisplayName("should return the existing server when registered again at the same address")
    void isIdempotent() {
      ServerRegistry registry = new ServerRegistry(Map.of(), null, List.of());
      BackendServer first = registry.register(new ServerInfo("game-1", ADDR_1));

      BackendServer second = registry.register(new ServerInfo("GAME-1", ADDR_1));

      assertSame(first, second);
    }

    @Test
    @DisplayName("should refuse another address under a registered name")
    void refusesConflictingAddress() {
      ServerRegistry registry = new ServerRegistry(Map.of(), null, List.of());
      registry.register(new ServerInfo("game-1", ADDR_1));

      assertThrows(
          IllegalArgumentException.class,
          () -> registry.register(new ServerInfo("game-1", ADDR_2)));
    }

    @Test
    @DisplayName("should register a server exactly once when discovery paths race")
    void registersOnceUnderContention() throws InterruptedException {
      ServerRegistry registry = new ServerRegistry(Map.of(), null, List.of());
      int threads = 8;
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      CountDownLatch start = new CountDownLatch(1);
      ConcurrentLinkedQueue<BackendServer> results = new ConcurrentLinkedQueue<>();
      try {
        for (int i = 0; i < threads; i++) {
          pool.execute(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                results.add(registry.register(new ServerInfo("game-1", ADDR_1)));
              });
        }
        start.countDown();
      } finally {
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
      }

      assertEquals(threads, results.size());
      BackendServer only = registry.getServer("game-1");
      assertTrue(results.stream().allMatch(server -> server == only));
    }
  }

  @Nested
  @DisplayName("unregister()")
  class Unregister {

    @Test
    @DisplayName("should remove a server by name and return it")
    void removesByName() {
      ServerRegistry registry = registry(List.of("lobby"));
      BackendServer survival = registry.getServer("survival");

      BackendServer removed = registry.unregister("SURVIVAL");

      assertSame(survival, removed);
      assertNull(registry.getServer("survival"));
    }

    @Test
    @DisplayName("should not remove a server registered again since the instance was taken")
    void ignoresStaleInstance() {
      ServerRegistry registry = new ServerRegistry(Map.of(), null, List.of());
      BackendServer stale = registry.register(new ServerInfo("game-1", ADDR_1));
      registry.unregister("game-1");
      BackendServer fresh = registry.register(new ServerInfo("game-1", ADDR_2));

      boolean removed = registry.unregister(stale);

      assertFalse(removed);
      assertSame(fresh, registry.getServer("game-1"));
    }

    @Test
    @DisplayName("should return null when no server has the name")
    void unknownName() {
      ServerRegistry registry = new ServerRegistry(Map.of(), null, List.of());

      assertNull(registry.unregister("nope"));
    }
  }

  @Nested
  @DisplayName("lookups")
  class Lookups {

    @Test
    @DisplayName("should list servers sorted by name")
    void listsSorted() {
      ServerRegistry registry = registry(List.of("lobby"));

      List<String> names = registry.allServers().stream().map(BackendServer::name).toList();

      assertEquals(List.of("creative", "lobby", "survival"), names);
    }

    @Test
    @DisplayName("should find a registered server only for its exact name and address")
    void registeredMatchesAddress() {
      ServerRegistry registry = registry(List.of("lobby"));

      assertNotNull(registry.registered(new ServerInfo("lobby", ADDR_1)));
      assertNull(registry.registered(new ServerInfo("lobby", ADDR_2)));
      assertNull(registry.registered(new ServerInfo("unknown", ADDR_1)));
    }

    @Test
    @DisplayName("should pick the default server first when it is registered")
    void initialIsDefault() {
      ServerRegistry registry = registry(List.of("survival"));

      BackendServer initial = registry.initialServer();

      assertNotNull(initial);
      assertEquals("lobby", initial.name());
    }

    @Test
    @DisplayName("should pick the first registered fallback when the default server is gone")
    void initialFallsBackWhenDefaultGone() {
      ServerRegistry registry = registry(List.of("lobby", "survival"));
      registry.unregister("lobby");

      BackendServer initial = registry.initialServer();

      assertNotNull(initial);
      assertEquals("survival", initial.name());
    }
  }

  private static ServerRegistry registry(List<String> fallbackOrder) {
    Map<String, InetSocketAddress> servers =
        Map.of("lobby", ADDR_1, "survival", ADDR_2, "creative", ADDR_3);
    return new ServerRegistry(servers, "lobby", fallbackOrder);
  }
}
