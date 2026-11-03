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
package dev.warp.proxy.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ConsoleCli")
class ConsoleCliTest {

  @TempDir private Path directory;

  @BeforeEach
  void tokens() throws Exception {
    Files.writeString(directory.resolve("ops.token"), "file-token\n", StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("eu.token"), "eu-token", StandardCharsets.UTF_8);
  }

  private ConsoleCli.Options parse(String... args) throws ConsoleCli.UsageException {
    return ConsoleCli.parse(
        List.of(args), Map.of(ConsoleCli.ENV_TOKEN, "env-token")::get, directory);
  }

  private ConsoleCli.Options parseWithoutEnv(String... args) throws ConsoleCli.UsageException {
    return ConsoleCli.parse(List.of(args), name -> null, directory);
  }

  @Nested
  @DisplayName("attach")
  class Attach {

    @Test
    @DisplayName("should run commands on the one proxy, with the token of the variable")
    void oneProxy() throws Exception {
      ConsoleCli.Options options =
          parse("attach", "--url", "http://127.0.0.1:9901", "--level", "warn", "--tags=-netty");

      ConsoleTarget target = options.targets().getFirst();
      assertEquals("127.0.0.1", target.name());
      assertEquals("env-token", target.token());
      assertEquals("127.0.0.1", options.commands());
      assertEquals(Map.of("level", "warn", "tags", "-netty"), options.query());
    }

    @Test
    @DisplayName("should keep several proxies read-only unless one is chosen for commands")
    void fleet() throws Exception {
      ConsoleCli.Options readOnly =
          parse(
              "attach",
              "--url",
              "https://eu-1.example.net:9901",
              "--url",
              "us=https://10.0.0.2:9901");
      ConsoleCli.Options chosen =
          parse(
              "attach",
              "--url",
              "https://eu-1.example.net:9901",
              "--url",
              "us=https://10.0.0.2:9901",
              "--commands",
              "us");

      assertEquals(
          List.of("eu-1", "us"), readOnly.targets().stream().map(ConsoleTarget::name).toList());
      assertNull(readOnly.commands());
      assertEquals("us", chosen.commands());
    }

    @Test
    @DisplayName("should give one proxy its own token file, the others the default one")
    void tokenFiles() throws Exception {
      ConsoleCli.Options options =
          parseWithoutEnv(
              "attach",
              "--url",
              "eu=http://a:1",
              "--url",
              "us=http://b:1",
              "--token-file",
              "ops.token",
              "--token-file",
              "eu=eu.token");

      assertEquals("eu-token", options.targets().get(0).token());
      assertEquals("file-token", options.targets().get(1).token());
    }

    @Test
    @DisplayName("should name two proxies of one host apart")
    void uniqueNames() throws Exception {
      ConsoleCli.Options options = parse("attach", "--url", "http://a:1", "--url", "http://a:2");

      assertEquals(
          List.of("a", "a-2"), options.targets().stream().map(ConsoleTarget::name).toList());
    }

    @Test
    @DisplayName("should stay read-only when asked, even with one proxy")
    void readOnly() throws Exception {
      assertNull(parse("attach", "--url", "http://a:1", "--read-only").commands());
    }
  }

  @Nested
  @DisplayName("mistakes")
  class Mistakes {

    @Test
    @DisplayName("should refuse a token on the command line")
    void tokenArgument() {
      ConsoleCli.UsageException error =
          assertThrows(
              ConsoleCli.UsageException.class,
              () -> parse("attach", "--url", "http://a:1", "--token", "secret"));

      assertTrue(String.valueOf(error.getMessage()).contains("--token-file"), error.getMessage());
    }

    @Test
    @DisplayName("should refuse to attach without an address or without a token")
    void missing() {
      assertThrows(ConsoleCli.UsageException.class, () -> parse("attach"));
      assertThrows(
          ConsoleCli.UsageException.class, () -> parseWithoutEnv("attach", "--url", "http://a:1"));
    }

    @Test
    @DisplayName("should refuse an address with credentials or another scheme")
    void addresses() {
      assertThrows(
          ConsoleCli.UsageException.class, () -> parse("attach", "--url", "http://u:p@a:1"));
      assertThrows(ConsoleCli.UsageException.class, () -> parse("attach", "--url", "ftp://a:1"));
      assertThrows(
          ConsoleCli.UsageException.class, () -> parse("attach", "--url", "http://a:1?token=x"));
    }

    @Test
    @DisplayName("should refuse --commands naming no proxy, and with --read-only")
    void commands() {
      assertThrows(
          ConsoleCli.UsageException.class,
          () -> parse("attach", "--url", "http://a:1", "--commands", "b"));
      assertThrows(
          ConsoleCli.UsageException.class,
          () -> parse("attach", "--url", "http://a:1", "--commands", "a", "--read-only"));
    }

    @Test
    @DisplayName("should refuse an unknown subcommand or option")
    void unknown() {
      assertThrows(ConsoleCli.UsageException.class, () -> parse("tail", "--url", "http://a:1"));
      assertThrows(
          ConsoleCli.UsageException.class,
          () -> parse("attach", "--url", "http://a:1", "--nope", "x"));
    }
  }

  @Nested
  @DisplayName("run")
  class Run {

    @Test
    @DisplayName("should read the command after the options")
    void command() throws Exception {
      ConsoleCli.Options options = parse("run", "--url", "http://a:1", "--", "glist", "all");

      assertEquals("run", options.subcommand());
      assertEquals("glist all", options.command());
    }

    @Test
    @DisplayName("should refuse a run without a command, or on several proxies")
    void invalid() {
      assertThrows(ConsoleCli.UsageException.class, () -> parse("run", "--url", "http://a:1"));
      assertThrows(
          ConsoleCli.UsageException.class,
          () -> parse("run", "--url", "http://a:1", "--url", "http://b:1", "glist"));
    }
  }
}
