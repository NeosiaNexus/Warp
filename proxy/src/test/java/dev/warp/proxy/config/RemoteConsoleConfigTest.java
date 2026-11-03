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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.config.RemoteConsoleConfig.Scope;
import dev.warp.proxy.config.RemoteConsoleConfig.Token;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

@DisplayName("WarpConfig management.console and management.tls")
class RemoteConsoleConfigTest {

  private static final String OPS = "a".repeat(40);
  private static final String DASH = "b".repeat(40);

  @TempDir private Path directory;

  private ManagementConfig parse(String content, Map<String, String> env)
      throws ConfigurationException, IOException {
    CommentedConfigurationNode root =
        HoconConfigurationLoader.builder()
            .source(() -> new BufferedReader(new StringReader(content)))
            .build()
            .load();
    return ManagementConfig.parse(root, directory, env::get);
  }

  private ManagementConfig parse(String content) throws ConfigurationException, IOException {
    return parse(content, Map.of());
  }

  private static String console(String bind, String tokens, String extra) {
    return "management { enabled = true, bind = \""
        + bind
        + "\"\n console { enabled = true\n tokens = ["
        + tokens
        + "]\n"
        + extra
        + "} }";
  }

  @Nested
  @DisplayName("defaults")
  class Defaults {

    @Test
    @DisplayName("should keep the console off and the HTTP port in plain text")
    void off() throws Exception {
      ManagementConfig management = parse("management { enabled = true }");

      assertFalse(management.console().enabled());
      assertNull(management.tls());
      assertEquals(1000, management.console().history());
      assertEquals(5, management.console().lockoutAfter());
      assertEquals(Duration.ofMinutes(15), management.console().lockoutDuration());
    }

    @Test
    @DisplayName("should write the console section, off, in a new file")
    void defaultFile() throws Exception {
      WarpConfig config = WarpConfig.load(directory);

      String written = Files.readString(directory.resolve("warp.conf"), StandardCharsets.UTF_8);
      assertTrue(written.contains("  console {\n    enabled = false\n"), written);
      assertFalse(config.management().console().enabled());
    }
  }

  @Nested
  @DisplayName("tokens")
  class Tokens {

    @Test
    @DisplayName("should read a token from a file, one from a variable and one as its digest")
    void sources() throws Exception {
      Files.writeString(directory.resolve("ops.token"), OPS + "\n", StandardCharsets.UTF_8);
      String digest = HexFormat.of().formatHex(RemoteConsoleConfig.sha256("c".repeat(40)));

      RemoteConsoleConfig console =
          parse(
                  console(
                      "127.0.0.1:9901",
                      "{ name = ops, scopes = [logs, commands], file = ops.token },"
                          + "{ name = dash, scopes = [logs], env = DASH_TOKEN },"
                          + "{ name = ci, scopes = [commands], sha256 = \""
                          + digest
                          + "\" }",
                      ""),
                  Map.of("DASH_TOKEN", DASH))
              .console();

      assertEquals(3, console.tokens().size());
      Token ops = console.tokens().get(0);
      assertEquals("ops", ops.name());
      assertEquals(Set.of(Scope.LOGS, Scope.COMMANDS), ops.scopes());
      assertArrayEquals(RemoteConsoleConfig.sha256(OPS), ops.digest());
      assertEquals(OPS, ops.secret());
      assertEquals(Set.of(Scope.LOGS), console.tokens().get(1).scopes());
      assertNull(console.tokens().get(2).secret());
      assertTrue(console.runsCommands());
      assertFalse(console.toString().contains(OPS), "no token in the description");
    }

    @Test
    @DisplayName("should refuse a token shorter than 32 characters")
    void tooShort() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class,
              () ->
                  parse(
                      console("127.0.0.1:9901", "{ name = ops, scopes = [logs], env = T }", ""),
                      Map.of("T", "hunter2")));

      assertTrue(
          String.valueOf(error.getMessage()).contains("openssl rand -hex 32"), error.getMessage());
    }

    @Test
    @DisplayName("should refuse the drain token as a console token")
    void drainToken() throws Exception {
      Files.writeString(directory.resolve("drain"), OPS, StandardCharsets.UTF_8);

      ConfigurationException error =
          assertThrows(
              ConfigurationException.class,
              () ->
                  parse(
                      "management { enabled = true, token-file = drain\n console { enabled = true\n"
                          + " tokens = [{ name = ops, scopes = [logs], env = T }] } }",
                      Map.of("T", OPS)));

      assertTrue(String.valueOf(error.getMessage()).contains("starts drains"), error.getMessage());
    }

    @Test
    @DisplayName("should refuse two holders of one token, and one name twice")
    void duplicates() {
      Map<String, String> env = Map.of("A", OPS, "B", OPS, "C", DASH);

      assertThrows(
          ConfigurationException.class,
          () ->
              parse(
                  console(
                      "127.0.0.1:9901",
                      "{ name = a, scopes = [logs], env = A }, { name = b, scopes = [logs], env ="
                          + " B }",
                      ""),
                  env));
      assertThrows(
          ConfigurationException.class,
          () ->
              parse(
                  console(
                      "127.0.0.1:9901",
                      "{ name = a, scopes = [logs], env = A }, { name = A, scopes = [logs], env ="
                          + " C }",
                      ""),
                  env));
    }

    @Test
    @DisplayName("should refuse an unknown scope, no source, and two sources")
    void invalidEntries() {
      Map<String, String> env = Map.of("A", OPS);

      assertThrows(
          ConfigurationException.class,
          () ->
              parse(console("127.0.0.1:9901", "{ name = a, scopes = [admin], env = A }", ""), env));
      assertThrows(
          ConfigurationException.class,
          () -> parse(console("127.0.0.1:9901", "{ name = a, scopes = [logs] }", ""), env));
      assertThrows(
          ConfigurationException.class,
          () ->
              parse(
                  console(
                      "127.0.0.1:9901",
                      "{ name = a, scopes = [logs], env = A, sha256 = \"" + "0".repeat(64) + "\" }",
                      ""),
                  env));
    }

    @Test
    @DisplayName(
        "should refuse an enabled console without a token or without the management server")
    void enabledWithoutTokens() {
      assertThrows(ConfigurationException.class, () -> parse(console("127.0.0.1:9901", "", "")));
      assertThrows(
          ConfigurationException.class,
          () ->
              parse(
                  "management { console { enabled = true, tokens = [{ name = a, scopes = [logs],"
                      + " env = A }] } }",
                  Map.of("A", OPS)));
    }
  }

  @Nested
  @DisplayName("commands without TLS")
  class Plaintext {

    private static final String COMMANDS = "{ name = ops, scopes = [logs, commands], env = A }";
    private static final Map<String, String> ENV = Map.of("A", OPS);

    @Test
    @DisplayName("should refuse to start when commands would cross the network in clear text")
    void refused() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class,
              () -> parse(console("0.0.0.0:9901", COMMANDS, ""), ENV));

      assertTrue(String.valueOf(error.getMessage()).contains("management.tls"), error.getMessage());
      assertTrue(
          String.valueOf(error.getMessage()).contains("trusted-networks"), error.getMessage());
    }

    @Test
    @DisplayName("should accept commands on the loopback")
    void loopback() throws Exception {
      assertTrue(parse(console("127.0.0.1:9901", COMMANDS, ""), ENV).console().runsCommands());
    }

    @Test
    @DisplayName("should accept commands from the trusted networks")
    void trusted() throws Exception {
      RemoteConsoleConfig console =
          parse(console("0.0.0.0:9901", COMMANDS, "trusted-networks = [\"10.0.0.0/8\"]"), ENV)
              .console();

      assertFalse(console.trustedNetworks().isEmpty());
    }

    @Test
    @DisplayName("should accept a read-only token anywhere")
    void readOnly() throws Exception {
      assertFalse(
          parse(console("0.0.0.0:9901", "{ name = d, scopes = [logs], env = A }", ""), ENV)
              .console()
              .runsCommands());
    }

    @Test
    @DisplayName("should accept commands over TLS")
    void tls() throws Exception {
      Files.writeString(directory.resolve("tls.crt"), "certificate", StandardCharsets.UTF_8);
      Files.writeString(directory.resolve("tls.key"), "key", StandardCharsets.UTF_8);

      ManagementConfig management =
          parse(
              "management { enabled = true, bind = \"0.0.0.0:9901\"\n tls { certificate ="
                  + " tls.crt, key = tls.key }\n console { enabled = true, tokens = ["
                  + COMMANDS
                  + "] } }",
              ENV);

      assertNotNull(management.tls());
      assertEquals(directory.resolve("tls.key"), management.tls().key());
    }

    @Test
    @DisplayName("should refuse a certificate without its key, and missing files")
    void tlsFiles() {
      assertThrows(
          ConfigurationException.class,
          () -> parse("management { tls { certificate = tls.crt } }"));
      assertThrows(
          ConfigurationException.class,
          () -> parse("management { tls { certificate = none.crt, key = none.key } }"));
    }
  }
}
