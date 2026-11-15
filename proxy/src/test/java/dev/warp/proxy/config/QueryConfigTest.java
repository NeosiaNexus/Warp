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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("WarpConfig query")
class QueryConfigTest {

  @TempDir private Path directory;

  private QueryConfig load(String content) throws ConfigurationException, IOException {
    Files.writeString(directory.resolve("warp.conf"), content, StandardCharsets.UTF_8);
    return WarpConfig.load(directory).query();
  }

  @Test
  @DisplayName("should be off by default, on the proxy's address and port over UDP")
  void defaults() throws Exception {
    QueryConfig query = load("bind = \"127.0.0.1:25570\"\n");

    assertFalse(query.enabled());
    assertEquals(new InetSocketAddress("127.0.0.1", 25570), query.bind());
    assertEquals("Warp", query.map());
    assertFalse(query.showPlugins());
    assertEquals(0, query.playerNames());
    assertEquals("", query.advertiseHost());
    assertEquals(QueryConfig.RateLimit.DEFAULTS, query.rateLimit());
    assertEquals("off", query.toString());
  }

  @Test
  @DisplayName("should write the query section, with its defaults explained, in a new file")
  void written() throws Exception {
    WarpConfig config = WarpConfig.load(directory);

    String text = Files.readString(directory.resolve("warp.conf"));
    assertTrue(text.contains("query {"));
    assertTrue(text.contains("player-names = 0"));
    assertTrue(text.contains("per-address = 5"));
    assertFalse(config.query().enabled());
  }

  @Test
  @DisplayName("should read every key")
  void everyKey() throws Exception {
    QueryConfig query =
        load(
            """
            query {
              enabled = true
              bind = "0.0.0.0:25565"
              map = "Hub"
              show-plugins = true
              player-names = 50
              advertise-host = "play.example.com"
              rate-limit { per-address = 2, burst = 4, global = 500 }
            }
            """);

    assertTrue(query.enabled());
    assertEquals(new InetSocketAddress("0.0.0.0", 25565), query.bind());
    assertEquals("Hub", query.map());
    assertTrue(query.showPlugins());
    assertEquals(50, query.playerNames());
    assertEquals("play.example.com", query.advertiseHost());
    assertEquals(new QueryConfig.RateLimit(2, 4, 500), query.rateLimit());
    assertTrue(query.toString().startsWith("on "));
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {-1, 1001})
    @DisplayName("should refuse player-names outside 0 to 1000")
    void playerNames(int value) {
      ConfigurationException e =
          assertThrows(
              ConfigurationException.class, () -> load("query { player-names = " + value + " }\n"));
      assertTrue(String.valueOf(e.getMessage()).contains("query.player-names"), e.getMessage());
    }

    @Test
    @DisplayName("should refuse a blank or oversized map")
    void map() {
      assertThrows(ConfigurationException.class, () -> load("query { map = \"  \" }\n"));
      assertThrows(
          ConfigurationException.class, () -> load("query { map = \"" + "x".repeat(65) + "\" }\n"));
    }

    @Test
    @DisplayName("should refuse a burst under the per-address rate, and a global under it")
    void rateLimits() {
      ConfigurationException burst =
          assertThrows(
              ConfigurationException.class,
              () -> load("query { rate-limit { per-address = 10, burst = 5 } }\n"));
      assertTrue(
          String.valueOf(burst.getMessage()).contains("query.rate-limit.burst"),
          burst.getMessage());
      ConfigurationException global =
          assertThrows(
              ConfigurationException.class,
              () -> load("query { rate-limit { per-address = 10, burst = 10, global = 5 } }\n"));
      assertTrue(
          String.valueOf(global.getMessage()).contains("query.rate-limit.global"),
          global.getMessage());
      assertThrows(
          ConfigurationException.class, () -> load("query { rate-limit { per-address = 0 } }\n"));
    }

    @Test
    @DisplayName("should refuse an unparsable bind")
    void bind() {
      ConfigurationException e =
          assertThrows(ConfigurationException.class, () -> load("query { bind = \"nonsense\" }\n"));
      assertTrue(String.valueOf(e.getMessage()).contains("query.bind"), e.getMessage());
    }

    @Test
    @DisplayName("should refuse an unknown key in the block")
    void unknownKey() {
      assertThrows(ConfigurationException.class, () -> load("query { port = 25565 }\n"));
    }
  }
}
