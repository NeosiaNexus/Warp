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
import dev.warp.proxy.network.CidrRanges;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code proxy-protocol} section of {@code warp.conf}, as {@link WarpConfig} loads it. */
@DisplayName("proxy-protocol configuration")
class ProxyProtocolConfigTest {

  @TempDir private Path directory;

  // ---------------------------------------------------------------------------
  // Defaults
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("defaults")
  class Defaults {

    @Test
    @DisplayName("should be disabled when the section is absent")
    void absent() throws Exception {
      WarpConfig config = load("forwarding { mode = \"none\" }");

      assertFalse(config.proxyProtocol().enabled());
      assertTrue(config.proxyProtocol().trustedProxies().isEmpty());
    }

    @Test
    @DisplayName("should write the section, disabled, into the default configuration file")
    void defaultFile() throws Exception {
      WarpConfig config = WarpConfig.load(directory);

      String written = Files.readString(directory.resolve("warp.conf"), StandardCharsets.UTF_8);
      assertTrue(written.contains("proxy-protocol {"), written);
      assertTrue(written.contains("trusted-proxies = []"), written);
      assertFalse(config.proxyProtocol().enabled());
    }
  }

  // ---------------------------------------------------------------------------
  // Enabled
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("enabled")
  class Enabled {

    @Test
    @DisplayName("should load trusted IPv4 and IPv6 ranges and single addresses")
    void trustedRanges() throws Exception {
      WarpConfig config =
          load(
              """
              forwarding { mode = "none" }
              proxy-protocol {
                enabled = true
                trusted-proxies = ["10.0.0.0/8", "2001:db8::/32", " 192.0.2.10 "]
              }
              """);

      ProxyProtocolConfig proxyProtocol = config.proxyProtocol();
      assertTrue(proxyProtocol.enabled());
      assertEquals(
          List.of(
              CidrRange.parse("10.0.0.0/8"),
              CidrRange.parse("2001:db8::/32"),
              CidrRange.parse("192.0.2.10")),
          proxyProtocol.trustedProxies().ranges());
      assertTrue(proxyProtocol.trustedProxies().contains(InetAddress.getByName("10.1.2.3")));
    }

    @Test
    @DisplayName("should refuse to load without trusted proxies, saying why")
    void requiresTrustedProxies() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class,
              () -> load("forwarding { mode = \"none\" }\nproxy-protocol { enabled = true }"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("proxy-protocol.trusted-proxies is empty"));
    }

    @Test
    @DisplayName("should refuse to load with an empty list of trusted proxies")
    void emptyTrustedProxies() {
      assertThrows(
          ConfigurationException.class,
          () ->
              load(
                  "forwarding { mode = \"none\" }\n"
                      + "proxy-protocol { enabled = true, trusted-proxies = [] }"));
    }

    @Test
    @DisplayName("should accept a range of every address, which it only warns about")
    void everyAddress() throws Exception {
      WarpConfig config =
          load(
              "forwarding { mode = \"none\" }\n"
                  + "proxy-protocol { enabled = true, trusted-proxies = [\"0.0.0.0/0\"] }");

      assertTrue(
          config.proxyProtocol().trustedProxies().contains(InetAddress.getByName("1.2.3.4")));
    }
  }

  // ---------------------------------------------------------------------------
  // Invalid lists
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("invalid trusted proxies")
  class InvalidTrustedProxies {

    @Test
    @DisplayName("should name an entry that is not an address or a range, even when disabled")
    void invalidEntry() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class,
              () ->
                  load(
                      "forwarding { mode = \"none\" }\n"
                          + "proxy-protocol { trusted-proxies = [\"10.0.0.0/8\", \"lb.local\"] }"));

      assertTrue(String.valueOf(error.getMessage()).contains("proxy-protocol.trusted-proxies:"));
      assertTrue(
          String.valueOf(error.getMessage()).contains("'lb.local'"),
          String.valueOf(error.getMessage()));
    }

    @Test
    @DisplayName("should refuse a single string instead of a list")
    void notAList() {
      ConfigurationException error =
          assertThrows(
              ConfigurationException.class,
              () ->
                  load(
                      "forwarding { mode = \"none\" }\n"
                          + "proxy-protocol { enabled = true, trusted-proxies = \"10.0.0.0/8\" }"));

      assertTrue(
          String.valueOf(error.getMessage()).contains("must be a list"),
          String.valueOf(error.getMessage()));
    }

    @Test
    @DisplayName("should refuse an empty entry")
    void emptyEntry() {
      assertThrows(
          ConfigurationException.class,
          () ->
              load(
                  "forwarding { mode = \"none\" }\n"
                      + "proxy-protocol { enabled = true, trusted-proxies = [\"\"] }"));
    }
  }

  // ---------------------------------------------------------------------------
  // Record
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("should not build an enabled configuration that trusts nobody")
  void recordInvariant() {
    assertThrows(
        IllegalArgumentException.class, () -> new ProxyProtocolConfig(true, CidrRanges.none()));
    assertFalse(ProxyProtocolConfig.DISABLED.enabled());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private WarpConfig load(String content) throws IOException, ConfigurationException {
    Files.writeString(directory.resolve("warp.conf"), content, StandardCharsets.UTF_8);
    return WarpConfig.load(directory);
  }
}
