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
package dev.warp.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.ConfigurationException.Problem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("groups and routes configuration")
class RoutingConfigTest {

  private static final String SERVERS =
      "servers {\n lobby1 { address = \"localhost:25565\" }\n lobby2 { address ="
          + " \"localhost:25566\" }\n survival { address = \"localhost:25567\" }\n}\n"
          + "default-server = \"lobby1\"\n";

  @TempDir private Path directory;

  private WarpConfig load(String content) throws Exception {
    Files.writeString(directory.resolve("warp.conf"), content, StandardCharsets.UTF_8);
    return WarpConfig.load(directory);
  }

  private Problem only(String content) {
    ConfigurationException error = assertThrows(ConfigurationException.class, () -> load(content));
    List<Problem> problems = error.problems();
    assertEquals(1, problems.size(), problems.toString());
    return problems.getFirst();
  }

  private static void assertContains(String expected, Problem problem) {
    assertTrue(
        problem.description().contains(expected),
        "expected '" + expected + "' in: " + problem.description());
  }

  @Nested
  @DisplayName("groups")
  class Groups {

    @Test
    @DisplayName("should read a group with the default strategy")
    void readsGroup() throws Exception {
      WarpConfig config =
          load(SERVERS + "groups { Lobbies { servers = [\"Lobby1\", \"lobby2\"] } }");

      GroupConfig group = Objects.requireNonNull(config.groups().get("lobbies"));

      assertEquals(List.of("lobby1", "lobby2"), group.servers());
      assertEquals("least-connections", group.strategy());
      assertEquals(1, group.weight("lobby1"));
      assertEquals(0, group.capacity());
    }

    @Test
    @DisplayName("should read weights and a capacity with the strategies that use them")
    void readsWeightsAndCapacity() throws Exception {
      WarpConfig config =
          load(
              SERVERS
                  + "groups {\n"
                  + "  heavy { servers = [\"lobby1\", \"lobby2\"], strategy = \"Weighted\","
                  + " weights { lobby1 = 3 } }\n"
                  + "  filled { servers = [\"lobby1\", \"lobby2\"], strategy = \"fill\","
                  + " capacity = 50 }\n"
                  + "}");

      assertEquals(3, Objects.requireNonNull(config.groups().get("heavy")).weight("lobby1"));
      assertEquals(1, Objects.requireNonNull(config.groups().get("heavy")).weight("lobby2"));
      assertEquals("weighted", Objects.requireNonNull(config.groups().get("heavy")).strategy());
      assertEquals(50, Objects.requireNonNull(config.groups().get("filled")).capacity());
    }

    @Test
    @DisplayName("should let a group be the default server and a fallback")
    void groupAsDefaultAndFallback() throws Exception {
      WarpConfig config =
          load(
              "servers {\n lobby1 { address = \"localhost:25565\" }\n lobby2 { address ="
                  + " \"localhost:25566\" }\n}\n"
                  + "default-server = \"lobbies\"\n"
                  + "fallback-order = [\"lobbies\", \"lobby1\"]\n"
                  + "groups { lobbies { servers = [\"lobby1\", \"lobby2\"] } }");

      assertEquals("lobbies", config.defaultServer());
      assertEquals(List.of("lobbies", "lobby1"), config.fallbackOrder());
    }

    @Test
    @DisplayName("should suggest the closest server for a misspelt member")
    void unknownMember() {
      Problem problem = only(SERVERS + "groups { lobbies { servers = [\"lobby1\", \"loby2\"] } }");

      assertEquals("groups.lobbies.servers", problem.key());
      assertContains("Did you mean 'lobby2'?", problem);
    }

    @Test
    @DisplayName("should suggest the closest strategy for a misspelt one")
    void unknownStrategy() {
      Problem problem =
          only(
              SERVERS
                  + "groups { lobbies { servers = [\"lobby1\"], strategy = \"round_robin\" } }");

      assertEquals("groups.lobbies.strategy", problem.key());
      assertContains("Did you mean 'round-robin'?", problem);
    }

    @Test
    @DisplayName("should refuse a group named like a server, an empty group, and a repeated member")
    void badGroups() {
      assertContains(
          "also the name of a server",
          only(SERVERS + "groups { lobby1 { servers = [\"lobby2\"] } }"));
      assertContains("at least one server", only(SERVERS + "groups { lobbies { servers = [] } }"));
      assertContains("missing", only(SERVERS + "groups { lobbies { strategy = \"random\" } }"));
      assertContains(
          "twice", only(SERVERS + "groups { lobbies { servers = [\"lobby1\", \"LOBBY1\"] } }"));
    }

    @Test
    @DisplayName("should tie weights to the weighted strategy and the capacity to fill")
    void settingsMatchStrategy() {
      assertContains(
          "only the weighted strategy uses weights",
          only(SERVERS + "groups { g { servers = [\"lobby1\"], weights { lobby1 = 2 } } }"));
      assertContains(
          "Did you mean 'lobby1'?",
          only(
              SERVERS
                  + "groups { g { servers = [\"lobby1\"], strategy = \"weighted\", weights {"
                  + " loby1 = 2 } } }"));
      assertContains(
          "a weight of 1 or more",
          only(
              SERVERS
                  + "groups { g { servers = [\"lobby1\"], strategy = \"weighted\", weights {"
                  + " lobby1 = 0 } } }"));
      assertContains(
          "missing",
          only(SERVERS + "groups { g { servers = [\"lobby1\"], strategy = \"fill\" } }"));
      assertContains(
          "only the fill strategy uses a capacity",
          only(SERVERS + "groups { g { servers = [\"lobby1\"], capacity = 10 } }"));
    }

    @Test
    @DisplayName("should point at a typo in a group's keys")
    void unknownKey() {
      Problem problem =
          only(SERVERS + "groups { g { servers = [\"lobby1\"], stratgy = \"random\" } }");

      assertContains("Did you mean groups.g.strategy?", problem);
    }

    @Test
    @DisplayName("should accept members that plugins register at runtime when servers is empty")
    void runtimeMembers() throws Exception {
      WarpConfig config =
          load(
              "servers {}\n"
                  + "default-server = \"lobbies\"\n"
                  + "groups { lobbies { servers = [\"lobby1\", \"lobby2\"] } }");

      assertEquals("lobbies", config.defaultServer());
      assertEquals(
          List.of("lobby1", "lobby2"),
          Objects.requireNonNull(config.groups().get("lobbies")).servers());
    }
  }

  @Nested
  @DisplayName("routes")
  class Routes {

    @Test
    @DisplayName("should read routes to servers, groups and captures, normalized")
    void readsRoutes() throws Exception {
      WarpConfig config =
          load(
              SERVERS
                  + "groups { lobbies { servers = [\"lobby1\", \"lobby2\"] } }\n"
                  + "routes {\n"
                  + "  \"Play.Example.com.\" = \"Lobbies\"\n"
                  + "  \"survival.example.com:25566\" = \"survival\"\n"
                  + "  \"*.mini.example.com\" = \"$1\"\n"
                  + "}");

      assertEquals(
          Map.of(
              "play.example.com", "lobbies",
              "survival.example.com:25566", "survival",
              "*.mini.example.com", "$1"),
          config.routes());
    }

    @Test
    @DisplayName("should suggest the closest server or group for a misspelt target")
    void unknownTarget() {
      Problem problem =
          only(
              SERVERS
                  + "groups { lobbies { servers = [\"lobby1\"] } }\n"
                  + "routes { \"play.example.com\" = \"lobies\" }");

      assertEquals("routes.\"play.example.com\"", problem.key());
      assertContains("Did you mean 'lobbies'?", problem);
    }

    @Test
    @DisplayName("should refuse a capture without a wildcard, a bad host and a repeated host")
    void badRoutes() {
      assertContains(
          "use a wildcard host", only(SERVERS + "routes { \"play.example.com\" = \"$1\" }"));
      assertContains(
          "not a port", only(SERVERS + "routes { \"play.example.com:abc\" = \"lobby1\" }"));
      assertContains("first label", only(SERVERS + "routes { \"play.*.com\" = \"lobby1\" }"));
      assertContains(
          "same host",
          only(
              SERVERS
                  + "routes { \"play.example.com\" = \"lobby1\", \"PLAY.example.com.\" ="
                  + " \"lobby2\" }"));
      assertContains("empty value", only(SERVERS + "routes { \"play.example.com\" = \"\" }"));
    }

    @Test
    @DisplayName("should accept targets that plugins register at runtime when servers is empty")
    void runtimeTargets() throws Exception {
      WarpConfig config = load("servers {}\nroutes { \"play.example.com\" = \"anything\" }");

      assertEquals(Map.of("play.example.com", "anything"), config.routes());
    }

    @Test
    @DisplayName("should have no route and no group by default")
    void defaults() throws Exception {
      WarpConfig config = load("");

      assertTrue(config.routes().isEmpty());
      assertTrue(config.groups().isEmpty());
    }
  }
}
