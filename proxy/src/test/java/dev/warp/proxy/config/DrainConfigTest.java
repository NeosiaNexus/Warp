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

import dev.warp.proxy.i18n.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("WarpConfig drain")
class DrainConfigTest {

  @TempDir private Path directory;

  private DrainConfig load(String content) throws ConfigurationException, IOException {
    Files.writeString(directory.resolve("warp.conf"), content, StandardCharsets.UTF_8);
    return WarpConfig.load(directory).drain();
  }

  @Test
  @DisplayName("should default to PrimeMC's production values, transferring nobody")
  void defaults() throws Exception {
    DrainConfig drain = load("online-mode = false\n");

    assertNull(drain.transferTo());
    assertFalse(drain.onSigterm());
    assertEquals(Duration.ofSeconds(5), drain.propagationDelay());
    assertEquals(Duration.ofMillis(100), drain.transferInterval());
    assertEquals(DrainConfig.CannotTransfer.KEEP, drain.cannotTransfer());
    assertEquals(DrainConfig.NewPlayers.ACCEPT, drain.newPlayers());
    assertEquals(Duration.ofMinutes(30), drain.timeout());
    assertEquals(Message.RESTARTING.of(), drain.message());
  }

  @Test
  @DisplayName("should take transfer-to = fleet as the least loaded proxy of the fleet")
  void fleetTarget() throws Exception {
    DrainConfig drain = load("online-mode = false\ndrain { transfer-to = \"fleet\" }\n");

    DrainConfig.Target target = drain.transferTo();
    assertEquals(DrainConfig.Target.FLEET, target);
    assertTrue(target != null && target.isFleet());
    assertEquals("fleet", String.valueOf(target));
    assertFalse(new DrainConfig.Target("fleet", 25565).isFleet(), "a host named fleet is one");
  }

  @Test
  @DisplayName("should write the drain section, with its defaults explained, in a new file")
  void defaultFile() throws Exception {
    WarpConfig config = WarpConfig.load(directory);

    String written = Files.readString(directory.resolve("warp.conf"), StandardCharsets.UTF_8);
    assertTrue(written.contains("\ndrain {\n"), written);
    assertTrue(written.contains("  propagation-delay = 5s\n"), written);
    assertEquals(DrainConfig.CannotTransfer.KEEP, config.drain().cannotTransfer());
    assertNull(config.drain().transferTo());
  }

  @Test
  @DisplayName("should read every key")
  void everyKey() throws Exception {
    DrainConfig drain =
        load(
            """
            drain {
              transfer-to = "play.example.net:25570"
              on-sigterm = true
              propagation-delay = 10s
              transfer-interval = 250ms
              cannot-transfer = kick
              new-players = refuse
              timeout = 2h
            }
            """);

    assertEquals(new DrainConfig.Target("play.example.net", 25570), drain.transferTo());
    assertTrue(drain.onSigterm());
    assertEquals(Duration.ofSeconds(10), drain.propagationDelay());
    assertEquals(Duration.ofMillis(250), drain.transferInterval());
    assertEquals(DrainConfig.CannotTransfer.KICK, drain.cannotTransfer());
    assertEquals(DrainConfig.NewPlayers.REFUSE, drain.newPlayers());
    assertEquals(Duration.ofHours(2), drain.timeout());
  }

  @Nested
  @DisplayName("transfer-to")
  class TransferTo {

    @Test
    @DisplayName("should default the port to Minecraft's")
    void defaultPort() throws Exception {
      assertEquals(
          new DrainConfig.Target("play.example.net", 25565),
          DrainConfig.parseTarget("drain.transfer-to", "play.example.net"));
    }

    @Test
    @DisplayName("should read an IPv6 address in brackets, with or without a port")
    void ipv6() throws Exception {
      assertEquals(
          new DrainConfig.Target("::1", 25570),
          DrainConfig.parseTarget("drain.transfer-to", "[::1]:25570"));
      assertEquals(
          new DrainConfig.Target("2001:db8::1", 25565),
          DrainConfig.parseTarget("drain.transfer-to", "[2001:db8::1]"));
      assertEquals("[::1]:25570", new DrainConfig.Target("::1", 25570).toString());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"::1", "host:0", "host:65536", "host:port", ":25565", "[::1", "[::1]x"})
    @DisplayName("should refuse an address it cannot use")
    void invalid(String value) {
      assertThrows(
          ConfigurationException.class, () -> DrainConfig.parseTarget("drain.transfer-to", value));
    }
  }

  @Nested
  @DisplayName("durations and choices")
  class Values {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"5", "five seconds", "-1s", "1w", "s"})
    @DisplayName("should refuse a duration without a known unit")
    void invalidDuration(String value) {
      assertThrows(
          ConfigurationException.class,
          () -> load("drain {\n  propagation-delay = \"" + value + "\"\n}\n"));
    }

    @Test
    @DisplayName("should accept a zero delay and interval, but not a zero time-out")
    void zero() throws Exception {
      DrainConfig drain = load("drain {\n  propagation-delay = 0s\n  transfer-interval = 0ms\n}\n");

      assertEquals(Duration.ZERO, drain.propagationDelay());
      assertEquals(Duration.ZERO, drain.transferInterval());
      assertThrows(ConfigurationException.class, () -> load("drain {\n  timeout = 0s\n}\n"));
    }

    @Test
    @DisplayName("should name the allowed choices when one is wrong")
    void invalidChoice() {
      ConfigurationException e =
          assertThrows(
              ConfigurationException.class, () -> load("drain {\n  new-players = maybe\n}\n"));

      assertTrue(String.valueOf(e.getMessage()).contains("accept, refuse"), e.getMessage());
    }

    @Test
    @DisplayName("should point a drain.message of an older layout to the message that replaced it")
    void messageMoved() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class, () -> load("drain {\n  message = \"<red>Back\"\n}\n"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("warp.disconnect.restarting"),
          error.getMessage());
    }

    @Test
    @DisplayName("should refuse a duration without a unit")
    void bareDuration() {
      ConfigurationException error =
          assertThrows(ConfigurationException.class, () -> load("drain {\n  timeout = 30\n}\n"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("drain.timeout needs a unit"),
          error.getMessage());
    }

    @Test
    @DisplayName("should format durations as the configuration writes them")
    void format() {
      assertEquals("100ms", DrainConfig.format(Duration.ofMillis(100)));
      assertEquals("5s", DrainConfig.format(Duration.ofSeconds(5)));
      assertEquals("30m", DrainConfig.format(Duration.ofMinutes(30)));
      assertEquals("2h", DrainConfig.format(Duration.ofHours(2)));
      assertEquals("0s", DrainConfig.format(Duration.ZERO));
      assertEquals("1500ms", DrainConfig.format(Duration.ofMillis(1500)));
    }
  }
}
