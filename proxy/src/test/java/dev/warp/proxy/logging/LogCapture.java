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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Captures the records the proxy logs, through a real {@link SinkAppender} and {@link
 * LogSinkRegistry}: what a plugin's sink would receive.
 */
public final class LogCapture implements AutoCloseable {

  private final LogSinkRegistry registry = new LogSinkRegistry();
  private final SinkAppender appender = new SinkAppender(registry);
  private final List<LogRecord> records = new CopyOnWriteArrayList<>();

  /** Starts capturing the INFO records and above. */
  public LogCapture() {
    appender.install();
    registry.register(
        "capture", LogLevel.INFO, 4096, records::add, LogCapture.class.getClassLoader());
  }

  /**
   * Waits for a record, up to 10 seconds: logging is asynchronous.
   *
   * @param matching what the record must match
   * @return the first record that matches
   * @throws AssertionError if none came
   * @throws InterruptedException if interrupted while waiting
   */
  public LogRecord await(Predicate<LogRecord> matching) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (true) {
      Optional<LogRecord> found = records.stream().filter(matching).findFirst();
      if (found.isPresent()) {
        return found.get();
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError("no matching record within 10 s, got " + messages());
      }
      Thread.sleep(5);
    }
  }

  /**
   * Returns whether a record matches after waiting a moment for records still on their way.
   *
   * @param matching what the record must match
   * @return {@code true} if one matched
   * @throws InterruptedException if interrupted while waiting
   */
  public boolean anyAfterSettling(Predicate<LogRecord> matching) throws InterruptedException {
    Thread.sleep(300);
    return records.stream().anyMatch(matching);
  }

  /**
   * Returns how many records match so far.
   *
   * @param matching what a record must match
   * @return the count
   */
  public long count(Predicate<LogRecord> matching) {
    return records.stream().filter(matching).count();
  }

  private List<String> messages() {
    return records.stream().map(LogRecord::message).toList();
  }

  @Override
  public void close() {
    appender.uninstall();
    registry.close();
  }
}
