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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.warp.api.server.ServerInfo;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
      ServerInfo result = registry.nextFallback(Set.of());

      assertNotNull(result);
      assertEquals("lobby", result.name());
    }

    @Test
    @DisplayName("should skip excluded servers and return next available")
    void skipsExcludedServers() {
      ServerRegistry registry = registry(List.of("lobby", "survival", "creative"));
      ServerInfo result = registry.nextFallback(Set.of("lobby"));

      assertNotNull(result);
      assertEquals("survival", result.name());
    }

    @Test
    @DisplayName("should return null when all fallback servers are excluded")
    void returnsNullWhenAllExcluded() {
      ServerRegistry registry = registry(List.of("lobby", "survival"));
      ServerInfo result = registry.nextFallback(Set.of("lobby", "survival"));

      assertNull(result);
    }

    @Test
    @DisplayName("should respect fallback order")
    void respectsOrder() {
      ServerRegistry registry = registry(List.of("survival", "lobby"));
      ServerInfo result = registry.nextFallback(Set.of());

      assertNotNull(result);
      assertEquals("survival", result.name());
    }

    @Test
    @DisplayName("should return null for empty fallback list")
    void emptyFallbackList() {
      ServerRegistry registry = registry(List.of());
      ServerInfo result = registry.nextFallback(Set.of());

      assertNull(result);
    }

    @Test
    @DisplayName("should skip multiple excluded servers at the beginning")
    void skipsMultipleExcludedAtBeginning() {
      ServerRegistry registry = registry(List.of("lobby", "survival", "creative"));
      ServerInfo result = registry.nextFallback(Set.of("lobby", "survival"));

      assertNotNull(result);
      assertEquals("creative", result.name());
    }
  }

  private static ServerRegistry registry(List<String> fallbackOrder) {
    Map<String, InetSocketAddress> servers =
        Map.of("lobby", ADDR_1, "survival", ADDR_2, "creative", ADDR_3);
    return new ServerRegistry(servers, "lobby", fallbackOrder);
  }
}
