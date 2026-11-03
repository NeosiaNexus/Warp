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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

@DisplayName("WarpConfig management")
class ManagementConfigTest {

  @TempDir private Path directory;

  private ManagementConfig parse(String content, Map<String, String> env)
      throws ConfigurationException, IOException {
    CommentedConfigurationNode root =
        HoconConfigurationLoader.builder()
            .source(() -> new BufferedReader(new StringReader(content)))
            .build()
            .load();
    return ManagementConfig.parse(root, directory, name -> env.get(name));
  }

  private ManagementConfig parse(String content) throws ConfigurationException, IOException {
    return parse(content, Map.of());
  }

  @Test
  @DisplayName("should be off by default, bound to the loopback, without a token")
  void defaults() throws Exception {
    ManagementConfig management = parse("");

    assertFalse(management.enabled());
    assertEquals("127.0.0.1", management.bind().getHostString());
    assertEquals(9901, management.bind().getPort());
    assertNull(management.agentCheck());
    assertNull(management.token());
    assertTrue(management.readyRequiresServer());
    assertEquals("off", management.toString());
  }

  @Test
  @DisplayName("should write the management section, off and explained, in a new file")
  void defaultFile() throws Exception {
    WarpConfig config = WarpConfig.load(directory);

    String written = Files.readString(directory.resolve("warp.conf"), StandardCharsets.UTF_8);
    assertTrue(written.contains("\nmanagement {\n  enabled = false\n"), written);
    assertFalse(config.management().enabled());
  }

  @Test
  @DisplayName("should read every key, the token from its file")
  void everyKey() throws Exception {
    Files.writeString(directory.resolve("token"), "  abc123\n", StandardCharsets.UTF_8);

    ManagementConfig management =
        parse(
            """
            management {
              enabled = true
              bind = "0.0.0.0:9901"
              agent-check = "0.0.0.0:9902"
              token-file = "token"
              ready-requires-server = false
            }
            """);

    assertTrue(management.enabled());
    assertEquals(
        InetSocketAddress.createUnresolved("0.0.0.0", 9901).getHostString(),
        management.bind().getHostString());
    assertEquals(9901, management.bind().getPort());
    assertEquals(9902, java.util.Objects.requireNonNull(management.agentCheck()).getPort());
    assertEquals("abc123", management.token());
    assertFalse(management.readyRequiresServer());
  }

  @Test
  @DisplayName("should take the token from the environment ahead of the file")
  void tokenFromEnvironment() throws Exception {
    Files.writeString(directory.resolve("token"), "from-file", StandardCharsets.UTF_8);

    ManagementConfig management =
        parse(
            "management { token-file = \"token\" }",
            Map.of(ManagementConfig.ENV_TOKEN, " from-env "));

    assertEquals("from-env", management.token());
  }

  @Test
  @DisplayName("should refuse a token file that is missing or empty")
  void badTokenFile() throws Exception {
    Files.writeString(directory.resolve("empty"), "\n", StandardCharsets.UTF_8);

    assertThrows(
        ConfigurationException.class, () -> parse("management { token-file = \"missing\" }"));
    assertThrows(
        ConfigurationException.class, () -> parse("management { token-file = \"empty\" }"));
  }

  @Test
  @DisplayName("should refuse the same port for HTTP and the agent check")
  void samePort() {
    assertThrows(
        ConfigurationException.class,
        () -> parse("management { bind = \"127.0.0.1:9901\", agent-check = \"0.0.0.0:9901\" }"));
  }

  @Test
  @DisplayName("should never print the token")
  void redacted() throws Exception {
    ManagementConfig management =
        parse("management { enabled = true }", Map.of(ManagementConfig.ENV_TOKEN, "do-not-print"));

    assertFalse(management.toString().contains("do-not-print"), management.toString());
    assertTrue(management.toString().contains("token set"), management.toString());
  }

  @Test
  @DisplayName("should refuse an invalid address")
  void invalidAddress() {
    assertThrows(ConfigurationException.class, () -> parse("management { bind = \"localhost\" }"));
  }
}
