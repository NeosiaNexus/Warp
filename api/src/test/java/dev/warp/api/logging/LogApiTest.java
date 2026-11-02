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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Log sink API")
class LogApiTest {

  @Nested
  @DisplayName("LogLevel")
  class Levels {

    @Test
    @DisplayName("should order the levels from TRACE to ERROR")
    void ordering() {
      assertTrue(LogLevel.ERROR.isAtLeast(LogLevel.WARN));
      assertTrue(LogLevel.WARN.isAtLeast(LogLevel.WARN));
      assertFalse(LogLevel.INFO.isAtLeast(LogLevel.WARN));
      assertTrue(LogLevel.DEBUG.isAtLeast(LogLevel.TRACE));
      assertFalse(LogLevel.TRACE.isAtLeast(LogLevel.DEBUG));
    }
  }

  @Nested
  @DisplayName("LogRecord")
  class Records {

    @Test
    @DisplayName("should keep its own unmodifiable copy of the context")
    void copiesContext() {
      Map<String, String> context = new HashMap<>(Map.of("player", "Steve"));

      LogRecord record = record(context, null);
      context.put("session", "abc");

      assertEquals(Map.of("player", "Steve"), record.context());
      assertThrows(UnsupportedOperationException.class, () -> record.context().put("a", "b"));
    }

    @Test
    @DisplayName("should carry the exception itself, or none")
    void exception() {
      IllegalStateException thrown = new IllegalStateException("bad");

      assertSame(thrown, record(Map.of(), thrown).thrown());
      assertNull(record(Map.of(), null).thrown());
    }

    @Test
    @DisplayName("should refuse a missing field")
    @SuppressWarnings("NullAway") // the nulls under test
    void refusesNulls() {
      Instant now = Instant.now();
      assertThrows(
          NullPointerException.class,
          () -> new LogRecord(null, LogLevel.INFO, "l", "t", "m", null, Map.of()));
      assertThrows(
          NullPointerException.class,
          () -> new LogRecord(now, null, "l", "t", "m", null, Map.of()));
      assertThrows(
          NullPointerException.class,
          () -> new LogRecord(now, LogLevel.INFO, "l", "t", null, null, Map.of()));
    }
  }

  @Nested
  @DisplayName("LogSinks")
  class Sinks {

    @Test
    @DisplayName("should register with the default capacity when none is given")
    @SuppressWarnings("NullAway") // the stub registrar returns no subscription
    void defaultCapacity() {
      AtomicInteger capacity = new AtomicInteger();
      AtomicReference<LogLevel> threshold = new AtomicReference<>();
      LogSinks sinks =
          (level, size, sink) -> {
            threshold.set(level);
            capacity.set(size);
            return null;
          };

      sinks.register(LogLevel.ERROR, record -> {});

      assertEquals(LogLevel.ERROR, threshold.get());
      assertEquals(LogSinks.DEFAULT_CAPACITY, capacity.get());
      assertTrue(LogSinks.DEFAULT_CAPACITY <= LogSinks.MAX_CAPACITY);
    }
  }

  private static LogRecord record(Map<String, String> context, @Nullable Throwable thrown) {
    return new LogRecord(
        Instant.EPOCH, LogLevel.ERROR, "dev.warp.Test", "main", "message", thrown, context);
  }
}
