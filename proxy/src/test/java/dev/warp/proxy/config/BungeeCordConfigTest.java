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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.messaging.BungeeCordSettings;
import dev.warp.proxy.messaging.BungeeCordSubchannel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code bungeecord-channel} block of {@code warp.conf}. */
@DisplayName("WarpConfig bungeecord-channel")
class BungeeCordConfigTest {

  @TempDir private Path directory;

  @Nested
  @DisplayName("defaults")
  class Defaults {

    @Test
    @DisplayName("should answer every sub-channel for every server without the block")
    void withoutBlock() throws ConfigurationException, IOException {
      write("online-mode = false\n");

      BungeeCordSettings settings = WarpConfig.load(directory).bungeeCord();

      assertTrue(settings.enabled());
      assertEquals(EnumSet.allOf(BungeeCordSubchannel.class), settings.allowed("anything"));
      assertEquals(Duration.ofSeconds(30), settings.forwardHold());
    }

    @Test
    @DisplayName("should write the block, explained, into the default configuration")
    void documented() throws ConfigurationException, IOException {
      BungeeCordSettings settings = WarpConfig.load(directory).bungeeCord();

      String written = Files.readString(directory.resolve("warp.conf"), StandardCharsets.UTF_8);
      assertTrue(written.contains("bungeecord-channel {"), written);
      assertTrue(written.contains("forward-hold = 30s"), written);
      assertEquals(BungeeCordSettings.DEFAULTS, settings);
    }
  }

  @Nested
  @DisplayName("rules")
  class Rules {

    @Test
    @DisplayName("should apply the exact name, then the longest prefix, then *")
    void specificity() throws ConfigurationException, IOException {
      write(
          """
          bungeecord-channel {
            allow {
              "*" = [GetServer]
              "game-*" = [Connect, GetServers]
              "game-eu-*" = [connect]
              "game-eu-1" = ["*", "!KickPlayer", "!KickPlayerRaw"]
            }
          }
          """);

      BungeeCordSettings settings = WarpConfig.load(directory).bungeeCord();

      assertEquals(Set.of(BungeeCordSubchannel.GET_SERVER), settings.allowed("lobby"));
      assertEquals(
          Set.of(BungeeCordSubchannel.CONNECT, BungeeCordSubchannel.GET_SERVERS),
          settings.allowed("game-us-3"));
      assertEquals(Set.of(BungeeCordSubchannel.CONNECT), settings.allowed("game-eu-2"));
      Set<BungeeCordSubchannel> exact = settings.allowed("game-eu-1");
      assertEquals(BungeeCordSubchannel.values().length - 2, exact.size());
      assertFalse(exact.contains(BungeeCordSubchannel.KICK_PLAYER));
    }

    @Test
    @DisplayName("should allow nothing to a server no rule matches")
    void unmatched() throws ConfigurationException, IOException {
      write("bungeecord-channel { allow { lobby = [\"*\"] } }\n");

      BungeeCordSettings settings = WarpConfig.load(directory).bungeeCord();

      assertTrue(settings.allowed("game-1").isEmpty());
      assertEquals(EnumSet.allOf(BungeeCordSubchannel.class), settings.allowed("lobby"));
    }

    @Test
    @DisplayName("should take a single string as a one-entry list")
    void singleString() throws ConfigurationException, IOException {
      write("bungeecord-channel { allow { \"*\" = \"*\" } }\n");

      assertEquals(
          EnumSet.allOf(BungeeCordSubchannel.class),
          WarpConfig.load(directory).bungeeCord().allowed("lobby"));
    }

    @Test
    @DisplayName("should refuse an unknown sub-channel, listing the valid ones")
    void unknownSubchannel() throws IOException {
      write("bungeecord-channel { allow { \"*\" = [Teleport] } }\n");

      ConfigurationException error =
          assertThrows(ConfigurationException.class, () -> WarpConfig.load(directory));

      assertTrue(String.valueOf(error.getMessage()).contains("Teleport"));
      assertTrue(String.valueOf(error.getMessage()).contains("ForwardToPlayer"));
    }

    @Test
    @DisplayName("should refuse a pattern with * anywhere but at the end")
    void badPattern() throws IOException {
      write("bungeecord-channel { allow { \"*-eu\" = [Connect] } }\n");

      assertThrows(ConfigurationException.class, () -> WarpConfig.load(directory));
    }
  }

  @Nested
  @DisplayName("switches")
  class Switches {

    @Test
    @DisplayName("should answer nothing when disabled")
    void disabled() throws ConfigurationException, IOException {
      write("bungeecord-channel { enabled = false }\n");

      BungeeCordSettings settings = WarpConfig.load(directory).bungeeCord();

      assertFalse(settings.enabled());
      assertTrue(settings.allowed("lobby").isEmpty());
    }

    @Test
    @DisplayName("should read the forward hold, 0 included")
    void forwardHold() throws ConfigurationException, IOException {
      write("bungeecord-channel { forward-hold = 0 }\n");

      assertEquals(Duration.ZERO, WarpConfig.load(directory).bungeeCord().forwardHold());
    }

    @Test
    @DisplayName("should refuse a block that is not a block")
    void notABlock() throws IOException {
      write("bungeecord-channel = true\n");

      assertThrows(ConfigurationException.class, () -> WarpConfig.load(directory));
    }
  }

  private void write(String content) throws IOException {
    Files.writeString(directory.resolve("warp.conf"), content, StandardCharsets.UTF_8);
  }
}
