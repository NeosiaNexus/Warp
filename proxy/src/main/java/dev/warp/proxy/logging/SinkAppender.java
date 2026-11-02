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
package dev.warp.proxy.logging;

import dev.warp.api.logging.LogLevel;
import dev.warp.api.logging.LogRecord;

import java.time.Instant;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Log4j appender that hands every record to the {@link LogSinkRegistry}: the bridge between the
 * proxy's logging and the plugins' sinks, which see SLF4J and the Warp API only.
 *
 * <p>It runs where Log4j calls its appenders (the asynchronous logging thread), copies what the
 * record holds (Log4j reuses its event objects) and queues it, without ever blocking. Nothing is
 * copied while no sink would receive the record.
 */
public final class SinkAppender extends AbstractAppender {

  private static final Logger logger = LoggerFactory.getLogger(SinkAppender.class);

  private final LogSinkRegistry registry;
  private volatile @Nullable LoggerContext installedIn;

  /**
   * Creates an appender, not yet installed.
   *
   * @param registry the sinks it delivers to
   */
  public SinkAppender(LogSinkRegistry registry) {
    // A name of its own: two proxies in one JVM (tests) each install theirs.
    super(
        "WarpLogSinks-" + Integer.toHexString(System.identityHashCode(registry)),
        null,
        null,
        true,
        Property.EMPTY_ARRAY);
    this.registry = registry;
  }

  @Override
  public void append(LogEvent event) {
    LogLevel level = level(event.getLevel());
    if (registry.wants(level)) {
      registry.publish(record(event, level), event.getThreadId());
    }
  }

  /**
   * Copies a Log4j event into a record.
   *
   * @param event the event, which Log4j may reuse once this returns
   * @param level its level
   * @return the record
   */
  static LogRecord record(LogEvent event, LogLevel level) {
    var instant = event.getInstant();
    String loggerName = event.getLoggerName();
    String threadName = event.getThreadName();
    return new LogRecord(
        Instant.ofEpochSecond(instant.getEpochSecond(), instant.getNanoOfSecond()),
        level,
        loggerName == null ? "" : loggerName,
        threadName == null ? "" : threadName,
        event.getMessage().getFormattedMessage(),
        event.getThrown(),
        event.getContextData().toMap());
  }

  /**
   * Maps a Log4j level to the API's: {@code FATAL} is {@code ERROR}, {@code ALL} is {@code TRACE}.
   *
   * @param level the Log4j level
   * @return the API level
   */
  static LogLevel level(Level level) {
    if (level.isMoreSpecificThan(Level.ERROR)) {
      return LogLevel.ERROR;
    }
    if (level.isMoreSpecificThan(Level.WARN)) {
      return LogLevel.WARN;
    }
    if (level.isMoreSpecificThan(Level.INFO)) {
      return LogLevel.INFO;
    }
    if (level.isMoreSpecificThan(Level.DEBUG)) {
      return LogLevel.DEBUG;
    }
    return LogLevel.TRACE;
  }

  // ---------------------------------------------------------------------------
  // Installation
  // ---------------------------------------------------------------------------

  /**
   * Adds the appender to the running Log4j configuration: to the root logger and to every logger
   * that does not pass its records up, so that each record reaches it exactly once. Does nothing,
   * with a warning, when SLF4J is not backed by Log4j Core.
   */
  public void install() {
    if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
      logger.warn("Log sinks receive nothing: the logging backend is not Log4j Core");
      return;
    }
    Configuration configuration = context.getConfiguration();
    start();
    configuration.addAppender(this);
    configuration.getRootLogger().addAppender(this, null, null);
    for (LoggerConfig loggerConfig : configuration.getLoggers().values()) {
      if (!loggerConfig.isAdditive() && !loggerConfig.getName().isEmpty()) {
        loggerConfig.addAppender(this, null, null);
      }
    }
    context.updateLoggers();
    installedIn = context;
  }

  /** Removes the appender from the configuration it was installed in, and stops it. */
  public void uninstall() {
    LoggerContext context = installedIn;
    installedIn = null;
    if (context == null) {
      return;
    }
    Configuration configuration = context.getConfiguration();
    configuration.getRootLogger().removeAppender(getName());
    for (LoggerConfig loggerConfig : configuration.getLoggers().values()) {
      loggerConfig.removeAppender(getName());
    }
    context.updateLoggers();
    stop();
  }
}
