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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.logging.LogLevel;
import dev.warp.api.logging.LogRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.layout.template.json.JsonTemplateLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisplayName("SinkAppender")
class SinkAppenderTest {

  @Nested
  @DisplayName("records")
  class Records {

    @Test
    @DisplayName("should copy every field of a Log4j event, the exception and the context included")
    void copiesEvent() {
      IllegalStateException thrown = new IllegalStateException("bad");
      LogEvent event = event(Level.ERROR, thrown, Map.of("player", "Steve", "session", "abc"));

      LogRecord record = SinkAppender.record(event, LogLevel.ERROR);

      assertEquals(Instant.ofEpochMilli(1_700_000_000_123L), record.timestamp());
      assertEquals(LogLevel.ERROR, record.level());
      assertEquals("dev.warp.proxy.Example", record.logger());
      assertEquals("warp-worker-1", record.thread());
      assertEquals("Something failed", record.message());
      assertSame(thrown, record.thrown());
      assertEquals(Map.of("player", "Steve", "session", "abc"), record.context());
    }

    @Test
    @DisplayName("should map Log4j's levels to the API's, FATAL as ERROR")
    void levels() {
      assertEquals(LogLevel.ERROR, SinkAppender.level(Level.FATAL));
      assertEquals(LogLevel.ERROR, SinkAppender.level(Level.ERROR));
      assertEquals(LogLevel.WARN, SinkAppender.level(Level.WARN));
      assertEquals(LogLevel.INFO, SinkAppender.level(Level.INFO));
      assertEquals(LogLevel.DEBUG, SinkAppender.level(Level.DEBUG));
      assertEquals(LogLevel.TRACE, SinkAppender.level(Level.TRACE));
      assertEquals(LogLevel.TRACE, SinkAppender.level(Level.ALL));
    }
  }

  @Nested
  @DisplayName("installed")
  class Installed {

    @Test
    @DisplayName("should hand the proxy's and plugins' records to the sinks, each exactly once")
    void deliversThroughLog4j() throws Exception {
      LogSinkRegistry registry = new LogSinkRegistry();
      SinkAppender appender = new SinkAppender(registry);
      List<LogRecord> received = new CopyOnWriteArrayList<>();
      appender.install();
      try {
        registry.register(
            "probe", LogLevel.INFO, 256, received::add, SinkAppenderTest.class.getClassLoader());
        Logger proxyLogger = LoggerFactory.getLogger("dev.warp.proxy.SinkAppenderProbe");
        Logger pluginLogger = LoggerFactory.getLogger("someplugin");

        proxyLogger.atWarn().addKeyValue("player", "Steve").log("hello {}", "world");
        pluginLogger.info("from a plugin");
        pluginLogger.debug("below the configured level");

        await(() -> count(received, "hello world") == 1 && count(received, "from a plugin") == 1);
        Thread.sleep(200);
        assertEquals(1, count(received, "hello world"));
        assertEquals(1, count(received, "from a plugin"));
        assertEquals(0, count(received, "below the configured level"));
        LogRecord hello =
            received.stream().filter(r -> r.message().equals("hello world")).findFirst().get();
        assertEquals(LogLevel.WARN, hello.level());
        assertEquals("dev.warp.proxy.SinkAppenderProbe", hello.logger());
        assertEquals(Map.of("player", "Steve"), hello.context());
      } finally {
        appender.uninstall();
        registry.close();
      }
    }

    @Test
    @DisplayName("should deliver nothing once uninstalled")
    void uninstall() throws Exception {
      LogSinkRegistry registry = new LogSinkRegistry();
      SinkAppender appender = new SinkAppender(registry);
      List<LogRecord> received = new CopyOnWriteArrayList<>();
      registry.register(
          "probe", LogLevel.INFO, 16, received::add, SinkAppenderTest.class.getClassLoader());
      appender.install();

      appender.uninstall();
      LoggerFactory.getLogger("dev.warp.proxy.SinkAppenderProbe").warn("after uninstall");
      Thread.sleep(200);

      assertEquals(0, count(received, "after uninstall"));
      assertFalse(appender.isStarted());
      registry.close();
    }
  }

  @Nested
  @DisplayName("JSON layout")
  class JsonLayout {

    private final JsonTemplateLayout layout =
        JsonTemplateLayout.newBuilder()
            .setConfiguration(new DefaultConfiguration())
            .setEventTemplateUri("classpath:warp-log-layout.json")
            .build();

    @Test
    @DisplayName("should write one object per line with the exception and the context")
    void fullRecord() {
      LogEvent event =
          event(Level.ERROR, new IllegalStateException("bad"), Map.of("player", "Steve"));

      String line = layout.toSerializable(event);

      assertEquals(1, line.strip().lines().count(), line);
      JsonObject json = JsonParser.parseString(line).getAsJsonObject();
      assertEquals("2023-11-14T22:13:20.123Z", json.get("timestamp").getAsString());
      assertEquals("ERROR", json.get("level").getAsString());
      assertEquals("dev.warp.proxy.Example", json.get("logger").getAsString());
      assertEquals("warp-worker-1", json.get("thread").getAsString());
      assertEquals("Something failed", json.get("message").getAsString());
      assertTrue(
          json.get("exception").getAsString().startsWith("java.lang.IllegalStateException: bad"),
          line);
      assertEquals("Steve", json.getAsJsonObject("context").get("player").getAsString());
    }

    @Test
    @DisplayName("should leave out the exception and the context when there are none")
    void plainRecord() {
      String line = layout.toSerializable(event(Level.INFO, null, Map.of()));

      JsonObject json = JsonParser.parseString(line).getAsJsonObject();
      assertEquals(
          List.of("timestamp", "level", "logger", "thread", "message"), List.copyOf(json.keySet()));
      assertNull(json.get("exception"));
    }
  }

  private static LogEvent event(
      Level level, @Nullable Throwable thrown, Map<String, String> context) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName("dev.warp.proxy.Example")
        .setLevel(level)
        .setMessage(new SimpleMessage("Something failed"))
        .setThreadName("warp-worker-1")
        .setTimeMillis(1_700_000_000_123L)
        .setThrown(thrown)
        .setContextData(new SortedArrayStringMap(context))
        .build();
  }

  private static long count(List<LogRecord> records, String message) {
    return records.stream().filter(record -> record.message().equals(message)).count();
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
