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

import java.time.Duration;

import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * The {@code management.metrics} section: whether {@code GET /metrics} answers, whether it needs
 * the bearer token, and how often the proxy pings its backends for {@code warp_server_rtt_seconds}
 * and {@code warp_server_health}. The registry exists either way: plugins register metrics whether
 * or not they are exposed.
 *
 * @param enabled whether {@code GET /metrics} answers
 * @param requireToken whether {@code GET /metrics} needs {@code Authorization: Bearer} with the
 *     management token
 * @param serverPingInterval how often each backend is pinged for its round trip and health; {@link
 *     Duration#ZERO} turns the pings off
 */
public record MetricsConfig(boolean enabled, boolean requireToken, Duration serverPingInterval) {

  /**
   * The defaults: exposed without a token (the default bind is loopback), backends pinged every 15
   * s.
   */
  public static final MetricsConfig DEFAULTS =
      new MetricsConfig(true, false, Duration.ofSeconds(15));

  static final Duration MIN_PING_INTERVAL = Duration.ofSeconds(1);
  static final Duration MAX_PING_INTERVAL = Duration.ofHours(1);

  /**
   * Reads the section.
   *
   * @param node the {@code management.metrics} node
   * @param hasToken whether a management token is configured, which {@code require-token} needs
   * @return the section's values
   * @throws ConfigurationException if {@code require-token} is on without a token, or the ping
   *     interval is out of range
   */
  static MetricsConfig parse(CommentedConfigurationNode node, boolean hasToken)
      throws ConfigurationException {
    boolean enabled = WarpConfig.booleanValue(node.node("enabled"), DEFAULTS.enabled());
    boolean requireToken =
        WarpConfig.booleanValue(node.node("require-token"), DEFAULTS.requireToken());
    if (requireToken && !hasToken) {
      throw new ConfigurationException(
          "management.metrics.require-token is true but no management token is configured: set"
              + " management.token-file or the WARP_MANAGEMENT_TOKEN variable");
    }
    Duration interval =
        Durations.parse(
            node.node("server-ping-interval"),
            "management.metrics.server-ping-interval",
            DEFAULTS.serverPingInterval());
    if (!interval.isZero()
        && (interval.compareTo(MIN_PING_INTERVAL) < 0
            || interval.compareTo(MAX_PING_INTERVAL) > 0)) {
      throw new ConfigurationException(
          "management.metrics.server-ping-interval must be 0 (off) or 1s to 1h, found "
              + DrainConfig.format(interval));
    }
    return new MetricsConfig(enabled, requireToken, interval);
  }

  @Override
  public String toString() {
    if (!enabled) {
      return "off";
    }
    return "on, token "
        + (requireToken ? "required" : "not required")
        + ", server pings "
        + (serverPingInterval.isZero() ? "off" : "every " + DrainConfig.format(serverPingInterval));
  }
}
