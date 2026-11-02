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

/**
 * Where the drain runs its steps, one at a time, and the clock it reads. The proxy runs them on a
 * thread of their own ({@link ExecutorDrainScheduler}), never on an event loop; tests drive a
 * virtual clock.
 */
public interface DrainScheduler {

  /**
   * Runs a task soon, after the tasks submitted before it.
   *
   * @param task the task
   * @throws java.util.concurrent.RejectedExecutionException once the scheduler is shut down
   */
  void execute(Runnable task);

  /**
   * Runs a task after a delay.
   *
   * @param task the task
   * @param delay the delay, zero or more
   * @return cancels the task if it has not run yet
   * @throws java.util.concurrent.RejectedExecutionException once the scheduler is shut down
   */
  Cancellable schedule(Runnable task, Duration delay);

  /**
   * Returns a monotonic time, for durations.
   *
   * @return the time in nanoseconds, from an arbitrary origin
   */
  long nanoTime();

  /**
   * Returns the current time, for the drain's start.
   *
   * @return the current time
   */
  Instant now();

  /** A task that can still be cancelled. */
  @FunctionalInterface
  interface Cancellable {

    /** Cancels the task if it has not run yet. Does nothing otherwise. */
    void cancel();
  }
}
