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

/**
 * Registers a plugin's log sinks. Each plugin has its own, from {@link
 * dev.warp.api.plugin.PluginContext#logSinks()}: its sinks are removed when it is disabled or fails
 * to load, whether or not it unregisters them.
 *
 * <p>A sink receives every record the proxy writes, the proxy's own and every plugin's, at the
 * levels the proxy's logging configuration lets through (INFO and above by default), filtered by
 * the sink's threshold.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface LogSinks {

  /** The capacity of a sink's queue when none is given: a burst of records the sink can lag. */
  int DEFAULT_CAPACITY = 4096;

  /** The largest capacity of a sink's queue. */
  int MAX_CAPACITY = 65_536;

  /**
   * Registers a sink with a queue of {@value #DEFAULT_CAPACITY} records.
   *
   * @param threshold the least severe level the sink receives, such as {@link LogLevel#ERROR} for
   *     an error tracker
   * @param sink the sink
   * @return the registration; closing it removes the sink
   */
  default LogSinkSubscription register(LogLevel threshold, LogSink sink) {
    return register(threshold, DEFAULT_CAPACITY, sink);
  }

  /**
   * Registers a sink. The sink starts receiving the records logged from now on.
   *
   * <p>The records logged while the queue is full are dropped and counted ({@link
   * LogSinkSubscription#dropped()}): the code that logs never waits for a sink.
   *
   * @param threshold the least severe level the sink receives
   * @param capacity how many records the sink's queue holds, 1 to {@value #MAX_CAPACITY}
   * @param sink the sink
   * @return the registration; closing it removes the sink
   * @throws IllegalArgumentException if {@code capacity} is out of range
   */
  LogSinkSubscription register(LogLevel threshold, int capacity, LogSink sink);
}
