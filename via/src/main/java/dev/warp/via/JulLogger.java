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
package dev.warp.via;

import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.slf4j.Logger;

/**
 * The {@link java.util.logging.Logger} ViaVersion logs to, written to the plugin's SLF4J logger:
 * messages keep their level (Via's FINE becomes debug), their parameters and their exception.
 */
final class JulLogger extends java.util.logging.Logger {

  private final Logger base;

  JulLogger(Logger base) {
    super(base.getName(), null);
    this.base = base;
    // Every record reaches log(LogRecord); SLF4J decides what is printed.
    setLevel(Level.ALL);
  }

  @Override
  public void log(LogRecord record) {
    String message = format(record);
    Throwable error = record.getThrown();
    int level = record.getLevel().intValue();
    if (level >= Level.SEVERE.intValue()) {
      base.error(message, error);
    } else if (level >= Level.WARNING.intValue()) {
      base.warn(message, error);
    } else if (level >= Level.INFO.intValue()) {
      base.info(message, error);
    } else if (level >= Level.FINE.intValue()) {
      base.debug(message, error);
    } else {
      base.trace(message, error);
    }
  }

  private static String format(LogRecord record) {
    String message = String.valueOf(record.getMessage());
    Object[] parameters = record.getParameters();
    if (parameters == null || parameters.length == 0) {
      return message;
    }
    try {
      return MessageFormat.format(message, parameters);
    } catch (IllegalArgumentException e) {
      return message;
    }
  }
}
