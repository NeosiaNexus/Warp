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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The drain's last step stops the proxy, then ends the process with the drain's exit code: the
 * thread running it must be one the JVM waits for, or the JVM shuts down under the step once the
 * event loops are gone, stops the logging and loses the exit log.
 */
@DisplayName("ExecutorDrainScheduler")
class ExecutorDrainSchedulerTest {

  private final ExecutorDrainScheduler scheduler = new ExecutorDrainScheduler();

  @AfterEach
  void shutdown() {
    scheduler.shutdown();
  }

  @Nested
  @DisplayName("thread")
  class ThreadTests {

    @Test
    @DisplayName("should run the steps on a warp-drain thread the JVM waits for, not a daemon")
    void shouldRunStepsOnANonDaemonThread() throws Exception {
      CompletableFuture<Thread> ran = new CompletableFuture<>();

      scheduler.execute(() -> ran.complete(Thread.currentThread()));
      Thread thread = ran.get(5, TimeUnit.SECONDS);

      assertEquals("warp-drain", thread.getName());
      assertFalse(thread.isDaemon(), "a daemon thread would not keep the JVM alive for the exit");
      assertFalse(thread.isVirtual(), "a virtual thread is a daemon");
    }

    @Test
    @DisplayName("should keep the thread until shut down, then let it finish its step and end")
    void shouldEndTheThreadOnceShutDownAfterItsStep() throws Exception {
      CompletableFuture<Thread> ran = new CompletableFuture<>();
      CountDownLatch release = new CountDownLatch(1);
      CompletableFuture<Boolean> stepFinished = new CompletableFuture<>();

      scheduler.execute(
          () -> {
            ran.complete(Thread.currentThread());
            try {
              stepFinished.complete(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              stepFinished.complete(false);
            }
          });
      Thread thread = ran.get(5, TimeUnit.SECONDS);

      // Shut down from inside the step, as the drain's last step does: the step runs on.
      scheduler.shutdown();
      assertTrue(thread.isAlive(), "the running step is not interrupted by the shutdown");
      release.countDown();

      assertTrue(stepFinished.get(5, TimeUnit.SECONDS), "the step finished without interruption");
      thread.join(Duration.ofSeconds(5).toMillis());
      assertFalse(thread.isAlive(), "the thread ends after its step, so a stopped proxy can exit");
    }

    @Test
    @DisplayName("should run nothing scheduled after the shutdown")
    void shouldRunNothingScheduledAfterShutdown() throws Exception {
      CompletableFuture<Boolean> ran = new CompletableFuture<>();
      scheduler.schedule(() -> ran.complete(true), Duration.ofMillis(50));

      scheduler.shutdown();
      Thread.sleep(200);

      assertFalse(ran.isDone(), "a delayed step does not run once the proxy is stopping");
    }
  }
}
