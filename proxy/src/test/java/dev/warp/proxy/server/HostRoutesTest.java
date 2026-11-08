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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.server.Route;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("HostRoutes")
class HostRoutesTest {

  private static final int PORT = 25565;

  private static HostRoutes routes(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return HostRoutes.compile(map);
  }

  private static String target(HostRoutes routes, String host) {
    Route route = routes.match(HostRoutes.clean(host), PORT);
    return route == null ? "<default>" : route.target();
  }

  @Nested
  @DisplayName("clean()")
  class Clean {

    @Test
    @DisplayName("should lowercase and strip a trailing dot")
    void lowercaseAndDot() {
      assertEquals("play.example.com", HostRoutes.clean("Play.Example.COM."));
    }

    @Test
    @DisplayName("should cut the Forge markers and a BungeeCord payload")
    void forgeAndBungee() {
      assertEquals("play.example.com", HostRoutes.clean("play.example.com\0FML\0"));
      assertEquals("play.example.com", HostRoutes.clean("play.example.com\0FML2\0"));
      assertEquals("play.example.com", HostRoutes.clean("play.example.com\0FML3\0"));
      assertEquals(
          "play.example.com",
          HostRoutes.clean("play.example.com\0" + "1.2.3.4\0" + "uuid\0" + "[]"));
    }

    @Test
    @DisplayName("should strip a port but keep an IPv6 literal")
    void port() {
      assertEquals("play.example.com", HostRoutes.clean("play.example.com:25566"));
      assertEquals("::1", HostRoutes.clean("::1"));
      assertEquals("play.example.com:abc", HostRoutes.clean("play.example.com:abc"));
    }

    @Test
    @DisplayName("should return an empty host for an empty or suffix-only address")
    void empty() {
      assertEquals("", HostRoutes.clean(""));
      assertEquals("", HostRoutes.clean("\0FML\0"));
      assertEquals("", HostRoutes.clean("..."));
    }
  }

  @Nested
  @DisplayName("match()")
  class Match {

    @Test
    @DisplayName("should match an exact host whatever its case, dot or suffix")
    void exact() {
      HostRoutes routes = routes("Play.Example.com", "Lobby");

      assertEquals("lobby", target(routes, "play.example.com"));
      assertEquals("lobby", target(routes, "PLAY.EXAMPLE.COM."));
      assertEquals("lobby", target(routes, "play.example.com\0FML2\0"));
      assertEquals("lobby", target(routes, "play.example.com:25565"));
      assertEquals("<default>", target(routes, "other.example.com"));
    }

    @Test
    @DisplayName("should match a wildcard over one or more labels, never the bare suffix")
    void wildcard() {
      HostRoutes routes = routes("*.example.com", "lobby");

      assertEquals("lobby", target(routes, "a.example.com"));
      assertEquals("lobby", target(routes, "a.b.example.com"));
      assertEquals("<default>", target(routes, "example.com"));
      assertEquals("<default>", target(routes, "notexample.com"));
    }

    @Test
    @DisplayName("should substitute the capture in the target")
    void capture() {
      HostRoutes routes = routes("*.play.example.com", "$1", "*.example.com", "mini-$1");

      assertEquals("kitpvp", target(routes, "KitPvP.play.example.com"));
      assertEquals("mini-skywars", target(routes, "skywars.example.com"));
      assertEquals("a.b", target(routes, "a.b.play.example.com"));
    }

    @Test
    @DisplayName("should prefer the exact host, then the wildcard with more labels")
    void specificity() {
      HostRoutes routes =
          routes(
              "*", "catchall",
              "*.example.com", "wide",
              "*.play.example.com", "narrow",
              "hub.play.example.com", "exact");

      assertEquals("exact", target(routes, "hub.play.example.com"));
      assertEquals("narrow", target(routes, "kit.play.example.com"));
      assertEquals("wide", target(routes, "kit.example.com"));
      assertEquals("catchall", target(routes, "somewhere.else"));
    }

    @Test
    @DisplayName("should match a port only when the client used it, and prefer it")
    void port() {
      HostRoutes routes = routes("play.example.com:25566", "creative", "play.example.com", "lobby");

      assertEquals("lobby", target(routes, "play.example.com"));
      Route onPort = routes.match("play.example.com", 25566);
      assertNotNull(onPort);
      assertEquals("creative", onPort.target());
      assertEquals("play.example.com:25566", onPort.host());
    }

    @Test
    @DisplayName("should be deterministic regardless of the order the entries were written in")
    void deterministic() {
      HostRoutes a = routes("*.example.com", "wide", "*.play.example.com", "narrow");
      HostRoutes b = routes("*.play.example.com", "narrow", "*.example.com", "wide");

      assertEquals(target(a, "x.play.example.com"), target(b, "x.play.example.com"));
      assertEquals(a.entries(), b.entries());
    }

    @Test
    @DisplayName("should report the matched entry as configured")
    void reportsHost() {
      HostRoutes routes = routes("*.Example.com.", "lobby");

      Route route = routes.match("a.example.com", PORT);

      assertNotNull(route);
      assertEquals("*.example.com", route.host());
    }

    @Test
    @DisplayName("should match nothing for an empty host or without entries")
    void nothing() {
      assertNull(HostRoutes.EMPTY.match("play.example.com", PORT));
      assertNull(routes("*", "x").match("", PORT));
      assertTrue(HostRoutes.EMPTY.isEmpty());
    }
  }

  @Nested
  @DisplayName("compile()")
  class Compile {

    @Test
    @DisplayName("should reject a capture without a wildcard")
    void captureNeedsWildcard() {
      assertThrows(IllegalArgumentException.class, () -> routes("play.example.com", "$1"));
    }

    @Test
    @DisplayName("should reject two keys that are the same host")
    void duplicates() {
      assertThrows(
          IllegalArgumentException.class,
          () -> routes("play.example.com", "a", "PLAY.example.com.", "b"));
    }

    @Test
    @DisplayName("should reject a star elsewhere than as the first label, a bad port or no host")
    void badKeys() {
      assertThrows(IllegalArgumentException.class, () -> HostRoutes.parseKey("play.*.com"));
      assertThrows(IllegalArgumentException.class, () -> HostRoutes.parseKey("*play.com"));
      assertThrows(IllegalArgumentException.class, () -> HostRoutes.parseKey("play.com:0"));
      assertThrows(IllegalArgumentException.class, () -> HostRoutes.parseKey("play.com:abc"));
      assertThrows(IllegalArgumentException.class, () -> HostRoutes.parseKey("play..com"));
      assertThrows(IllegalArgumentException.class, () -> HostRoutes.parseKey(":25565"));
      assertThrows(IllegalArgumentException.class, () -> HostRoutes.parseKey("  "));
    }
  }
}
