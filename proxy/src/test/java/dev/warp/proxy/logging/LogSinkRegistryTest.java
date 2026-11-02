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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.logging.LogLevel;
import dev.warp.api.logging.LogRecord;
import dev.warp.api.logging.LogSinkSubscription;
import dev.warp.api.logging.LogSinks;

import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LogSinkRegistry")
class LogSinkRegistryTest {

  private static final ClassLoader LOADER = LogSinkRegistryTest.class.getClassLoader();
  private static final long CALLER = Thread.currentThread().threadId();

  private final LogSinkRegistry registry = new LogSinkRegistry(Duration.ofMillis(50));

  @AfterEach
  void tearDown() {
    registry.close();
  }

  @Nested
  @DisplayName("delivery")
  class Delivery {

    @Test
    @DisplayName("should deliver the records at or above the threshold, in order")
    void threshold() throws Exception {
      List<String> received = new CopyOnWriteArrayList<>();
      LogSinkSubscription subscription =
          registry.register(
              "sentry", LogLevel.WARN, 16, record -> received.add(record.message()), LOADER);

      registry.publish(record(LogLevel.INFO, "info"), CALLER);
      registry.publish(record(LogLevel.WARN, "warn"), CALLER);
      registry.publish(record(LogLevel.ERROR, "error"), CALLER);

      await(() -> received.size() == 2);
      assertEquals(List.of("warn", "error"), received);
      assertEquals(2, subscription.delivered());
      assertEquals(0, subscription.dropped());
    }

    @Test
    @DisplayName("should tell whether some sink wants a level, so that no record is built for none")
    void wants() {
      assertFalse(registry.wants(LogLevel.ERROR));

      LogSinkSubscription errors = registry.register("a", LogLevel.ERROR, 1, record -> {}, LOADER);
      LogSinkSubscription infos = registry.register("b", LogLevel.INFO, 1, record -> {}, LOADER);

      assertTrue(registry.wants(LogLevel.INFO));
      assertFalse(registry.wants(LogLevel.DEBUG));
      infos.unsubscribe();
      assertFalse(registry.wants(LogLevel.WARN));
      assertTrue(registry.wants(LogLevel.ERROR));
      errors.unsubscribe();
      assertFalse(registry.wants(LogLevel.ERROR));
    }

    @Test
    @DisplayName("should run each sink with its plugin's class loader as context class loader")
    void contextClassLoader() throws Exception {
      try (URLClassLoader plugin = new URLClassLoader("plugin", new URL[0], LOADER)) {
        AtomicReference<ClassLoader> seen = new AtomicReference<>();
        registry.register(
            "plugin",
            LogLevel.INFO,
            4,
            record -> seen.set(Thread.currentThread().getContextClassLoader()),
            plugin);

        registry.publish(record(LogLevel.INFO, "hello"), CALLER);

        await(() -> seen.get() != null);
        assertSame(plugin, seen.get());
      }
    }

    @Test
    @DisplayName("should keep delivering after a sink throws, counting the failure")
    void failure() throws Exception {
      List<String> received = new CopyOnWriteArrayList<>();
      LogSinkSubscription subscription =
          registry.register(
              "flaky",
              LogLevel.INFO,
              8,
              record -> {
                if (record.message().equals("bad")) {
                  throw new IllegalStateException("cannot send");
                }
                received.add(record.message());
              },
              LOADER);

      registry.publish(record(LogLevel.INFO, "bad"), CALLER);
      registry.publish(record(LogLevel.INFO, "good"), CALLER);

      await(() -> received.size() == 1);
      assertEquals(List.of("good"), received);
      assertEquals(1, subscription.failed());
      assertEquals(1, subscription.delivered());
    }

    @Test
    @DisplayName("should deliver nothing logged by a sink's own thread, which would feed itself")
    void noFeedback() throws Exception {
      AtomicLong sinkThread = new AtomicLong();
      List<String> received = new CopyOnWriteArrayList<>();
      registry.register(
          "loop",
          LogLevel.INFO,
          8,
          record -> {
            sinkThread.set(Thread.currentThread().threadId());
            received.add(record.message());
          },
          LOADER);
      registry.publish(record(LogLevel.INFO, "first"), CALLER);
      await(() -> sinkThread.get() != 0);

      registry.publish(record(LogLevel.INFO, "from the sink"), sinkThread.get());
      registry.publish(record(LogLevel.INFO, "second"), CALLER);

      await(() -> received.size() == 2);
      assertEquals(List.of("first", "second"), received);
    }
  }

  @Nested
  @DisplayName("back-pressure")
  class BackPressure {

    @Test
    @DisplayName("should never block the logging thread: a full queue drops records, counted")
    void dropsWhenFull() throws Exception {
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch handling = new CountDownLatch(1);
      LogSinkSubscription subscription =
          registry.register(
              "stuck",
              LogLevel.INFO,
              2,
              record -> {
                handling.countDown();
                release.await();
              },
              LOADER);
      registry.publish(record(LogLevel.INFO, "held by the sink"), CALLER);
      assertTrue(handling.await(5, TimeUnit.SECONDS));

      long start = System.nanoTime();
      for (int i = 0; i < 10_000; i++) {
        registry.publish(record(LogLevel.INFO, "record " + i), CALLER);
      }
      long elapsed = System.nanoTime() - start;

      assertTrue(elapsed < TimeUnit.SECONDS.toNanos(5), "publishing took " + elapsed + " ns");
      assertEquals(10_000 - 2, subscription.dropped());
      release.countDown();
      await(() -> subscription.delivered() == 3);
    }

    @Test
    @DisplayName("should refuse a capacity out of range")
    void capacity() {
      assertThrows(
          IllegalArgumentException.class,
          () -> registry.register("a", LogLevel.INFO, 0, record -> {}, LOADER));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              registry.register(
                  "a", LogLevel.INFO, LogSinks.MAX_CAPACITY + 1, record -> {}, LOADER));
    }
  }

  @Nested
  @DisplayName("removal")
  class Removal {

    @Test
    @DisplayName("should stop a removed sink, counting the records it still had queued as dropped")
    void unsubscribe() throws Exception {
      CountDownLatch handling = new CountDownLatch(1);
      CountDownLatch never = new CountDownLatch(1);
      LogSinkSubscription subscription =
          registry.register(
              "slow",
              LogLevel.INFO,
              8,
              record -> {
                handling.countDown();
                never.await();
              },
              LOADER);
      registry.publish(record(LogLevel.INFO, "in progress"), CALLER);
      assertTrue(handling.await(5, TimeUnit.SECONDS));
      registry.publish(record(LogLevel.INFO, "queued 1"), CALLER);
      registry.publish(record(LogLevel.INFO, "queued 2"), CALLER);

      subscription.unsubscribe();
      registry.publish(record(LogLevel.INFO, "after"), CALLER);

      assertFalse(subscription.isActive());
      assertFalse(registry.wants(LogLevel.ERROR));
      await(() -> subscription.dropped() == 2);
      assertEquals(0, subscription.delivered());
    }

    @Test
    @DisplayName("should remove every sink when closed")
    void close() {
      LogSinkSubscription first = registry.register("a", LogLevel.INFO, 1, record -> {}, LOADER);
      LogSinkSubscription second = registry.register("b", LogLevel.INFO, 1, record -> {}, LOADER);

      registry.close();

      assertFalse(first.isActive());
      assertFalse(second.isActive());
      assertFalse(registry.wants(LogLevel.ERROR));
    }
  }

  private static LogRecord record(LogLevel level, String message) {
    return new LogRecord(Instant.now(), level, "test", "main", message, null, Map.of());
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within 10 s");
      }
      Thread.sleep(5);
    }
  }
}
