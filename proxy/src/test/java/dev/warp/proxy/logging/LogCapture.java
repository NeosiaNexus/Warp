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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures the records the proxy logs, through a real {@link SinkAppender} and {@link
 * LogSinkRegistry}: what a plugin's sink would receive.
 */
public final class LogCapture implements AutoCloseable {

  private final LogSinkRegistry registry = new LogSinkRegistry();
  private final SinkAppender appender = new SinkAppender(registry);
  private static final Logger logger = LoggerFactory.getLogger(LogCapture.class);

  private final List<LogRecord> records = new CopyOnWriteArrayList<>();
  private final String barrier = "Log capture " + UUID.randomUUID() + " starts";
  private final CountDownLatch started = new CountDownLatch(1);

  /**
   * Starts capturing the INFO records and above that are logged from now on.
   *
   * <p>Logging is asynchronous, so records logged before the capture (by an earlier test, say) may
   * still be queued and would reach an appender installed meanwhile. The capture therefore logs a
   * record of its own and drops everything up to it: the queue keeps the order, so whatever comes
   * after was logged after the capture began.
   *
   * @throws AssertionError if the capture's own record does not come back within 10 seconds
   */
  public LogCapture() {
    appender.install();
    registry.register(
        "capture", LogLevel.INFO, 4096, this::accept, LogCapture.class.getClassLoader());
    logger.info(barrier);
    boolean reached;
    try {
      reached = started.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      reached = false;
    }
    if (!reached) {
      close();
      throw new AssertionError("the log capture did not receive its own first record");
    }
  }

  private void accept(LogRecord record) {
    if (started.getCount() == 0) {
      records.add(record);
    } else if (record.message().equals(barrier)) {
      started.countDown();
    }
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
