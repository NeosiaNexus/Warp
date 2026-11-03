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
package dev.warp.proxy;

import dev.warp.proxy.config.ConfigurationException;
import dev.warp.proxy.config.LogFormat;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.config.WarpConfig;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

import org.apache.logging.log4j.LogManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap entry point for the Warp proxy.
 *
 * <p>Picks the log format, loads configuration, creates a {@link WarpServer}, and delegates
 * lifecycle management to it. This class exists solely to house {@code main}: all real logic lives
 * in {@link WarpServer}.
 */
public final class WarpBootstrap {

  private WarpBootstrap() {}

  /**
   * Launches the Warp proxy.
   *
   * @param args command-line arguments (currently unused)
   */
  public static void main(String[] args) {
    Path directory = Path.of(".");
    // Before the first logger: Log4j reads the format once, when it starts, so that every line has
    // the same format, the configuration's own included. Hence no static logger in this class.
    LogFormat format = selectLogFormat(directory, System::getenv);
    Logger logger = LoggerFactory.getLogger(WarpBootstrap.class);

    WarpConfig config;
    try {
      config = WarpConfig.load(directory);
    } catch (ConfigurationException e) {
      logger.error(describe(e));
      exit(StartupException.CONFIGURATION);
      return; // unreachable, but keeps the compiler happy
    }
    if (config.logging().format() != format) {
      logger.warn(
          "The log format is {}, not logging.format = {}: {} could not be read before logging"
              + " started",
          format.configName(),
          config.logging().format().configName(),
          directory.resolve("warp.conf"));
    }

    try {
      new WarpServer(config, directory).start();
    } catch (StartupException e) {
      // The operator's to fix, and the message says how: no stack trace.
      logger.error("Warp cannot start. {}", e.getMessage());
      exit(e.exitCode());
    } catch (RuntimeException e) {
      logger.error("Failed to start Warp", e);
      exit(1);
    }
  }

  /**
   * What the log says of an invalid configuration: every problem on its own line, with its file and
   * line, then what to do.
   *
   * @param e the problems
   * @return the message
   */
  static String describe(ConfigurationException e) {
    StringBuilder text =
        new StringBuilder(
            e.problems().size() == 1
                ? "Warp cannot start: its configuration has a problem."
                : "Warp cannot start: its configuration has " + e.problems().size() + " problems.");
    for (ConfigurationException.Problem problem : e.problems()) {
      text.append(System.lineSeparator()).append("  - ").append(problem.format());
    }
    return text.append(System.lineSeparator())
        .append(
            "Fix them, then start Warp again: it does not run on a configuration it cannot read.")
        .toString();
  }

  /**
   * Picks the log format ({@code WARP_LOG_FORMAT}, else {@code logging.format}, else text) and
   * hands it to {@code log4j2.xml} through the {@value LogFormat#SYSTEM_PROPERTY} system property.
   * Must run before Log4j starts.
   *
   * @param directory the proxy's working directory, holding {@code warp.conf}
   * @param env reads an environment variable, {@code null} when unset
   * @return the format picked
   */
  static LogFormat selectLogFormat(Path directory, UnaryOperator<@Nullable String> env) {
    LogFormat format = LoggingConfig.selectFormat(directory, env);
    System.setProperty(LogFormat.SYSTEM_PROPERTY, format.configName());
    return format;
  }

  /**
   * Exits once the logs are written: Log4j's own shutdown hook is off (see {@code log4j2.xml}), so
   * that the proxy's shutdown is logged, and asynchronous loggers would lose these last lines.
   */
  private static void exit(int status) {
    LogManager.shutdown();
    System.exit(status);
  }
}
