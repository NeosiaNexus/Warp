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
import dev.warp.api.logging.LogSink;
import dev.warp.api.logging.LogSinkSubscription;
import dev.warp.api.logging.LogSinks;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The log sinks plugins registered, and the delivery of records to them.
 *
 * <p>{@link #publish} runs on the thread that writes the logs (Log4j's asynchronous logging thread)
 * and never blocks it: each sink has a bounded queue, and a record that finds it full is dropped
 * and counted. Each sink drains its queue on a virtual thread of its own, with its plugin's class
 * loader as context class loader, so that a slow or stuck sink delays nobody but itself.
 *
 * <p>Records logged by the sinks' own threads reach no sink: a sink that logs while handling a
 * record would otherwise feed itself, or two sinks each other.
 */
public final class LogSinkRegistry implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(LogSinkRegistry.class);

  /** How often, at most, a sink's dropped and failed records are reported. */
  static final Duration REPORT_INTERVAL = Duration.ofMinutes(1);

  private final List<Worker> workers = new CopyOnWriteArrayList<>();

  /** The identifiers of the threads that run the sinks, whose own records reach no sink. */
  private final Set<Long> sinkThreads = ConcurrentHashMap.newKeySet();

  /** The least severe level some sink receives, or {@code null} without sinks. */
  private volatile @Nullable LogLevel threshold;

  private final Duration reportInterval;

  /** Creates an empty registry. */
  public LogSinkRegistry() {
    this(REPORT_INTERVAL);
  }

  /**
   * Creates an empty registry that reports losses at most once per {@code reportInterval} (tests).
   *
   * @param reportInterval the shortest time between two reports of a sink's losses
   */
  LogSinkRegistry(Duration reportInterval) {
    this.reportInterval = reportInterval;
  }

  // ---------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------

  /**
   * Registers a sink and starts its thread.
   *
   * @param owner the plugin's identifier, for the thread's name and the reports
   * @param level the least severe level the sink receives
   * @param capacity how many records its queue holds
   * @param sink the sink
   * @param contextLoader the context class loader of the sink's thread: its plugin's
   * @return the registration; closing it removes the sink
   * @throws IllegalArgumentException if {@code capacity} is not 1 to {@link LogSinks#MAX_CAPACITY}
   */
  public LogSinkSubscription register(
      String owner, LogLevel level, int capacity, LogSink sink, ClassLoader contextLoader) {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(sink, "sink");
    if (capacity < 1 || capacity > LogSinks.MAX_CAPACITY) {
      throw new IllegalArgumentException(
          "capacity must be 1 to " + LogSinks.MAX_CAPACITY + ", got " + capacity);
    }
    Worker worker = new Worker(owner, level, capacity, sink);
    Thread thread = Thread.ofVirtual().name("warp-log-sink-" + owner).unstarted(worker::run);
    thread.setContextClassLoader(contextLoader);
    worker.thread = thread;
    sinkThreads.add(thread.threadId());
    workers.add(worker);
    updateThreshold();
    thread.start();
    return worker;
  }

  /**
   * Returns whether some sink receives records of {@code level}: the caller skips building a record
   * nobody receives.
   *
   * @param level the record's level
   * @return {@code true} if {@link #publish} would queue such a record for a sink
   */
  public boolean wants(LogLevel level) {
    LogLevel current = threshold;
    return current != null && level.isAtLeast(current);
  }

  /**
   * Queues a record for every sink whose threshold it passes. Never blocks: a full queue drops the
   * record, counted against that sink.
   *
   * @param record the record
   * @param threadId the identifier of the thread that logged it
   */
  public void publish(LogRecord record, long threadId) {
    if (sinkThreads.contains(threadId)) {
      return;
    }
    for (Worker worker : workers) {
      if (record.level().isAtLeast(worker.level)) {
        worker.offer(record);
      }
    }
  }

  /** Removes every sink. */
  @Override
  public void close() {
    for (Worker worker : workers) {
      worker.unsubscribe();
    }
  }

  private void remove(Worker worker) {
    workers.remove(worker);
    updateThreshold();
  }

  private synchronized void updateThreshold() {
    LogLevel lowest = null;
    for (Worker worker : workers) {
      if (lowest == null || !worker.level.isAtLeast(lowest)) {
        lowest = worker.level;
      }
    }
    threshold = lowest;
  }

  // ---------------------------------------------------------------------------
  // One sink
  // ---------------------------------------------------------------------------

  /** A sink, its queue and its thread. */
  private final class Worker implements LogSinkSubscription {

    private final String owner;
    private final LogLevel level;
    private final ArrayBlockingQueue<LogRecord> queue;
    private final LogSink sink;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean active = true;
    private volatile @Nullable Thread thread;

    /** Touched by the sink's thread only. */
    private long reportedDropped;

    private long reportedFailed;
    private long lastReport;
    private @Nullable Exception lastFailure;

    Worker(String owner, LogLevel level, int capacity, LogSink sink) {
      this.owner = owner;
      this.level = level;
      this.queue = new ArrayBlockingQueue<>(capacity);
      this.sink = sink;
    }

    void offer(LogRecord record) {
      if (!active || !queue.offer(record)) {
        dropped.incrementAndGet();
      }
    }

    /** The sink's thread: takes records one at a time until the sink is removed. */
    void run() {
      lastReport = System.nanoTime();
      try {
        while (active) {
          LogRecord record = queue.poll(reportInterval.toMillis(), TimeUnit.MILLISECONDS);
          if (record != null && active) {
            deliver(record);
          }
          reportLosses(false);
        }
      } catch (InterruptedException e) {
        // Removed while waiting: nothing left to do.
      } finally {
        dropped.addAndGet(queue.size());
        queue.clear();
        reportLosses(true);
        Thread current = thread;
        if (current != null) {
          sinkThreads.remove(current.threadId());
        }
      }
    }

    private void deliver(LogRecord record) {
      try {
        sink.accept(record);
        delivered.incrementAndGet();
      } catch (InterruptedException e) {
        failed.incrementAndGet();
        Thread.currentThread().interrupt();
        active = false;
      } catch (Exception e) {
        failed.incrementAndGet();
        if (failed.get() == 1) {
          // The first failure in full, at once: it is usually a configuration error.
          logger.warn(
              LogCode.LOG_SINK_FAILED.marker(),
              "The log sink of plugin {} failed on a record",
              owner,
              e);
          reportedFailed = 1;
        } else {
          lastFailure = e;
        }
      } catch (LinkageError e) {
        // The plugin's classes are gone, or never loaded: the sink cannot work again.
        failed.incrementAndGet();
        logger.warn(
            LogCode.LOG_SINK_REMOVED.marker(),
            "The log sink of plugin {} cannot run, removed",
            owner,
            e);
        unsubscribe();
      }
    }

    /** Logs the records lost since the last report, at most once per interval unless final. */
    private void reportLosses(boolean last) {
      long now = System.nanoTime();
      if (!last && now - lastReport < reportInterval.toNanos()) {
        return;
      }
      lastReport = now;
      long droppedNow = dropped.get();
      long failedNow = failed.get();
      long newlyDropped = droppedNow - reportedDropped;
      long newlyFailed = failedNow - reportedFailed;
      reportedDropped = droppedNow;
      reportedFailed = failedNow;
      if (newlyDropped > 0) {
        logger.warn(
            LogCode.LOG_SINK_LOST_RECORDS.marker(),
            "The log sink of plugin {} lost {} records{}",
            owner,
            newlyDropped,
            last ? " (queued when it was removed, or its queue was full)" : ": its queue was full");
      }
      if (newlyFailed > 0) {
        Exception failure = lastFailure;
        lastFailure = null;
        logger.warn(
            LogCode.LOG_SINK_FAILED.marker(),
            "The log sink of plugin {} failed on {} more records, last: {}",
            owner,
            newlyFailed,
            failure == null ? "unknown" : failure.toString());
      }
    }

    @Override
    public long delivered() {
      return delivered.get();
    }

    @Override
    public long failed() {
      return failed.get();
    }

    @Override
    public long dropped() {
      return dropped.get();
    }

    @Override
    public boolean isActive() {
      return active;
    }

    @Override
    public void unsubscribe() {
      if (!active) {
        return;
      }
      active = false;
      remove(this);
      Thread current = thread;
      if (current != null && !current.equals(Thread.currentThread())) {
        current.interrupt();
      }
    }
  }
}
