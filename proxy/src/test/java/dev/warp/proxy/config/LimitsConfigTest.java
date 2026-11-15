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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The {@code limits} section of {@code warp.conf}, as {@link WarpConfig} loads it. */
@DisplayName("limits configuration")
class LimitsConfigTest {

  @TempDir private Path directory;

  // ---------------------------------------------------------------------------
  // Defaults
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("defaults")
  class Defaults {

    @Test
    @DisplayName("should apply the default limits when the section is absent")
    void absent() throws Exception {
      WarpConfig config = load("");

      assertEquals(LimitsConfig.DEFAULTS, config.limits());
    }

    @Test
    @DisplayName("should write the default limits into the default configuration file")
    void defaultFile() throws Exception {
      WarpConfig config = WarpConfig.load(directory);

      String written = Files.readString(directory.resolve("warp.conf"), StandardCharsets.UTF_8);
      assertTrue(written.contains("limits {"), written);
      assertEquals(LimitsConfig.DEFAULTS, config.limits());
    }

    @Test
    @DisplayName("should exempt loopback, admit shared addresses and turn strict under attack")
    void defaultValues() {
      LimitsConfig defaults = LimitsConfig.DEFAULTS;

      assertTrue(defaults.enabled());
      assertEquals(
          List.of(CidrRange.parse("127.0.0.0/8"), CidrRange.parse("::1")),
          defaults.exempt().ranges());
      assertEquals(Duration.ofSeconds(1), defaults.logins().interval());
      assertEquals(10, defaults.maxConcurrentLoginsPerAddress());
      assertEquals("2 at once, then 1 every 3s", defaults.loginsUnderAttack().toString());
      assertEquals(2, defaults.maxConcurrentLoginsPerAddressUnderAttack());
      assertEquals("10 at once, then 1 every 1s", defaults.logins().toString());
      assertEquals("10 at once, then 1 every 200ms", defaults.connections().toString());
    }

    @Test
    @DisplayName("should cap connections not logged in and give handshakes and pings 5 s each")
    void preLoginDefaults() {
      LimitsConfig defaults = LimitsConfig.DEFAULTS;

      assertEquals(8192, defaults.maxPendingConnections());
      assertEquals(Duration.ofMillis(250), defaults.pendingEvictionAfter());
      assertEquals(Duration.ZERO, LimitsConfig.DISABLED.pendingEvictionAfter());
      assertEquals(Duration.ofSeconds(5), defaults.handshakeTimeout());
      assertEquals(Duration.ofSeconds(5), defaults.statusTimeout());
    }
  }

  // ---------------------------------------------------------------------------
  // Values
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("values")
  class Values {

    @Test
    @DisplayName("should load every key")
    void everyKey() throws Exception {
      WarpConfig config =
          load(
              """
              limits {
                enabled = true
                exempt = ["10.1.0.0/16"]
                connections { burst = 20, interval = 100ms }
                status-pings { burst = 5, interval = 2s }
                logins {
                  burst = 1
                  interval = 3s
                  max-concurrent-per-address = 4
                  max-concurrent-authentications = 64
                  timeout = 1m
                }
                report-interval = 5m
                max-pending-connections = 1000
                evict-pending-after = 100ms
                handshake-timeout = 3s
                status-timeout = 0
              }
              """);

      LimitsConfig limits = config.limits();
      assertEquals(1000, limits.maxPendingConnections());
      assertEquals(Duration.ofMillis(100), limits.pendingEvictionAfter());
      assertEquals(Duration.ofSeconds(3), limits.handshakeTimeout());
      assertEquals(Duration.ZERO, limits.statusTimeout());
      assertEquals(List.of(CidrRange.parse("10.1.0.0/16")), limits.exempt().ranges());
      assertEquals(new LimitsConfig.Rate(20, Duration.ofMillis(100)), limits.connections());
      assertEquals(new LimitsConfig.Rate(5, Duration.ofSeconds(2)), limits.statusPings());
      assertEquals(new LimitsConfig.Rate(1, Duration.ofSeconds(3)), limits.logins());
      assertEquals(4, limits.maxConcurrentLoginsPerAddress());
      assertEquals(64, limits.maxConcurrentAuthentications());
      assertEquals(Duration.ofMinutes(1), limits.loginTimeout());
      assertEquals(Duration.ofMinutes(5), limits.reportInterval());
    }

    @Test
    @DisplayName("should keep the defaults of the keys a section leaves out")
    void partialSection() throws Exception {
      WarpConfig config = load("limits { logins { burst = 3 } }");

      LimitsConfig limits = config.limits();
      assertEquals(3, limits.logins().burst());
      assertEquals(LimitsConfig.DEFAULTS.logins().interval(), limits.logins().interval());
      assertEquals(LimitsConfig.DEFAULTS.connections(), limits.connections());
    }

    @ParameterizedTest(name = "{0} -> {1} ms")
    @CsvSource({"250ms, 250", "3s, 3000", "2m, 120000", "1h, 3600000", "0, 0"})
    @DisplayName("should read durations with a unit, and a bare 0")
    void durations(String value, long millis) throws Exception {
      WarpConfig config = load("limits { connections { interval = " + value + " } }");

      assertEquals(Duration.ofMillis(millis), config.limits().connections().interval());
    }

    @Test
    @DisplayName("should lift a rate limit with a zero interval and a concurrency limit with 0")
    void zeroLiftsLimits() throws Exception {
      WarpConfig config =
          load(
              "limits { logins { interval = 0, max-concurrent-per-address = 0,"
                  + " max-concurrent-authentications = 0, timeout = 0 } }");

      LimitsConfig limits = config.limits();
      assertFalse(limits.logins().limited());
      assertEquals(0, limits.maxConcurrentLoginsPerAddress());
      assertEquals(0, limits.maxConcurrentAuthentications());
      assertEquals(Duration.ZERO, limits.loginTimeout());
    }

    @Test
    @DisplayName("should load an empty exempt list")
    void noExemption() throws Exception {
      WarpConfig config = load("limits { exempt = [] }");

      assertTrue(config.limits().exempt().isEmpty());
    }

    @Test
    @DisplayName("should load disabled limits")
    void disabled() throws Exception {
      assertFalse(load("limits { enabled = false }").limits().enabled());
    }
  }

  // ---------------------------------------------------------------------------
  // Invalid values
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("invalid values")
  class InvalidValues {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1.5s", "-1s", "3 weeks", "fast", "3 sec"})
    @DisplayName("should refuse a duration it cannot read, naming the key")
    void badDuration(String value) {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class,
              () -> load("limits { logins { interval = \"" + value + "\" } }"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("limits.logins.interval must be a duration"),
          error.getMessage());
    }

    @Test
    @DisplayName("should refuse a burst of 0, pointing at the interval to lift the limit")
    void zeroBurst() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class, () -> load("limits { connections { burst = 0 } }"));

      assertTrue(String.valueOf(error.getMessage()).contains("interval = 0"), error.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"-1", "two", "1.5", "99999999999"})
    @DisplayName("should refuse a count that is not a whole number of zero or more")
    void badCount(String value) {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class,
              () -> load("limits { logins { max-concurrent-per-address = \"" + value + "\" } }"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("limits.logins.max-concurrent-per-address"),
          error.getMessage());
    }

    @Test
    @DisplayName("should refuse an invalid exempt entry, naming it")
    void badExempt() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class, () -> load("limits { exempt = [\"localhost\"] }"));

      assertTrue(String.valueOf(error.getMessage()).contains("limits.exempt:"));
    }

    @Test
    @DisplayName("should refuse a time before making room it cannot read, naming the key")
    void badEvictPendingAfter() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class, () -> load("limits { evict-pending-after = soon }"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("limits.evict-pending-after"),
          error.getMessage());
    }

    @Test
    @DisplayName("should refuse a negative time before making room")
    void negativeEvictPendingAfter() {
      LimitsConfig d = LimitsConfig.DEFAULTS;

      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  new LimitsConfig(
                      true,
                      d.exempt(),
                      d.connections(),
                      d.statusPings(),
                      d.logins(),
                      d.maxConcurrentLoginsPerAddress(),
                      d.maxConcurrentAuthentications(),
                      d.loginTimeout(),
                      d.reportInterval(),
                      d.maxPendingConnections(),
                      d.handshakeTimeout(),
                      d.statusTimeout(),
                      d.authenticationQueue(),
                      d.authenticationQueueWait(),
                      d.loginsUnderAttack(),
                      d.maxConcurrentLoginsPerAddressUnderAttack(),
                      Duration.ofNanos(-1)));

      assertTrue(String.valueOf(error.getMessage()).endsWith(", PT-0.000000001S"));
    }

    @Test
    @DisplayName("should take 0 as never closing a connection to make room")
    void zeroEvictPendingAfter() throws Exception {
      assertEquals(
          Duration.ZERO,
          load("limits { evict-pending-after = 0 }").limits().pendingEvictionAfter());
    }

    @Test
    @DisplayName("should refuse a report interval under a second")
    void shortReportInterval() {
      assertThrows(ConfigurationException.class, () -> load("limits { report-interval = 10ms }"));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private WarpConfig load(String limits) throws IOException, ConfigurationException {
    Files.writeString(
        directory.resolve("warp.conf"),
        "forwarding { mode = \"none\" }\n" + limits,
        StandardCharsets.UTF_8);
    return WarpConfig.load(directory);
  }
}
