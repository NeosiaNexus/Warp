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
import dev.warp.proxy.config.WarpConfig;

import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap entry point for the Warp proxy.
 *
 * <p>Loads configuration, creates a {@link WarpServer}, and delegates lifecycle management to it.
 * This class exists solely to house {@code main} — all real logic lives in {@link WarpServer}.
 */
public final class WarpBootstrap {

  private static final Logger logger = LoggerFactory.getLogger(WarpBootstrap.class);

  private WarpBootstrap() {}

  /**
   * Launches the Warp proxy.
   *
   * @param args command-line arguments (currently unused)
   */
  public static void main(String[] args) {
    Path directory = Path.of(".");
    WarpConfig config;
    try {
      config = WarpConfig.load(directory);
    } catch (ConfigurationException e) {
      logger.error("Failed to load configuration: {}", e.getMessage());
      exit(1);
      return; // unreachable, but keeps the compiler happy
    }

    try {
      new WarpServer(config, directory).start();
    } catch (RuntimeException e) {
      logger.error("Failed to start Warp", e);
      exit(1);
    }
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
