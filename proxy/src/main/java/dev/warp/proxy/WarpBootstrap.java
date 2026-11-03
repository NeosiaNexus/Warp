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
import dev.warp.proxy.console.ConsoleOutput;
import dev.warp.proxy.console.Summaries;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.io.IoBuilder;
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
    // Before anything touches them: java.util.logging (JLine, some libraries) goes through Log4j,
    // rather than printing its own two-line records to standard error; System.console() is the
    // JDK's plain implementation, not its built-in JLine, which would compete with Warp's.
    setDefault("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
    setDefault("jdk.console", "java.base");
    Path directory = Path.of(".");
    // Before the first logger: Log4j reads the format once, when it starts, so that every line has
    // the same format, the configuration's own included. Hence no static logger in this class.
    LogFormat format = selectLogFormat(directory, System::getenv);
    Logger logger = LoggerFactory.getLogger(WarpBootstrap.class);
    captureStandardStreams(logger);
    // First, before the configuration and its warnings: the banner (in the log file, the line
    // "Starting Warp <version> (commit: <sha>, branch: <branch>)").
    LogManager.getLogger(WarpBootstrap.class)
        .info(
            Summaries.banner(
                WarpBuildInfo.VERSION, WarpBuildInfo.GIT_COMMIT, WarpBuildInfo.GIT_BRANCH));
    ConsoleOutput console = ConsoleOutput.get();
    String consoleWarning = console == null ? null : console.mode().warning();
    if (consoleWarning != null) {
      logger.warn(consoleWarning);
    }

    WarpConfig config;
    try {
      config = WarpConfig.load(directory);
    } catch (ConfigurationException e) {
      logger.error("Failed to load configuration: {}", e.getMessage());
      exit(1);
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
      WarpServer server = new WarpServer(config, directory);
      server.start();
      server.startConsole();
    } catch (RuntimeException e) {
      logger.error("Failed to start Warp", e);
      exit(1);
    }
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

  private static void setDefault(String property, String value) {
    if (System.getProperty(property) == null) {
      System.setProperty(property, value);
    }
  }

  /**
   * Sends what is printed to {@link System#out} and {@link System#err} (by a library, a plugin, the
   * JVM's default handler of uncaught exceptions) to the log: it reaches the log file, keeps the
   * JSON format one object per line, and never breaks the line being typed at the console. Uncaught
   * exceptions are logged as errors, with their thread.
   */
  private static void captureStandardStreams(Logger logger) {
    System.setOut(
        IoBuilder.forLogger(LogManager.getLogger("STDOUT"))
            .setLevel(Level.INFO)
            .buildPrintStream());
    System.setErr(
        IoBuilder.forLogger(LogManager.getLogger("STDERR"))
            .setLevel(Level.WARN)
            .buildPrintStream());
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) ->
            logger.error("Uncaught exception in thread {}", thread.getName(), error));
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
