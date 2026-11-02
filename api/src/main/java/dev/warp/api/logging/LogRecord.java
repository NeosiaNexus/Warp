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
package dev.warp.api.logging;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One log record, as a {@link LogSink} receives it: what the proxy's JSON log line holds, with the
 * exception itself rather than its text.
 *
 * @param timestamp when the record was logged
 * @param level its severity
 * @param logger the name of the logger: a class name for the proxy, the plugin's identifier for the
 *     logger a plugin is given
 * @param thread the name of the thread that logged it
 * @param message the message, its arguments filled in
 * @param thrown the exception logged with it, or {@code null} for none
 * @param context the record's context: SLF4J's MDC and key-value pairs, such as {@code player} (a
 *     player's name) and {@code session} (the identifier of one of its connections) on the records
 *     about a player; empty for none
 */
public record LogRecord(
    Instant timestamp,
    LogLevel level,
    String logger,
    String thread,
    String message,
    @Nullable Throwable thrown,
    Map<String, String> context) {

  /** Validates the record and copies its context, which is then unmodifiable. */
  public LogRecord {
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(logger, "logger");
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(message, "message");
    context = Map.copyOf(context);
  }
}
