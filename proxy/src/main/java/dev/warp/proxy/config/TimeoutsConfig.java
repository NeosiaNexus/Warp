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
 * @param read the longest a logged-in player's connection, or its connection to a backend, may stay
 *     silent before it is closed
 */
public record TimeoutsConfig(Duration connect, Duration read) {

  /** Velocity's {@code connection-timeout} and Warp's former fixed value. */
  static final Duration DEFAULT_CONNECT = Duration.ofSeconds(5);

  /**
   * Shorter than the client's own 30 s: a backend that stalls is closed, and its player sent to a
   * fallback server, before the client gives up with "Timed out" (Velocity's 30 s matches the
   * client's exactly, and is then too late to act on, PaperMC/Velocity#1819).
   */
  static final Duration DEFAULT_READ = Duration.ofSeconds(25);

  /** The shortest read timeout: below, a backend's 15 s keep-alive rhythm cannot keep it open. */
  static final Duration MIN_READ = Duration.ofSeconds(1);

  /** The settings of a {@code warp.conf} without {@code timeouts} section. */
  public static final TimeoutsConfig DEFAULTS = new TimeoutsConfig(DEFAULT_CONNECT, DEFAULT_READ);

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
    return new TimeoutsConfig(connect, read);
  }

  @Override
  public String toString() {
    return "connect " + Durations.format(connect) + ", read " + Durations.format(read);
  }
}
