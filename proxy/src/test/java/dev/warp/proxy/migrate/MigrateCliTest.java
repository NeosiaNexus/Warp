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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.console.ConsoleMode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("MigrateCli")
class MigrateCliTest {

  @TempDir private Path directory;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private Path velocity;
  private Path target;

  @BeforeEach
  void installations() throws Exception {
    velocity = Files.createDirectories(directory.resolve("velocity"));
    target = Files.createDirectories(directory.resolve("warp"));
    Files.writeString(
        velocity.resolve("velocity.toml"),
        MigrationSupport.resource("velocity.toml"),
        StandardCharsets.UTF_8);
    Files.writeString(velocity.resolve("forwarding.secret"), "abc", StandardCharsets.UTF_8);
    Files.createDirectories(velocity.resolve("plugins"));
    Files.createFile(velocity.resolve("plugins").resolve("ViaVersion-5.2.0.jar"));
    Files.createFile(velocity.resolve("plugins").resolve("LuckPerms-Velocity-5.4.jar"));
    Files.createFile(velocity.resolve("plugins").resolve("MyOwnPlugin.jar"));
  }

  private int run(String... args) {
    ConsoleMode plain = ConsoleMode.detect().plain();
    return MigrateCli.run(
        List.of(args),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8),
        plain);
  }

  private String out() {
    return out.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return err.toString(StandardCharsets.UTF_8);
  }

  @Nested
  @DisplayName("arguments")
  class Arguments {

    @Test
    @DisplayName("should default the source and target to the current directory")
    void defaults() throws Exception {
      MigrateCli.Options options = MigrateCli.parse(List.of(), velocity);

      assertEquals(velocity, options.source());
      assertEquals(velocity, options.target());
      assertNull(options.from());
      assertFalse(options.dryRun());
      assertFalse(options.force());
    }

    @Test
    @DisplayName("should read every option, in both spellings")
    void everything() throws Exception {
      MigrateCli.Options options =
          MigrateCli.parse(
              List.of("--from=Waterfall", "--to", "out", "--dry-run", "--force", "velocity"),
              directory);

      assertEquals(velocity, options.source());
      assertEquals(directory.resolve("out"), options.target());
      assertEquals("bungeecord", options.from());
      assertTrue(options.dryRun());
      assertTrue(options.force());
    }

    @Test
    @DisplayName("should refuse unknown options, unknown proxies and a missing directory")
    void refused() {
      assertThrows(
          MigrateCli.UsageException.class, () -> MigrateCli.parse(List.of("--nope"), directory));
      assertThrows(
          MigrateCli.UsageException.class,
          () -> MigrateCli.parse(List.of("--from", "gate"), directory));
      assertThrows(
          MigrateCli.UsageException.class, () -> MigrateCli.parse(List.of("--to"), directory));
      assertThrows(
          MigrateCli.UsageException.class, () -> MigrateCli.parse(List.of("missing"), directory));
      assertThrows(
          MigrateCli.UsageException.class,
          () -> MigrateCli.parse(List.of("velocity", "warp"), directory));
      assertEquals(MigrateCli.USAGE, run("--nope"));
      assertTrue(err().contains("--help"), err());
    }
  }

  @Nested
  @DisplayName("running")
  class Running {

    @Test
    @DisplayName("should detect Velocity, write both files and report plugins")
    void writes() throws Exception {
      int code = run(velocity.toString(), "--to", target.toString());

      assertEquals(MigrateCli.OK, code, err());
      assertTrue(Files.exists(target.resolve("warp.conf")));
      assertEquals("abc", Files.readString(target.resolve("forwarding.secret")));
      String report = out();
      assertTrue(report.contains("Migrating Velocity from"), report);
      assertTrue(report.contains("Written: warp.conf, forwarding.secret"), report);
      assertTrue(report.contains("-> bind"), report);
      assertTrue(report.contains("ViaVersion-5.2.0.jar"), report);
      assertTrue(report.contains("replaced"), report);
      assertTrue(report.contains("LuckPerms-Velocity-5.4.jar"), report);
      assertTrue(report.contains("unavailable"), report);
      assertTrue(report.contains("MyOwnPlugin.jar"), report);
      assertTrue(report.contains("unknown"), report);
      assertTrue(report.contains("Next steps"), report);
    }

    @Test
    @DisplayName("should refuse to overwrite without --force, and overwrite with it")
    void force() throws Exception {
      Files.writeString(target.resolve("warp.conf"), "old", StandardCharsets.UTF_8);

      assertEquals(MigrateCli.EXISTS, run(velocity.toString(), "--to", target.toString()));
      assertEquals("old", Files.readString(target.resolve("warp.conf")));
      assertTrue(err().contains("--force"), err());

      assertEquals(MigrateCli.OK, run(velocity.toString(), "--to", target.toString(), "--force"));
      assertTrue(Files.readString(target.resolve("warp.conf")).contains("bind = "));
    }

    @Test
    @DisplayName("should print the configuration on standard output with --dry-run")
    void dryRun() throws Exception {
      int code = run("--dry-run", velocity.toString(), "--to", target.toString());

      assertEquals(MigrateCli.OK, code, err());
      assertFalse(Files.exists(target.resolve("warp.conf")));
      assertTrue(out().startsWith("# Warp configuration migrated from Velocity"), out());
      assertFalse(out().contains("Migrated ("), out());
      assertTrue(err().contains("Migrated ("), err());
      assertTrue(err().contains("(dry run)"), err());
    }

    @Test
    @DisplayName("should ask for --from when both proxies' files are present, and fail without any")
    void detection() throws Exception {
      Path empty = Files.createDirectories(directory.resolve("empty"));
      assertEquals(MigrateCli.FAILED, run(empty.toString()));
      assertTrue(err().contains("neither velocity.toml nor config.yml"), err());

      Files.writeString(
          velocity.resolve("config.yml"), "online_mode: true\n", StandardCharsets.UTF_8);
      err.reset();
      assertEquals(MigrateCli.FAILED, run(velocity.toString(), "--to", target.toString()));
      assertTrue(err().contains("--from"), err());

      err.reset();
      assertEquals(
          MigrateCli.OK,
          run(velocity.toString(), "--from", "bungeecord", "--to", target.toString(), "--force"));
      assertTrue(out().contains("Migrating BungeeCord"), out());
    }

    @Test
    @DisplayName("should fail with the parser's message on an invalid file")
    void invalid() throws Exception {
      Files.writeString(velocity.resolve("velocity.toml"), "bind = \n", StandardCharsets.UTF_8);

      assertEquals(MigrateCli.FAILED, run(velocity.toString(), "--to", target.toString()));
      assertTrue(err().contains("line 1"), err());
    }
  }
}
