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
package dev.warp.proxy.management.console;

import dev.warp.proxy.logging.LogCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

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
 * The log as the remote console sees it: a Log4j appender that numbers every line, keeps the last
 * ones for a replay, and hands each to the attached clients.
 *
 * <p>Lines are written by the logging thread, under a lock no reader ever takes: readers (the
 * management event loop, replaying) read the published slots without waiting, and skip a slot
 * already overwritten. A client attaches before it reads the replay, so that every line reaches it
 * once: either in the replay, or live, or both, the client then dropping what it already sent.
 */
final class LogHistory {

  private static final Logger logger = LoggerFactory.getLogger(LogHistory.class);

  /** A client of the live log. */
  interface Subscriber {

    /**
     * Receives a line, on the logging thread: must neither block nor throw.
     *
     * @param record the line
     */
    void offer(ConsoleRecord record);
  }

  private final AtomicReferenceArray<@Nullable ConsoleRecord> slots;
  private final Redactor redactor;
  private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
  private final ReentrantLock writing = new ReentrantLock();
  private final Tap tap;

  /** The number of the last line published; 0 before the first. */
  private volatile long published;

  /**
   * Creates the history.
   *
   * @param capacity how many lines are kept for a replay; 0 keeps none
   * @param redactor masks the secrets of every line
   */
  LogHistory(int capacity, Redactor redactor) {
    this.slots = new AtomicReferenceArray<>(Math.max(1, capacity));
    this.redactor = redactor;
    this.tap = new Tap(this);
  }

  // ---------------------------------------------------------------------------
  // Writing
  // ---------------------------------------------------------------------------

  /**
   * Numbers a line, keeps it and hands it to the clients. Called by the appender.
   *
   * @param event the line, which Log4j may reuse once this returns
   */
  void append(LogEvent event) {
    ConsoleRecord record;
    writing.lock();
    try {
      long id = published + 1;
      record = ConsoleRecord.of(id, event, redactor);
      slots.set((int) (id % slots.length()), record);
      published = id;
    } finally {
      writing.unlock();
    }
    for (Subscriber subscriber : subscribers) {
      subscriber.offer(record);
    }
  }

  // ---------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------

  /**
   * Attaches a client: from now on, every line is offered to it.
   *
   * @param subscriber the client
   */
  void subscribe(Subscriber subscriber) {
    subscribers.add(subscriber);
  }

  /**
   * Detaches a client.
   *
   * @param subscriber the client
   */
  void unsubscribe(Subscriber subscriber) {
    subscribers.remove(subscriber);
  }

  /**
   * Returns how many clients are attached.
   *
   * @return the count
   */
  int subscribers() {
    return subscribers.size();
  }

  /**
   * Returns the number of the last line published.
   *
   * @return the number, 0 before the first line
   */
  long last() {
    return published;
  }

  /**
   * Returns the lines kept after a number, oldest first, at most {@code limit} of those a filter
   * keeps, the most recent ones.
   *
   * @param after the number of the last line the client has, 0 for none
   * @param filter which lines it wants
   * @param limit the most lines returned
   * @return the lines, and how many after {@code after} are no longer kept
   */
  Replay replay(long after, LogFilter filter, int limit) {
    long last = published;
    long oldestKept = Math.max(1, last - slots.length() + 1);
    long from = Math.max(after + 1, oldestKept);
    List<ConsoleRecord> records = new ArrayList<>();
    for (long id = last; id >= from && records.size() < limit; id--) {
      ConsoleRecord record = slots.get((int) (id % slots.length()));
      // Overwritten while reading: the older lines are gone too.
      if (record == null || record.id() != id) {
        break;
      }
      if (filter.test(record)) {
        records.add(record);
      }
    }
    return new Replay(records.reversed(), Math.max(0, oldestKept - after - 1), last);
  }

  /**
   * Lines read again for a client.
   *
   * @param records the lines, oldest first
   * @param lost how many lines after the client's last are no longer kept
   * @param last the number of the last line published when the replay was read
   */
  record Replay(List<ConsoleRecord> records, long lost, long last) {

    /**
     * Copies the lines.
     *
     * @param records the lines
     * @param lost how many are no longer kept
     * @param last the last line published
     */
    Replay {
      records = List.copyOf(records);
    }
  }

  // ---------------------------------------------------------------------------
  // Log4j
  // ---------------------------------------------------------------------------

  /** Adds the appender to every logger that writes to the console. */
  void install() {
    if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
      logger.warn(
          LogCode.REMOTE_CONSOLE_NO_LOG.marker(),
          "The remote console streams nothing: the logging backend is not Log4j Core");
      return;
    }
    Configuration configuration = context.getConfiguration();
    tap.start();
    configuration.addAppender(tap);
    configuration.getRootLogger().addAppender(tap, null, null);
    for (LoggerConfig loggerConfig : configuration.getLoggers().values()) {
      if (!loggerConfig.isAdditive() && !loggerConfig.getName().isEmpty()) {
        loggerConfig.addAppender(tap, null, null);
      }
    }
    context.updateLoggers();
    tap.context = context;
  }

  /** Removes the appender. Idempotent. */
  void uninstall() {
    LoggerContext context = tap.context;
    tap.context = null;
    if (context == null) {
      return;
    }
    Configuration configuration = context.getConfiguration();
    configuration.getRootLogger().removeAppender(tap.getName());
    for (LoggerConfig loggerConfig : configuration.getLoggers().values()) {
      loggerConfig.removeAppender(tap.getName());
    }
    context.updateLoggers();
    tap.stop();
  }

  /** The appender, under a name of its own: two proxies in one JVM (tests) each install theirs. */
  private static final class Tap extends AbstractAppender {

    private final LogHistory history;
    private volatile @Nullable LoggerContext context;

    Tap(LogHistory history) {
      super(
          "WarpRemoteConsole-" + Integer.toHexString(System.identityHashCode(history)),
          null,
          null,
          true,
          Property.EMPTY_ARRAY);
      this.history = history;
    }

    @Override
    public void append(LogEvent event) {
      history.append(event);
    }
  }
}
