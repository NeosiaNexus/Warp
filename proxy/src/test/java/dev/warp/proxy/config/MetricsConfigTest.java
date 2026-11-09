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

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

@DisplayName("management.metrics")
class MetricsConfigTest {

  private static CommentedConfigurationNode node(String hocon) throws Exception {
    return HoconConfigurationLoader.builder()
        .source(() -> new java.io.BufferedReader(new java.io.StringReader(hocon)))
        .build()
        .load();
  }

  @Test
  @DisplayName("should be on by default, without a token, pinging backends every 15 s")
  void defaults() throws Exception {
    MetricsConfig metrics = MetricsConfig.parse(node(""), false);

    assertEquals(MetricsConfig.DEFAULTS, metrics);
    assertTrue(metrics.enabled());
    assertFalse(metrics.requireToken());
    assertEquals(Duration.ofSeconds(15), metrics.serverPingInterval());
    assertEquals("on, token not required, server pings every 15s", metrics.toString());
  }

  @Test
  @DisplayName("should read every setting")
  void values() throws Exception {
    MetricsConfig metrics =
        MetricsConfig.parse(
            node("enabled = false\nrequire-token = true\nserver-ping-interval = 0"), true);

    assertFalse(metrics.enabled());
    assertTrue(metrics.requireToken());
    assertEquals(Duration.ZERO, metrics.serverPingInterval());
    assertEquals("off", metrics.toString());
  }

  @Test
  @DisplayName("should refuse require-token without a token, and an interval out of range")
  void errors() throws Exception {
    ConfigurationException noToken =
        assertThrows(
            ConfigurationException.class,
            () -> MetricsConfig.parse(node("require-token = true"), false));
    assertTrue(
        String.valueOf(noToken.getMessage()).contains("management.metrics.require-token"),
        noToken.getMessage());
    assertThrows(
        ConfigurationException.class,
        () -> MetricsConfig.parse(node("server-ping-interval = 100ms"), false));
    assertThrows(
        ConfigurationException.class,
        () -> MetricsConfig.parse(node("server-ping-interval = 2h"), false));
  }

  @Test
  @DisplayName("should know when the metrics are open to other machines")
  void exposed() throws Exception {
    ManagementConfig loopback =
        new ManagementConfig(
            true, new java.net.InetSocketAddress("127.0.0.1", 9901), null, null, true);
    ManagementConfig open =
        new ManagementConfig(
            true, new java.net.InetSocketAddress("0.0.0.0", 9901), null, null, true);
    ManagementConfig withToken =
        new ManagementConfig(
            true,
            new java.net.InetSocketAddress("0.0.0.0", 9901),
            null,
            "t",
            true,
            null,
            RemoteConsoleConfig.DISABLED,
            new MetricsConfig(true, true, Duration.ZERO));

    assertFalse(loopback.metricsExposed());
    assertTrue(open.metricsExposed());
    assertFalse(withToken.metricsExposed());
  }
}
