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
package dev.warp.proxy.drain;

import java.time.Duration;
import java.time.Instant;
import java.util.PriorityQueue;
import java.util.concurrent.RejectedExecutionException;

/**
 * A {@link DrainScheduler} on a virtual clock: nothing runs until the test says so, and time only
 * moves when the test advances it. Tasks run on the test's thread, in the order they are due, then
 * in the order they were submitted.
 */
public final class ManualScheduler implements DrainScheduler {

  /** The instant the virtual clock starts at. */
  public static final Instant ORIGIN = Instant.parse("2026-11-02T12:00:00Z");

  /** An arbitrary origin for the monotonic time, negative as {@link System#nanoTime} may be. */
  private static final long START_NANOS = -5_000_000_000L;

  private final PriorityQueue<Task> tasks = new PriorityQueue<>();
  private long nanos = START_NANOS;
  private long sequence;
  private boolean shutdown;

  private static final class Task implements Comparable<Task> {
    private final long due;
    private final long order;
    private final Runnable action;
    private boolean cancelled;

    Task(long due, long order, Runnable action) {
      this.due = due;
      this.order = order;
      this.action = action;
    }

    @Override
    public int compareTo(Task other) {
      int byDue = Long.compare(due, other.due);
      return byDue != 0 ? byDue : Long.compare(order, other.order);
    }
  }

  @Override
  public void execute(Runnable task) {
    add(task, 0);
  }

  @Override
  public Cancellable schedule(Runnable task, Duration delay) {
    Task added = add(task, delay.toNanos());
    return () -> added.cancelled = true;
  }

  @Override
  public long nanoTime() {
    return nanos;
  }

  @Override
  public Instant now() {
    return ORIGIN.plusNanos(nanos - START_NANOS);
  }

  /** Makes later submissions fail, as an executor that was shut down does. */
  public void shutdown() {
    shutdown = true;
  }

  /** Runs every task due now, the ones they submit for now included. */
  public void runDue() {
    for (Task next = tasks.peek(); next != null && next.due <= nanos; next = tasks.peek()) {
      tasks.poll();
      if (!next.cancelled) {
        next.action.run();
      }
    }
  }

  /**
   * Moves the clock forward, running each task due meanwhile at its own time.
   *
   * @param duration how far
   */
  public void advance(Duration duration) {
    long target = nanos + duration.toNanos();
    runDue();
    for (Task next = tasks.peek(); next != null && next.due <= target; next = tasks.peek()) {
      tasks.poll();
      nanos = Math.max(nanos, next.due);
      if (!next.cancelled) {
        next.action.run();
      }
    }
    nanos = target;
    runDue();
  }

  /**
   * Returns how long the clock has run since it started.
   *
   * @return the virtual time elapsed
   */
  public Duration elapsed() {
    return Duration.ofNanos(nanos - START_NANOS);
  }

  /**
   * Returns how many tasks wait, cancelled ones excluded.
   *
   * @return the count
   */
  public long pending() {
    return tasks.stream().filter(task -> !task.cancelled).count();
  }

  private Task add(Runnable action, long delayNanos) {
    if (shutdown) {
      throw new RejectedExecutionException("shut down");
    }
    Task task = new Task(nanos + Math.max(0, delayNanos), sequence++, action);
    tasks.add(task);
    return task;
  }
}
