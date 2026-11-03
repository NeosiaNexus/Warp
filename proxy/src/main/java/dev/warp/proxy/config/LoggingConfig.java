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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The {@code logging} section of {@code warp.conf}: the format of the log lines, which routine
 * events get a line each, and the levels of log sources kept across restarts. Also reads {@code
 * console.theme}, which logging needs before the rest of the configuration.
 *
 * @param format how log lines are written to standard output
 * @param connections whether players logging in, leaving and moving between servers are logged
 *     (Velocity's {@code log-player-connections}, on by default there too)
 * @param pings whether each server list ping is logged (Velocity's {@code show-ping-requests}, off
 *     by default there too)
 * @param commands whether each command a player sends is logged (Velocity's {@code
 *     log-command-executions}, off by default there too)
 * @param levels the level of each log source (a console tag such as {@code antibot}, or a logger
 *     name), in the order of the file: {@code logging.levels}
 */
public record LoggingConfig(
    LogFormat format,
    boolean connections,
    boolean pings,
    boolean commands,
    Map<String, String> levels) {

  /** The environment variable that sets the format, ahead of {@code logging.format}. */
  public static final String ENV_FORMAT = "WARP_LOG_FORMAT";

  /** The levels {@code logging.levels} takes. */
  public static final List<String> LEVELS = List.of("error", "warn", "info", "debug", "trace");

  /** The themes {@code console.theme} takes. */
  public static final List<String> THEMES = List.of("auto", "dark", "light", "colorblind");

  /** The settings of a {@code warp.conf} without {@code logging} section. */
  public static final LoggingConfig DEFAULTS =
      new LoggingConfig(LogFormat.TEXT, true, false, false);

  /** Copies the levels, so that the record stays immutable. */
  public LoggingConfig {
    levels = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(levels));
  }

  /**
   * Creates the section without levels.
   *
   * @param format how log lines are written to standard output
   * @param connections whether connections are logged
   * @param pings whether pings are logged
   * @param commands whether players' commands are logged
   */
  public LoggingConfig(LogFormat format, boolean connections, boolean pings, boolean commands) {
    this(format, connections, pings, commands, Map.of());
  }

  /**
   * Reads the section, and checks {@code console.theme}.
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
    Map<String, String> levels = new LinkedHashMap<>();
    CommentedConfigurationNode levelsNode = node.node("levels");
    if (!levelsNode.virtual() && !levelsNode.isMap()) {
      throw new ConfigurationException(
          "logging.levels must be a block, for example levels { antibot = debug }");
    }
    readLevels(levelsNode, "", levels);
    CommentedConfigurationNode console = root.node("console");
    if (!console.virtual() && !console.isMap()) {
      throw new ConfigurationException(
          "console must be a block, for example console { theme = light }");
    }
    String theme = WarpConfig.stringValue(console.node("theme"), "auto");
    if (!THEMES.contains(theme.strip().toLowerCase(Locale.ROOT))) {
      throw ConfigurationException.invalid("console.theme", String.join(", ", THEMES), theme);
    }
    return new LoggingConfig(
        format,
        WarpConfig.booleanValue(node.node("connections"), DEFAULTS.connections()),
        WarpConfig.booleanValue(node.node("pings"), DEFAULTS.pings()),
        WarpConfig.booleanValue(node.node("commands"), DEFAULTS.commands()),
        levels);
  }

  /**
   * Reads {@code logging.levels}, where an unquoted logger name ({@code dev.warp.proxy = debug})
   * nests blocks: their keys join back into the name.
   */
  private static void readLevels(ConfigurationNode node, String prefix, Map<String, String> levels)
      throws ConfigurationException {
    for (Map.Entry<Object, ? extends ConfigurationNode> child : node.childrenMap().entrySet()) {
      String name = prefix + child.getKey();
      ConfigurationNode value = child.getValue();
      if (value.isMap()) {
        readLevels(value, name + ".", levels);
        continue;
      }
      String level = WarpConfig.stringValue(value, "");
      if (!LEVELS.contains(level.strip().toLowerCase(Locale.ROOT))) {
        throw ConfigurationException.invalid(
            "logging.levels." + name, String.join(", ", LEVELS), level);
      }
      levels.put(name, level.strip().toLowerCase(Locale.ROOT));
    }
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
      String text = readEarly(directory, "logging", "format");
      return text == null ? DEFAULTS.format() : LogFormat.parse(text, "logging.format");
    } catch (Exception e) {
      return DEFAULTS.format();
    }
  }

  /**
   * Reads {@code console.theme} before logging starts, so that the banner already has the colours
   * of the theme. Never throws and logs nothing: the configuration's full load reports an invalid
   * value.
   *
   * @param directory the proxy's working directory
   * @return the theme as written, or {@code null} when unset or unreadable
   */
  public static @Nullable String selectTheme(Path directory) {
    try {
      return readEarly(directory, "console", "theme");
    } catch (Exception e) {
      return null;
    }
  }

  /** Reads one value of {@code warp.conf}, or {@code null} when it has none. */
  private static @Nullable String readEarly(Path directory, String... path) throws Exception {
    Path file = directory.resolve(WarpConfig.CONFIG_FILE);
    if (!Files.isRegularFile(file)) {
      return null;
    }
    ConfigurationNode value =
        HoconConfigurationLoader.builder().path(file).build().load().node((Object[]) path);
    return value.virtual() || value.isMap() || value.isList() ? null : value.getString();
  }
}
