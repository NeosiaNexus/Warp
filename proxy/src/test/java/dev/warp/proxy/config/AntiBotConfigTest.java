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

import dev.warp.proxy.network.CidrRange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code anti-bot} section of {@code warp.conf}, as {@link WarpConfig} loads it. */
@DisplayName("anti-bot configuration")
class AntiBotConfigTest {

  @TempDir private Path directory;

  @Test
  @DisplayName("should apply the defaults of decision D18 when the section is absent")
  void defaults() throws Exception {
    AntiBotConfig config = load("").antiBot();

    assertEquals(AntiBotConfig.DEFAULTS, config);
    assertTrue(config.enabled());
    assertEquals(Duration.ofDays(30), config.verified().expireAfterLastJoin());
    assertEquals(250_000, config.verified().capacity());
    assertTrue(config.verified().persist());
    assertEquals(10, config.attack().unknownLoginFloor());
  }

  @Test
  @DisplayName("should write a default file that loads to the defaults")
  void defaultFile() throws Exception {
    WarpConfig config = WarpConfig.load(directory);

    String written = Files.readString(directory.resolve("warp.conf"), StandardCharsets.UTF_8);
    assertTrue(written.contains("anti-bot {"), written);
    assertEquals(AntiBotConfig.DEFAULTS, config.antiBot());
  }

  @Test
  @DisplayName("should load every key")
  void everyKey() throws Exception {
    AntiBotConfig config =
        load("""
                anti-bot {
                  enabled = true
                  exempt = ["10.1.0.0/16"]
                  verified {
                    expire-after-last-join = 7d
                    capacity = 1000
                    prefix { ipv4 = 32, ipv6 = 64 }
                    persist = false
                    secret-file = "secret"
                    shared-store-timeout = 1s
                  }
                  attack {
                    floors { unknown-logins = 20, authentication-failures = 8 }
                    baseline-factor = 3
                    pending-share = 0
                    sustain = 5s
                    calm-for = 2m
                    report-interval = 30s
                  }
                  blocklist { strikes = 0, duration = 1h }
                  checks { timeout = 500ms }
                  verification {
                    offline-mode = attack
                    online-mode = never
                    max-concurrent = 64
                    queue { size = 128, max-wait = 5s }
                    probe-timeout = 3s
                    token { lifetime = 2m, rotate-after = 1d, secret-file = "tokens" }
                    transfer { host = "play.example.org", port = 25566 }
                  }
                }
                """)
            .antiBot();

    assertEquals(List.of(CidrRange.parse("10.1.0.0/16")), config.exempt().ranges());
    assertEquals(
        new AntiBotConfig.Verified(
            Duration.ofDays(7), 1000, 32, 64, false, "secret", Duration.ofSeconds(1)),
        config.verified());
    assertEquals(
        new AntiBotConfig.Attack(
            20, 8, 3, 0, Duration.ofSeconds(5), Duration.ofMinutes(2), Duration.ofSeconds(30)),
        config.attack());
    assertFalse(config.blocklist().enabled());
    assertEquals(Duration.ofMillis(500), config.checkTimeout());
    assertEquals(
        new AntiBotConfig.Verification(
            AntiBotConfig.Mode.ATTACK,
            AntiBotConfig.Mode.NEVER,
            64,
            128,
            Duration.ofSeconds(5),
            Duration.ofSeconds(3),
            Duration.ofMinutes(2),
            Duration.ofDays(1),
            "tokens",
            "play.example.org",
            25566),
        config.verification());
  }

  @Test
  @DisplayName("should refuse a verification mode that is not always, attack or never")
  void verificationMode() {
    ConfigurationException refused =
        assertThrows(
            ConfigurationException.class,
            () -> load("anti-bot { verification { offline-mode = sometimes } }"));

    assertTrue(String.valueOf(refused.getMessage()).contains("always, attack or never"));
  }

  @Test
  @DisplayName("should refuse values out of range, naming the section")
  void outOfRange() {
    ConfigurationException refused =
        assertThrows(
            ConfigurationException.class,
            () -> load("anti-bot { verified { prefix { ipv4 = 4 } } }"));

    assertTrue(String.valueOf(refused.getMessage()).contains("anti-bot:"), refused.getMessage());
  }

  private WarpConfig load(String section) throws IOException, ConfigurationException {
    Files.writeString(
        directory.resolve("warp.conf"),
        "forwarding { mode = \"none\" }\n" + section,
        StandardCharsets.UTF_8);
    return WarpConfig.load(directory);
  }
}
