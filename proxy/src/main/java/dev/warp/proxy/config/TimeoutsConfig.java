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
 * The {@code timeouts} section of {@code warp.conf}: how long the proxy waits on a backend that
 * does not answer and on a logged-in connection that went quiet. The deadlines before login
 * (handshake, server list ping, login) are part of {@code limits}.
 *
 * @param connect the longest a TCP connection to a backend server may take to open
 * @param read the longest a logged-in player's connection may stay silent before it is closed
 * @param backendRead the longest a connection to a backend server may stay silent before it is
 *     closed, in every state: while it logs the player in, until its first Join Game, and in play
 * @param ping the longest {@code RegisteredServer.ping()} waits for a backend's answer, connection
 *     included, when its caller gives no time-out
 */
public record TimeoutsConfig(Duration connect, Duration read, Duration backendRead, Duration ping) {

  /** Velocity's {@code connection-timeout} and Warp's former fixed value. */
  static final Duration DEFAULT_CONNECT = Duration.ofSeconds(5);

  /**
   * Shorter than the client's own 30 s: the proxy notices a dead client before a live client gives
   * up on the proxy with "Timed out".
   */
  static final Duration DEFAULT_READ = Duration.ofSeconds(25);

  /**
   * Velocity's and BungeeCord's 30 s. A backend may take its time to log a player in (preparing a
   * new player, under load): the proxy's keep-alives keep the waiting client connected meanwhile,
   * so the wait does not have to fit within the client's own 30 s.
   */
  static final Duration DEFAULT_BACKEND_READ = Duration.ofSeconds(30);

  /** The shortest read timeout, for clients and backends alike. */
  static final Duration MIN_READ = Duration.ofSeconds(1);

  /** Velocity's 5 s when a plugin pings a server, and Warp's former fixed value. */
  static final Duration DEFAULT_PING = Duration.ofSeconds(5);

  /** The settings of a {@code warp.conf} without {@code timeouts} section. */
  public static final TimeoutsConfig DEFAULTS =
      new TimeoutsConfig(DEFAULT_CONNECT, DEFAULT_READ, DEFAULT_BACKEND_READ, DEFAULT_PING);

  /**
   * Reads the section.
   *
   * @param root the configuration's root node
   * @return the section's values
   * @throws ConfigurationException if a value is invalid
   */
  static TimeoutsConfig parse(CommentedConfigurationNode root) throws ConfigurationException {
    CommentedConfigurationNode node = root.node("timeouts");
    if (!node.virtual() && !node.isMap()) {
      throw new ConfigurationException(
          "timeouts must be a block, for example timeouts { connect = 5s }");
    }
    Duration connect = Durations.parse(node.node("connect"), "timeouts.connect", DEFAULT_CONNECT);
    if (connect.isZero() || connect.toMillis() > Integer.MAX_VALUE) {
      throw new ConfigurationException(
          "timeouts.connect must be more than zero and at most 24d, got "
              + Durations.format(connect));
    }
    Duration read = Durations.parse(node.node("read"), "timeouts.read", DEFAULT_READ);
    if (read.compareTo(MIN_READ) < 0) {
      throw new ConfigurationException(
          "timeouts.read must be at least 1s, got " + Durations.format(read));
    }
    Duration backendRead =
        Durations.parse(node.node("backend-read"), "timeouts.backend-read", DEFAULT_BACKEND_READ);
    if (backendRead.compareTo(MIN_READ) < 0) {
      throw new ConfigurationException(
          "timeouts.backend-read must be at least 1s, got " + Durations.format(backendRead));
    }
    Duration ping = Durations.parse(node.node("ping"), "timeouts.ping", DEFAULT_PING);
    if (ping.isZero()) {
      throw new ConfigurationException("timeouts.ping must be more than zero, got 0");
    }
    return new TimeoutsConfig(connect, read, backendRead, ping);
  }

  @Override
  public String toString() {
    return "connect "
        + Durations.format(connect)
        + ", read "
        + Durations.format(read)
        + ", backend-read "
        + Durations.format(backendRead)
        + ", ping "
        + Durations.format(ping);
  }
}
