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
 * Receives log records, to forward them elsewhere: an error tracker such as Sentry, a log store
 * such as Loki, a webhook. Registered through {@link LogSinks#register}.
 *
 * <p>Each sink has a thread of its own, a virtual thread whose context class loader is its plugin's
 * (libraries that look classes up through it find the plugin's). It receives its records one at a
 * time, in the order they were logged; it may block, on a network call for instance, without
 * delaying the proxy or the other sinks: while it does, its records wait in its queue, and once the
 * queue is full the newest are dropped, and counted.
 *
 * <p>What a sink logs itself, from that thread, reaches no sink, so that a sink logging its own
 * failures cannot feed itself.
 */
@FunctionalInterface
public interface LogSink {

  /**
   * Handles one record. A record is not retried: an exception thrown here counts the record as
   * failed ({@link LogSinkSubscription#failed()}), is logged at most once a minute, and the sink
   * keeps receiving the next records.
   *
   * @param record the record
   * @throws Exception if the record could not be handled
   */
  void accept(LogRecord record) throws Exception;
}
