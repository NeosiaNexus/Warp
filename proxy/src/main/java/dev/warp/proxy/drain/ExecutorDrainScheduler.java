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

import dev.warp.proxy.logging.LogCode;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the drain's steps on a thread of its own, {@code warp-drain}: a platform thread, started on
 * first use, that never runs plugin code or touches an event loop directly.
 *
 * <p>The thread is not a daemon: the drain's last step stops the proxy and then ends the process
 * with the drain's exit code. Stopping the proxy ends the event loops, the last threads the JVM
 * waits for; a daemon thread alone would not keep the JVM alive, which would start its shutdown
 * hooks under that step, stop the logging and lose the exit log, and sometimes the exit code. The
 * thread ends once {@link #shutdown()} let it finish its step, so a stopped proxy still exits.
 */
public final class ExecutorDrainScheduler implements DrainScheduler {

  private static final Logger logger = LoggerFactory.getLogger(ExecutorDrainScheduler.class);

  private final ScheduledThreadPoolExecutor executor;

  /** Creates the scheduler; its thread starts with the first task. */
  public ExecutorDrainScheduler() {
    this.executor =
        new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("warp-drain").factory());
    executor.setRemoveOnCancelPolicy(true);
    // Once shut down, nothing scheduled runs any more: the proxy is stopping.
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  @Override
  public void execute(Runnable task) {
    executor.execute(guarded(task));
  }

  @Override
  public Cancellable schedule(Runnable task, Duration delay) {
    ScheduledFuture<?> future =
        executor.schedule(guarded(task), delay.toNanos(), TimeUnit.NANOSECONDS);
    return () -> future.cancel(false);
  }

  @Override
  public long nanoTime() {
    return System.nanoTime();
  }

  @Override
  public Instant now() {
    return Instant.now();
  }

  /** Stops running tasks, without waiting: safe to call from a drain step. */
  public void shutdown() {
    executor.shutdown();
  }

  /** Logs what a step throws: the executor would keep it in a future nobody reads. */
  private static Runnable guarded(Runnable task) {
    return () -> {
      try {
        task.run();
      } catch (RuntimeException | Error e) {
        logger.error(LogCode.DRAIN_STEP_FAILED.marker(), "A drain step failed", e);
      }
    };
  }
}
