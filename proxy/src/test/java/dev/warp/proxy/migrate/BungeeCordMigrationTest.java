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
package dev.warp.proxy.migrate;

import static dev.warp.proxy.migrate.MigrationSupport.assertAccountsFor;
import static dev.warp.proxy.migrate.MigrationSupport.assertLoads;
import static dev.warp.proxy.migrate.MigrationSupport.dropped;
import static dev.warp.proxy.migrate.MigrationSupport.migrated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.ForwardingMode;
import dev.warp.proxy.config.WarpConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("BungeeCordMigration")
class BungeeCordMigrationTest {

  @TempDir private Path source;
  @TempDir private Path target;

  private final BungeeCordMigration migration = new BungeeCordMigration();

  private Migration.Result migrate(String yaml) throws Exception {
    Files.writeString(source.resolve("config.yml"), yaml, StandardCharsets.UTF_8);
    return migration.migrate(source);
  }

  @Nested
  @DisplayName("the default config.yml")
  class DefaultFile {

    @Test
    @DisplayName("should produce a warp.conf Warp loads, with every setting accounted for")
    void loadsAndAccountsForEverything() throws Exception {
      Migration.Result result = migrate(MigrationSupport.resource("config.yml"));

      WarpConfig config = assertLoads(target, result.warpConf());
      assertEquals(25577, config.bind().getPort());
      assertEquals(List.of("lobby"), config.fallbackOrder());
      assertEquals(2, config.servers().size());
      assertEquals(ForwardingMode.NONE, config.forwardingMode());
      assertEquals(1, config.status().maxPlayers());
      assertEquals(256, config.compressionThreshold());
      assertNull(result.forwardingSecret());

      // Every top-level key, and every key of the listener, which the report prefixes.
      Report report = result.report();
      Source whole = new Source(migration.read(source.resolve("config.yml")));
      List<String> keys = whole.unconsumed().stream().filter(k -> !k.equals("listeners")).toList();
      assertAccountsFor(report, keys);
      Map<String, Object> listener = whole.tables("listeners").orElseThrow().getFirst();
      assertAccountsFor(
          report,
          new Source(listener).unconsumed().stream().map(k -> "listeners[0]." + k).toList());
    }

    @Test
    @DisplayName("should map each key to the documented Warp key")
    void mapping() throws Exception {
      Report report = migrate(MigrationSupport.resource("config.yml")).report();

      assertEquals("bind", migrated(report, "listeners[0].host").target());
      assertEquals("status.motd", migrated(report, "listeners[0].motd").target());
      assertEquals("status.max-players", migrated(report, "listeners[0].max_players").target());
      assertEquals(
          "default-server, fallback-order", migrated(report, "listeners[0].priorities").target());
      assertEquals(
          "routes.\"pvp.md-5.net\"",
          migrated(report, "listeners[0].forced_hosts.\"pvp.md-5.net\"").target());
      assertEquals(
          "proxy-protocol.enabled", migrated(report, "listeners[0].proxy_protocol").target());
      assertEquals("online-mode", migrated(report, "online_mode").target());
      assertEquals("forwarding.mode", migrated(report, "ip_forward").target());
      assertEquals("player-limit", migrated(report, "player_limit").target());
      assertEquals(
          "compression.threshold", migrated(report, "network_compression_threshold").target());
      assertEquals("timeouts.backend-read", migrated(report, "timeout").target());
      assertEquals("timeouts.connect", migrated(report, "server_connect_timeout").target());
      assertEquals("logging.pings", migrated(report, "log_pings").target());
      assertEquals("logging.commands", migrated(report, "log_commands").target());
      assertEquals("limits.connections.interval", migrated(report, "connection_throttle").target());
      assertEquals(
          "limits.connections.burst", migrated(report, "connection_throttle_limit").target());
      assertEquals("servers.pvp.address", migrated(report, "servers.pvp.address").target());

      dropped(report, "permissions.admin");
      dropped(report, "groups.md_5");
      dropped(report, "stats");
      dropped(report, "remote_ping_cache");
      dropped(report, "listeners[0].tab_list");
      dropped(report, "listeners[0].query_port");
      dropped(report, "servers.lobby.motd");
      assertTrue(dropped(report, "enforce_secure_profile").reason().startsWith(Report.ALWAYS));
    }

    @Test
    @DisplayName("should convert the legacy motd to MiniMessage")
    void motd() throws Exception {
      String conf = migrate(MigrationSupport.resource("config.yml")).warpConf();

      assertTrue(conf.contains("motd = \"<dark_blue>Another Bungee server\""), conf);
      assertTrue(conf.contains("burst = 3"), conf);
      assertTrue(conf.contains("interval = 4s"), conf);
    }
  }

  @Nested
  @DisplayName("other installations")
  class Others {

    @Test
    @DisplayName("should turn ip_forward into modern forwarding, with a warning")
    void ipForward() throws Exception {
      Migration.Result result =
          migrate(
              "ip_forward: true\nservers:\n  hub:\n    address: a:1\nlisteners:\n- host: 0.0.0.0:25565\n"
                  + "  priorities:\n  - hub\n");

      WarpConfig config = assertLoads(target, result.warpConf());
      assertEquals(ForwardingMode.VELOCITY, config.forwardingMode());
      assertTrue(result.report().warnings().stream().anyMatch(w -> w.contains("spoof")));
      assertTrue(result.report().nextSteps().contains(Shared.BACKENDS_STEP));
    }

    @Test
    @DisplayName("should migrate the first listener and warn about the others")
    void listeners() throws Exception {
      Migration.Result result =
          migrate(
              "servers:\n  hub:\n    address: a:1\nlisteners:\n- host: 0.0.0.0:1\n  motd: \"one\\ntwo\"\n"
                  + "- host: 0.0.0.0:2\n");

      WarpConfig config = assertLoads(target, result.warpConf());
      assertEquals(1, config.bind().getPort());
      assertTrue(result.warpConf().contains("motd = [\"one\", \"two\"]"), result.warpConf());
      assertTrue(result.report().warnings().stream().anyMatch(w -> w.contains("2 listeners")));
    }

    @Test
    @DisplayName("should mention waterfall.yml and Waterfall's own keys")
    void waterfall() throws Exception {
      Files.writeString(source.resolve("waterfall.yml"), "x: 1\n", StandardCharsets.UTF_8);

      Migration.Result result =
          migrate("max_packets_per_second: 4096\nservers:\n  hub:\n    address: a:1\n");

      assertLoads(target, result.warpConf());
      dropped(result.report(), "max_packets_per_second");
      assertTrue(result.report().warnings().stream().anyMatch(w -> w.contains("waterfall.yml")));
    }

    @Test
    @DisplayName("should refuse a missing or invalid file")
    void invalid() throws Exception {
      assertThrows(MigrationException.class, () -> migration.migrate(source));
      assertThrows(MigrationException.class, () -> migrate("- just\n- a list\n"));
      assertThrows(MigrationException.class, () -> migrate("a: [unclosed\n"));
    }
  }
}
