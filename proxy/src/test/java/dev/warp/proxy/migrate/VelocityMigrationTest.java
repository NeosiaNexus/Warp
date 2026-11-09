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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.ForwardingMode;
import dev.warp.proxy.config.WarpConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("VelocityMigration")
class VelocityMigrationTest {

  @TempDir private Path source;
  @TempDir private Path target;

  private final VelocityMigration migration = new VelocityMigration();

  private Migration.Result migrate(String toml) throws Exception {
    Files.writeString(source.resolve("velocity.toml"), toml, StandardCharsets.UTF_8);
    return migration.migrate(source);
  }

  @Nested
  @DisplayName("the default velocity.toml")
  class DefaultFile {

    @Test
    @DisplayName("should produce a warp.conf Warp loads, with every setting accounted for")
    void loadsAndAccountsForEverything() throws Exception {
      String toml = MigrationSupport.resource("velocity.toml");
      Files.writeString(source.resolve("forwarding.secret"), "s3cret\n", StandardCharsets.UTF_8);

      Migration.Result result = migrate(toml);

      WarpConfig config = assertLoads(target, result.warpConf());
      assertEquals(25565, config.bind().getPort());
      assertEquals(List.of("lobby"), config.fallbackOrder());
      assertEquals("lobby", config.defaultServer());
      assertEquals(3, config.servers().size());
      assertEquals(256, config.compressionThreshold());
      assertEquals(ForwardingMode.NONE, config.forwardingMode());
      assertEquals(500, config.status().maxPlayers());
      assertAccountsFor(result.report(), new Source(Toml.parse(toml)).unconsumed());
      assertEquals("s3cret", result.forwardingSecret());
    }

    @Test
    @DisplayName("should map each key to the documented Warp key")
    void mapping() throws Exception {
      Migration.Result result = migrate(MigrationSupport.resource("velocity.toml"));
      Report report = result.report();

      assertEquals("bind", migrated(report, "bind").target());
      assertEquals("status.motd", migrated(report, "motd").target());
      assertEquals("status.max-players", migrated(report, "show-max-players").target());
      assertEquals("online-mode", migrated(report, "online-mode").target());
      assertEquals("forwarding.mode", migrated(report, "player-info-forwarding-mode").target());
      assertEquals("duplicate-login", migrated(report, "kick-existing-players").target());
      assertEquals("status.sample-size", migrated(report, "sample-players-in-ping").target());
      assertEquals("servers.lobby.address", migrated(report, "servers.lobby").target());
      assertEquals("default-server, fallback-order", migrated(report, "servers.try").target());
      assertEquals(
          "routes.\"lobby.example.com\"",
          migrated(report, "forced-hosts.\"lobby.example.com\"").target());
      assertEquals(
          "compression.threshold", migrated(report, "advanced.compression-threshold").target());
      assertEquals("compression.level", migrated(report, "advanced.compression-level").target());
      assertEquals(
          "limits.logins.burst, limits.logins.interval",
          migrated(report, "advanced.login-ratelimit").target());
      assertEquals("timeouts.connect", migrated(report, "advanced.connection-timeout").target());
      assertEquals("timeouts.backend-read", migrated(report, "advanced.read-timeout").target());
      assertEquals(
          "proxy-protocol.enabled", migrated(report, "advanced.haproxy-protocol").target());
      assertEquals("tcp-fast-open", migrated(report, "advanced.tcp-fast-open").target());
      assertEquals(
          "bungeecord-channel.enabled",
          migrated(report, "advanced.bungee-plugin-message-channel").target());
      assertEquals("logging.pings", migrated(report, "advanced.show-ping-requests").target());
      assertEquals(
          "logging.commands", migrated(report, "advanced.log-command-executions").target());
      assertEquals(
          "logging.connections", migrated(report, "advanced.log-player-connections").target());
      assertEquals("accepts-transfers", migrated(report, "advanced.accepts-transfers").target());

      assertEquals(
          Report.ALWAYS + ": a player a server drops follows fallback-order",
          dropped(report, "advanced.failover-on-unexpected-server-disconnect").reason());
      assertTrue(dropped(report, "force-key-authentication").reason().startsWith(Report.ALWAYS));
      dropped(report, "query.enabled");
      dropped(report, "ping-passthrough.modinfo");
      dropped(report, "packet-limiter.interval");
      dropped(report, "advanced.command-rate-limit");
      dropped(report, "config-version");
      assertTrue(report.nextSteps().stream().anyMatch(s -> s.contains("plugins/")));
    }

    @Test
    @DisplayName("should write the origin of each value as a comment")
    void comments() throws Exception {
      String conf = migrate(MigrationSupport.resource("velocity.toml")).warpConf();

      assertTrue(
          conf.contains("# velocity.toml: bind = \"0.0.0.0:25565\"\nbind = \"0.0.0.0:25565\""),
          conf);
      assertTrue(conf.contains("routes {\n"), conf);
      assertTrue(conf.contains("\"lobby.example.com\" = \"lobby\""), conf);
      assertTrue(conf.contains("interval = 3s"), conf);
      assertTrue(conf.contains("connect = 5s"), conf);
      assertTrue(conf.contains("sample-size = 0"), conf);
      assertTrue(conf.contains("duplicate-login = \"refuse-new\""), conf);
    }
  }

  @Nested
  @DisplayName("other installations")
  class Others {

    @Test
    @DisplayName("should turn modern forwarding and its secret into Warp's")
    void modern() throws Exception {
      Files.writeString(source.resolve("my.secret"), "  abc123\n", StandardCharsets.UTF_8);

      Migration.Result result =
          migrate(
              "player-info-forwarding-mode = \"modern\"\nforwarding-secret-file = \"my.secret\"\n"
                  + "[servers]\nhub = \"10.0.0.1:25565\"\ntry = [\"hub\"]\n");

      assertEquals("abc123", result.forwardingSecret());
      WarpConfig config = assertLoads(target, result.warpConf());
      assertEquals(ForwardingMode.VELOCITY, config.forwardingMode());
      assertTrue(result.report().nextSteps().contains(Shared.BACKENDS_STEP));
      assertTrue(result.report().warnings().isEmpty(), result.report().warnings().toString());
    }

    @Test
    @DisplayName("should warn that legacy forwarding becomes modern forwarding")
    void legacy() throws Exception {
      Migration.Result result =
          migrate(
              "player-info-forwarding-mode = \"legacy\"\n[servers]\nhub = \"a:1\"\ntry = [\"hub\"]\n");

      WarpConfig config = assertLoads(target, result.warpConf());
      assertEquals(ForwardingMode.VELOCITY, config.forwardingMode());
      assertNull(result.forwardingSecret());
      assertTrue(result.report().warnings().stream().anyMatch(w -> w.contains("spoof")));
      assertTrue(result.report().warnings().stream().anyMatch(w -> w.contains("generates one")));
    }

    @Test
    @DisplayName("should read the inline secret and legacy motd of a Velocity 1.x file")
    void velocity1() throws Exception {
      Migration.Result result =
          migrate(
              "config-version = \"1.0\"\nmotd = \"&3A &lVelocity &rServer\"\n"
                  + "player-info-forwarding-mode = \"MODERN\"\nforwarding-secret = \"old\"\n"
                  + "ping-passthrough = \"DISABLED\"\n[servers]\nhub = \"a:1\"\ntry = [\"hub\"]\n"
                  + "[metrics]\nenabled = true\nid = \"x\"\n");

      assertEquals("old", result.forwardingSecret());
      WarpConfig config = assertLoads(target, result.warpConf());
      assertEquals(ForwardingMode.VELOCITY, config.forwardingMode());
      assertTrue(
          result
              .warpConf()
              .contains("motd = \"<dark_aqua>A <bold>Velocity </bold></dark_aqua>Server\""),
          result.warpConf());
      dropped(result.report(), "ping-passthrough");
      dropped(result.report(), "metrics.enabled");
      dropped(result.report(), "metrics.id");
    }

    @Test
    @DisplayName("should leave out a forced host or fallback that names a missing server")
    void missingServer() throws Exception {
      Migration.Result result =
          migrate(
              "[servers]\nlobby = \"a:1\"\ntry = [\"gone\", \"lobby\"]\n"
                  + "[forced-hosts]\n\"pvp.example.com\" = [\"gone\"]\n"
                  + "\"hub.example.com\" = [\"lobby\", \"gone\"]\n");

      WarpConfig config = assertLoads(target, result.warpConf());
      assertEquals(List.of("lobby"), config.fallbackOrder());
      assertFalse(result.warpConf().contains("\"pvp.example.com\""), result.warpConf());
      assertTrue(result.warpConf().contains("\"hub.example.com\" = \"lobby\""), result.warpConf());
      dropped(result.report(), "forced-hosts.\"pvp.example.com\"");
      assertEquals(3, result.report().warnings().size(), result.report().warnings().toString());
    }

    @Test
    @DisplayName("should route a forced host with several servers to a group of them")
    void group() throws Exception {
      Migration.Result result =
          migrate(
              "[servers]\nlobby = \"a:1\"\nlobby2 = \"a:2\"\ntry = [\"lobby\"]\n"
                  + "[forced-hosts]\n\"Lobby.Example.com\" = [\"lobby\", \"lobby2\"]\n");

      assertLoads(target, result.warpConf());
      String conf = result.warpConf();
      assertTrue(conf.contains("groups {\n  lobby-example-com {\n"), conf);
      assertTrue(conf.contains("servers = [\"lobby\", \"lobby2\"]"), conf);
      assertTrue(conf.contains("strategy = \"round-robin\""), conf);
      assertTrue(conf.contains("\"lobby.example.com\" = \"lobby-example-com\""), conf);
      assertTrue(result.report().warnings().stream().anyMatch(w -> w.contains("round-robin")));
    }

    @Test
    @DisplayName("should require trusted proxies when the PROXY protocol was on")
    void haproxy() throws Exception {
      Migration.Result result =
          migrate(
              "[servers]\nhub = \"a:1\"\ntry = [\"hub\"]\n[advanced]\nhaproxy-protocol = true\n");

      assertTrue(result.warpConf().contains("trusted-proxies = []"), result.warpConf());
      assertTrue(result.report().warnings().stream().anyMatch(w -> w.contains("trusted-proxies")));
      // Warp refuses an empty list, as the warning says: the file is right apart from that.
      assertThrows(
          dev.warp.proxy.config.ConfigurationException.class,
          () -> {
            Files.writeString(target.resolve("warp.conf"), result.warpConf());
            WarpConfig.load(target);
          });
    }

    @Test
    @DisplayName("should keep an ampersand in a MiniMessage motd as it is")
    void miniMessageAmpersand() throws Exception {
      Migration.Result result =
          migrate(
              "config-version = \"2.7\"\nmotd = \"<gold>Rock&Roll Network\"\n"
                  + "[servers]\nhub = \"a:1\"\ntry = [\"hub\"]\n");

      assertLoads(target, result.warpConf());
      assertTrue(
          result.warpConf().contains("motd = \"<gold>Rock&Roll Network\""), result.warpConf());
    }

    @Test
    @DisplayName("should convert section signs in a MiniMessage motd only without tags")
    void miniMessageSection() throws Exception {
      Migration.Result plain =
          migrate(
              "config-version = \"2.7\"\nmotd = \"§6Rock&Roll\"\n"
                  + "[servers]\nhub = \"a:1\"\ntry = [\"hub\"]\n");
      Migration.Result tagged =
          migrate(
              "config-version = \"2.7\"\nmotd = \"<gold>Rock §cRoll\"\n"
                  + "[servers]\nhub = \"a:1\"\ntry = [\"hub\"]\n");

      assertTrue(plain.warpConf().contains("motd = \"<gold>Rock&Roll\""), plain.warpConf());
      assertTrue(tagged.warpConf().contains("motd = \"<gold>Rock §cRoll\""), tagged.warpConf());
    }

    @Test
    @DisplayName("should give colliding multi-server forced hosts a group each")
    void groupCollision() throws Exception {
      Migration.Result result =
          migrate(
              "[servers]\ns1 = \"a:1\"\ns2 = \"a:2\"\ns3 = \"a:3\"\ns4 = \"a:4\"\n"
                  + "try = [\"s1\"]\n[forced-hosts]\n"
                  + "\"a-b.example.com\" = [\"s1\", \"s2\"]\n"
                  + "\"a.b.example.com\" = [\"s3\", \"s4\"]\n");

      assertLoads(target, result.warpConf());
      String conf = result.warpConf();
      assertTrue(conf.contains("  a-b-example-com {\n"), conf);
      assertTrue(conf.contains("servers = [\"s1\", \"s2\"]"), conf);
      assertTrue(conf.contains("  a-b-example-com-2 {\n"), conf);
      assertTrue(conf.contains("servers = [\"s3\", \"s4\"]"), conf);
      assertTrue(conf.contains("\"a-b.example.com\" = \"a-b-example-com\""), conf);
      assertTrue(conf.contains("\"a.b.example.com\" = \"a-b-example-com-2\""), conf);
    }

    @Test
    @DisplayName("should drop timeouts Warp refuses rather than write a file it cannot load")
    void timeoutsOutOfRange() throws Exception {
      Migration.Result result =
          migrate(
              "[servers]\nhub = \"a:1\"\ntry = [\"hub\"]\n[advanced]\n"
                  + "connection-timeout = 0\nread-timeout = 500\n");
      Migration.Result negative =
          migrate(
              "[servers]\nhub = \"a:1\"\ntry = [\"hub\"]\n[advanced]\n"
                  + "connection-timeout = -1\nread-timeout = 0\n");

      WarpConfig config = assertLoads(target, result.warpConf());
      assertEquals(5, config.timeouts().connect().toSeconds());
      assertEquals(25, config.timeouts().read().toSeconds());
      assertFalse(result.warpConf().contains("timeouts"), result.warpConf());
      dropped(result.report(), "advanced.connection-timeout");
      dropped(result.report(), "advanced.read-timeout");
      assertLoads(target, negative.warpConf());
      dropped(negative.report(), "advanced.connection-timeout");
      dropped(negative.report(), "advanced.read-timeout");
    }

    @Test
    @DisplayName("should keep Warp's client read timeout when Velocity's was 30 s or more")
    void readTimeout() throws Exception {
      Migration.Result longer =
          migrate("[servers]\nhub = \"a:1\"\n[advanced]\nread-timeout = 30000\n");
      Migration.Result shorter =
          migrate("[servers]\nhub = \"a:1\"\n[advanced]\nread-timeout = 20000\n");

      assertTrue(longer.warpConf().contains("backend-read = 30s"), longer.warpConf());
      assertFalse(longer.warpConf().contains("\n  read = "), longer.warpConf());
      assertTrue(shorter.warpConf().contains("read = 20s"), shorter.warpConf());
      assertTrue(shorter.warpConf().contains("backend-read = 20s"), shorter.warpConf());
    }

    @Test
    @DisplayName("should report a value of the wrong kind and a key Velocity never had")
    void oddities() throws Exception {
      Migration.Result result =
          migrate("bind = [\"a\"]\nmystery = 1\n[servers]\nhub = \"a:1\"\n[custom]\nx = 2\n");

      assertNotNull(assertLoads(target, result.warpConf()));
      assertTrue(result.report().warnings().stream().anyMatch(w -> w.startsWith("bind is a list")));
      assertEquals(
          "not a Velocity setting Warp knows", dropped(result.report(), "mystery").reason());
      dropped(result.report(), "custom.x");
    }

    @Test
    @DisplayName("should refuse a missing or invalid file with its line")
    void invalid() {
      MigrationException missing =
          assertThrows(MigrationException.class, () -> migration.migrate(source));
      assertTrue(
          String.valueOf(missing.getMessage()).contains("cannot read"),
          String.valueOf(missing.getMessage()));

      MigrationException syntax =
          assertThrows(MigrationException.class, () -> migrate("bind = \n"));
      assertTrue(
          String.valueOf(syntax.getMessage()).contains("line 1"),
          String.valueOf(syntax.getMessage()));
    }
  }
}
