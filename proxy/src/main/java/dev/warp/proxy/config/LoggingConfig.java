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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The {@code logging} section of {@code warp.conf}: the format of the log lines, and which routine
 * events get a line each.
 *
 * @param format how log lines are written to standard output
 * @param connections whether players logging in, leaving and moving between servers are logged
 *     (Velocity's {@code log-player-connections}, on by default there too)
 * @param pings whether each server list ping is logged (Velocity's {@code show-ping-requests}, off
 *     by default there too)
 * @param commands whether each command a player sends is logged (Velocity's {@code
 *     log-command-executions}, off by default there too)
 */
public record LoggingConfig(
    LogFormat format, boolean connections, boolean pings, boolean commands) {

  /** The environment variable that sets the format, ahead of {@code logging.format}. */
  public static final String ENV_FORMAT = "WARP_LOG_FORMAT";

  /** The settings of a {@code warp.conf} without {@code logging} section. */
  public static final LoggingConfig DEFAULTS =
      new LoggingConfig(LogFormat.TEXT, true, false, false);

  /**
   * Reads the section.
   *
   * @param root the configuration's root node
   * @param env reads an environment variable, {@code null} when unset
   * @return the section's values
   * @throws ConfigurationException if a value is invalid, {@code WARP_LOG_FORMAT} included
   */
  static LoggingConfig parse(CommentedConfigurationNode root, UnaryOperator<@Nullable String> env)
      throws ConfigurationException {
    CommentedConfigurationNode node = root.node("logging");
    if (!node.virtual() && !node.isMap()) {
      throw new ConfigurationException(
          "logging must be a block, for example logging { format = json }");
    }
    String fromEnv = env.apply(ENV_FORMAT);
    LogFormat format =
        fromEnv != null && !fromEnv.isBlank()
            ? LogFormat.parse(fromEnv, ENV_FORMAT)
            : LogFormat.parse(
                WarpConfig.stringValue(node.node("format"), DEFAULTS.format().configName()),
                "logging.format");
    return new LoggingConfig(
        format,
        WarpConfig.booleanValue(node.node("connections"), DEFAULTS.connections()),
        WarpConfig.booleanValue(node.node("pings"), DEFAULTS.pings()),
        WarpConfig.booleanValue(node.node("commands"), DEFAULTS.commands()));
  }

  /**
   * Picks the log format before logging starts, so that every line, the first included, has the
   * same format: {@code WARP_LOG_FORMAT}, else {@code logging.format} of {@code warp.conf}, else
   * text. Never throws and logs nothing: an invalid value falls back to text here, and fails the
   * configuration's full load, which reports it.
   *
   * @param directory the proxy's working directory
   * @param env reads an environment variable, {@code null} when unset
   * @return the format
   */
  public static LogFormat selectFormat(Path directory, UnaryOperator<@Nullable String> env) {
    try {
      String fromEnv = env.apply(ENV_FORMAT);
      if (fromEnv != null && !fromEnv.isBlank()) {
        return LogFormat.parse(fromEnv, ENV_FORMAT);
      }
      Path file = directory.resolve(WarpConfig.CONFIG_FILE);
      if (!Files.isRegularFile(file)) {
        return DEFAULTS.format();
      }
      ConfigurationNode value =
          HoconConfigurationLoader.builder().path(file).build().load().node("logging", "format");
      String text = value.virtual() || value.isMap() || value.isList() ? null : value.getString();
      return text == null ? DEFAULTS.format() : LogFormat.parse(text, "logging.format");
    } catch (Exception e) {
      return DEFAULTS.format();
    }
  }
}
