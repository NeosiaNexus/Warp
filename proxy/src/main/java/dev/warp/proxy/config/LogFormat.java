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

import java.util.Locale;

/** How the proxy writes its log lines to standard output. */
public enum LogFormat {
  /** One line of text per record, for people reading a terminal. */
  TEXT,
  /**
   * One JSON object per line, for log collectors (Loki, Elasticsearch, Cloud Logging, Datadog):
   * timestamp, level, logger, thread, message, exception and context.
   */
  JSON;

  /** The system property {@code log4j2.xml} selects its console layout by. */
  public static final String SYSTEM_PROPERTY = "warp.log.format";

  /**
   * Returns the format named by a value of {@code logging.format} or {@code WARP_LOG_FORMAT}.
   *
   * @param value {@code text} or {@code json}, in any case
   * @param source where the value comes from, for the error message
   * @return the format
   * @throws ConfigurationException if the value names no format
   */
  public static LogFormat parse(String value, String source) throws ConfigurationException {
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "text" -> TEXT;
      case "json" -> JSON;
      default ->
          throw new ConfigurationException(source + " must be text or json, got '" + value + "'");
    };
  }

  /**
   * Returns the name the configuration writes.
   *
   * @return {@code text} or {@code json}
   */
  public String configName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
