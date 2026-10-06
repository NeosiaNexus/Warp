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
package dev.warp.proxy.connection;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * Records what one class logs, at every level, while open.
 *
 * <p>The class gets a logger configuration of its own, synchronous (the production configuration
 * logs {@code dev.warp} asynchronously) and not additive, so that the events are recorded by the
 * time the code under test returns and stay out of the test output. Closing restores the previous
 * configuration.
 */
final class LogCapture implements AutoCloseable {

  private final LoggerContext context = (LoggerContext) LogManager.getContext(false);
  private final String loggerName;
  private final List<LogEvent> events = new CopyOnWriteArrayList<>();

  /**
   * Starts recording what {@code type} logs.
   *
   * @param type the class whose logger is recorded
   */
  LogCapture(Class<?> type) {
    this.loggerName = type.getName();
    Configuration config = context.getConfiguration();
    AbstractAppender appender =
        new AbstractAppender("capture-" + loggerName, null, null, true, Property.EMPTY_ARRAY) {
          @Override
          public void append(LogEvent event) {
            events.add(event.toImmutable());
          }
        };
    appender.start();
    LoggerConfig logger =
        LoggerConfig.newBuilder()
            .setLoggerName(loggerName)
            .setLevel(Level.ALL)
            .setAdditivity(false)
            .setConfig(config)
            .build();
    logger.addAppender(appender, Level.ALL, null);
    config.addLogger(loggerName, logger);
    context.updateLoggers();
  }

  /**
   * Returns the events recorded so far, oldest first.
   *
   * @return the events
   */
  List<LogEvent> events() {
    return List.copyOf(events);
  }

  @Override
  public void close() {
    context.getConfiguration().removeLogger(loggerName);
    context.updateLoggers();
  }
}
