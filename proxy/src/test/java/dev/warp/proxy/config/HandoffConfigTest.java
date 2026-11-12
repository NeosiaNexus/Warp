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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("WarpConfig transfers.handoff")
class HandoffConfigTest {

  @TempDir private Path directory;

  private HandoffConfig load(String content) throws ConfigurationException, IOException {
    Files.writeString(
        directory.resolve("warp.conf"), "online-mode = false\n" + content, StandardCharsets.UTF_8);
    return WarpConfig.load(directory).handoff();
  }

  @Nested
  @DisplayName("defaults")
  class Defaults {

    @Test
    @DisplayName("should be off unless turned on: a ticket stands in for Mojang's authentication")
    void off() throws Exception {
      HandoffConfig handoff = load("");

      assertFalse(handoff.enabled());
      assertEquals("transfer.secret", handoff.secretFile());
      assertEquals(Duration.ofSeconds(15), handoff.maxAge());
      assertTrue(handoff.bindAddress());
      assertEquals(List.of(), handoff.peers());
      assertEquals(HandoffConfig.DEFAULTS, handoff);
      assertEquals("off", handoff.toString());
    }

    @Test
    @DisplayName("should write the section, off and explained, in a new file, and read it back")
    void defaultFile() throws Exception {
      WarpConfig config = WarpConfig.load(directory);

      String written = Files.readString(directory.resolve("warp.conf"), StandardCharsets.UTF_8);
      assertTrue(written.contains("\ntransfers {\n  handoff {\n    enabled = false\n"), written);
      assertTrue(written.contains("    bind-address = true\n"), written);
      assertTrue(written.contains("    max-age = 15s\n"), written);
      assertTrue(written.contains("    secret-file = \"transfer.secret\"\n"), written);
      assertTrue(written.contains("    peers = []\n"), written);
      assertEquals(HandoffConfig.DEFAULTS, config.handoff());
      assertFalse(Files.exists(directory.resolve("transfer.secret")), "no key while off");
    }
  }

  @Nested
  @DisplayName("reading the section")
  class Reading {

    @Test
    @DisplayName("should read every setting")
    void everySetting() throws Exception {
      HandoffConfig handoff =
          load(
              """
              transfers.handoff {
                enabled = true
                secret-file = " keys/handoff.key "
                max-age = 5s
                bind-address = false
                peers = ["eu.example.com:25570", "us.example.com", "[2001:db8::1]:25565"]
              }
              """);

      assertTrue(handoff.enabled());
      assertEquals("keys/handoff.key", handoff.secretFile());
      assertEquals(Duration.ofSeconds(5), handoff.maxAge());
      assertFalse(handoff.bindAddress());
      assertEquals(
          List.of(
              new DrainConfig.Target("eu.example.com", 25570),
              new DrainConfig.Target("us.example.com", 25565),
              new DrainConfig.Target("2001:db8::1", 25565)),
          handoff.peers());
      assertEquals(
          "on, max-age 5s, not bound to the client address, 3 peers listed", handoff.toString());
    }

    @Test
    @DisplayName("should say when tickets are bound to the client address")
    void printed() throws Exception {
      HandoffConfig handoff = load("transfers.handoff.enabled = true\n");

      assertEquals(
          "on, max-age 15s, bound to the client address, 0 peers listed", handoff.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1s", "60s", "1m"})
    @DisplayName("should take a max-age from one second to one minute")
    void maxAgeBounds(String value) throws Exception {
      HandoffConfig handoff = load("transfers.handoff.max-age = " + value + "\n");

      assertEquals(
          value.equals("1s") ? Duration.ofSeconds(1) : Duration.ofMinutes(1), handoff.maxAge());
    }

    @ParameterizedTest
    @ValueSource(strings = {"999ms", "61s", "5m", "0"})
    @DisplayName("should refuse a max-age outside one second to one minute, naming the setting")
    void maxAgeOutOfBounds(String value) {
      ConfigurationException refused =
          assertThrows(
              ConfigurationException.class,
              () -> load("transfers.handoff.max-age = " + value + "\n"));

      assertTrue(
          String.valueOf(refused.getMessage()).contains("transfers.handoff.max-age"),
          refused.getMessage());
    }

    @Test
    @DisplayName("should refuse a blank secret-file")
    void blankSecretFile() {
      ConfigurationException refused =
          assertThrows(
              ConfigurationException.class, () -> load("transfers.handoff.secret-file = \"  \"\n"));

      assertTrue(
          String.valueOf(refused.getMessage()).contains("transfers.handoff.secret-file"),
          refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "peers = \"play.example.com\"",
          "peers = [\"\"]",
          "peers = [\"host:0\"]",
          "peers = [\"::1\"]",
          "peers = [\"ok.example.com\", \"host:port\"]"
        })
    @DisplayName("should refuse peers that are not a list of addresses, naming the setting")
    void badPeers(String setting) {
      ConfigurationException refused =
          assertThrows(
              ConfigurationException.class, () -> load("transfers.handoff." + setting + "\n"));

      assertTrue(
          String.valueOf(refused.getMessage()).contains("transfers.handoff.peers"),
          refused.getMessage());
    }
  }

  @Nested
  @DisplayName("the record")
  class Value {

    @Test
    @DisplayName("should copy its peers")
    void copiesPeers() {
      List<DrainConfig.Target> peers = new ArrayList<>();
      peers.add(new DrainConfig.Target("eu.example.com", 25565));
      HandoffConfig handoff = new HandoffConfig(true, "f", Duration.ofSeconds(5), true, peers);

      peers.clear();

      assertEquals(1, handoff.peers().size());
      assertThrows(UnsupportedOperationException.class, () -> handoff.peers().clear());
    }
  }
}
