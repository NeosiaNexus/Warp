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

import dev.warp.proxy.network.CidrRanges;

import java.time.Duration;
import java.util.List;

/**
 * The {@code limits} section of the configuration: how many connections, server list pings and
 * logins one client address may make, and how many authentications the proxy runs at once.
 *
 * @param enabled whether any limit applies
 * @param exempt addresses no per-address limit applies to
 * @param connections new TCP connections per address
 * @param statusPings server list pings per address
 * @param logins login attempts per address
 * @param maxConcurrentLoginsPerAddress logins one address may have in progress at once; 0 for no
 *     limit
 * @param maxConcurrentAuthentications authentications with the session server the proxy runs at
 *     once, RSA decryption included; 0 for no limit
 * @param loginTimeout the longest a login may take before the player is logged in; zero for none
 * @param reportInterval how often the counts of refusals are logged
 * @param maxPendingConnections connections that are not logged in yet (server list pings, logins in
 *     progress, connections that sent nothing) the whole proxy holds at once, whatever their
 *     address; 0 for no limit
 * @param handshakeTimeout the longest a connection may take to send its handshake, PROXY protocol
 *     header included, counted from the accept whatever it sends meanwhile; zero for none
 * @param statusTimeout the longest a server list ping may take, from its handshake to its pong;
 *     zero for none
 */
public record LimitsConfig(
    boolean enabled,
    CidrRanges exempt,
    Rate connections,
    Rate statusPings,
    Rate logins,
    int maxConcurrentLoginsPerAddress,
    int maxConcurrentAuthentications,
    Duration loginTimeout,
    Duration reportInterval,
    int maxPendingConnections,
    Duration handshakeTimeout,
    Duration statusTimeout) {

  /** The limits Warp applies when the configuration does not say otherwise. */
  public static final LimitsConfig DEFAULTS =
      new LimitsConfig(
          true,
          CidrRanges.parse(List.of("127.0.0.0/8", "::1/128")),
          new Rate(10, Duration.ofMillis(200)),
          new Rate(10, Duration.ofSeconds(1)),
          new Rate(2, Duration.ofSeconds(3)),
          2,
          256,
          Duration.ofSeconds(30),
          Duration.ofMinutes(1),
          8192,
          Duration.ofSeconds(5),
          Duration.ofSeconds(5));

  /** No limit at all. */
  public static final LimitsConfig DISABLED =
      new LimitsConfig(
          false,
          CidrRanges.none(),
          Rate.UNLIMITED,
          Rate.UNLIMITED,
          Rate.UNLIMITED,
          0,
          0,
          Duration.ZERO,
          Duration.ofMinutes(1),
          0,
          Duration.ZERO,
          Duration.ZERO);

  /**
   * Checks the values.
   *
   * @throws IllegalArgumentException if a count or a duration is negative, or the report interval
   *     is not positive
   */
  public LimitsConfig {
    if (maxConcurrentLoginsPerAddress < 0
        || maxConcurrentAuthentications < 0
        || maxPendingConnections < 0) {
      throw new IllegalArgumentException("concurrency limits must not be negative");
    }
    if (loginTimeout.isNegative() || handshakeTimeout.isNegative() || statusTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "timeouts must not be negative, got "
              + loginTimeout
              + ", "
              + handshakeTimeout
              + ", "
              + statusTimeout);
    }
    if (reportInterval.isNegative() || reportInterval.isZero()) {
      throw new IllegalArgumentException("reportInterval must be positive, got " + reportInterval);
    }
  }

  /**
   * A token bucket: {@code burst} at once, then one every {@code interval}. A zero interval means
   * no limit, as Velocity's {@code login-ratelimit = 0} does.
   *
   * @param burst how many an address may make at once; at least 1
   * @param interval how long an address waits for one more; zero for no limit
   */
  public record Rate(int burst, Duration interval) {

    /** No limit. */
    public static final Rate UNLIMITED = new Rate(1, Duration.ZERO);

    /**
     * Checks the values.
     *
     * @throws IllegalArgumentException if {@code burst} is below 1 or {@code interval} negative
     */
    public Rate {
      if (burst < 1) {
        throw new IllegalArgumentException("burst must be at least 1, got " + burst);
      }
      if (interval.isNegative()) {
        throw new IllegalArgumentException("interval must not be negative, got " + interval);
      }
    }

    /**
     * Returns whether this rate limits anything.
     *
     * @return {@code false} for a zero interval
     */
    public boolean limited() {
      return !interval.isZero();
    }

    /**
     * Describes the rate for the log, such as {@code "10 at once, then 1 every 200ms"}.
     *
     * @return the description
     */
    @Override
    public String toString() {
      if (!limited()) {
        return "unlimited";
      }
      return burst + " at once, then 1 every " + format(interval);
    }
  }

  /** Formats a duration as its configuration would: {@code 200ms}, {@code 3s}, {@code 1m}. */
  static String format(Duration duration) {
    long millis = duration.toMillis();
    if (millis % 60_000 == 0 && millis != 0) {
      return millis / 60_000 + "m";
    }
    if (millis % 1000 == 0) {
      return millis / 1000 + "s";
    }
    return millis + "ms";
  }
}
