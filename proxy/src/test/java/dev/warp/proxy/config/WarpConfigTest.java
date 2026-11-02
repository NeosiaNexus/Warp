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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WarpConfig")
class WarpConfigTest {

  @TempDir private Path directory;

  @Nested
  @DisplayName("servers")
  class Servers {

    @Test
    @DisplayName("should generate a default configuration with one lobby server")
    void defaultFile() throws ConfigurationException {
      WarpConfig config = WarpConfig.load(directory);

      assertTrue(Files.exists(directory.resolve("warp.conf")));
      assertEquals(List.of("lobby"), List.copyOf(config.servers().keySet()));
      assertEquals("lobby", config.defaultServer());
      assertEquals(List.of("lobby"), config.fallbackOrder());
    }

    @Test
    @DisplayName("should accept an empty servers block, for servers registered at runtime")
    void emptyBlock() throws Exception {
      WarpConfig config = load("servers {}");

      assertTrue(config.servers().isEmpty());
      assertNull(config.defaultServer());
      assertTrue(config.fallbackOrder().isEmpty());
    }

    @Test
    @DisplayName("should refuse a default server when no server is configured")
    void defaultWithoutServers() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class, () -> load("servers {}\ndefault-server = lobby"));

      assertTrue(String.valueOf(error.getMessage()).contains("no server is configured"));
    }

    @Test
    @DisplayName("should refuse a default server missing from the servers block")
    void unknownDefault() {
      assertThrows(
          ConfigurationException.class,
          () -> load("servers { lobby { address = \"localhost:25565\" } }\ndefault-server = hub"));
    }

    @Test
    @DisplayName("should refuse a servers value that is not a block")
    void notABlock() {
      assertThrows(ConfigurationException.class, () -> load("servers = [\"localhost:25565\"]"));
    }

    @Test
    @DisplayName("should keep the configured servers, lowercased")
    void configuredServers() throws Exception {
      WarpConfig config =
          load(
              """
              servers {
                Lobby { address = "localhost:25565" }
                survival { address = "localhost:25566" }
              }
              default-server = lobby
              fallback-order = [survival, lobby]
              """);

      assertEquals(new InetSocketAddress("localhost", 25566), config.servers().get("survival"));
      assertEquals("lobby", config.defaultServer());
      assertEquals(List.of("survival", "lobby"), config.fallbackOrder());
    }
  }

  @Nested
  @DisplayName("duplicate-login")
  class DuplicateLogin {

    @Test
    @DisplayName("should default to auto")
    void defaultsToAuto() throws Exception {
      assertEquals(DuplicateLoginPolicy.AUTO, load("").duplicateLoginPolicy());
    }

    @Test
    @DisplayName("should read kebab-case values")
    void readsValues() throws Exception {
      assertEquals(
          DuplicateLoginPolicy.KICK_EXISTING,
          load("duplicate-login = kick-existing").duplicateLoginPolicy());
      assertEquals(
          DuplicateLoginPolicy.REFUSE_NEW,
          load("duplicate-login = refuse-new").duplicateLoginPolicy());
    }

    @Test
    @DisplayName("should refuse an unknown value and name the accepted ones")
    void refusesUnknown() {
      ConfigurationException error =
          assertThrows(ConfigurationException.class, () -> load("duplicate-login = maybe"));

      assertTrue(String.valueOf(error.getMessage()).contains("kick-existing"));
    }

    @Test
    @DisplayName("auto should kick the existing session online and refuse the newcomer offline")
    void autoFollowsMode() {
      assertTrue(DuplicateLoginPolicy.AUTO.kicksExisting(true));
      assertEquals(false, DuplicateLoginPolicy.AUTO.kicksExisting(false));
      assertTrue(DuplicateLoginPolicy.KICK_EXISTING.kicksExisting(false));
      assertEquals(false, DuplicateLoginPolicy.REFUSE_NEW.kicksExisting(true));
    }
  }

  private WarpConfig load(String content) throws IOException, ConfigurationException {
    Files.writeString(
        directory.resolve("warp.conf"),
        "forwarding { mode = none }\n" + content,
        StandardCharsets.UTF_8);
    return WarpConfig.load(directory);
  }
}
