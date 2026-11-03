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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.console.ComponentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The remote console's log: records, filters, replay and redaction")
class LogHistoryTest {

  private static final String SECRET = "s3cr3t-forwarding-value";

  private final Redactor redactor = new Redactor(List.of(SECRET, "short"));

  static LogEvent event(String logger, Level level, Message message) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName(logger)
        .setLevel(level)
        .setMessage(message)
        .setThreadName("main")
        .setTimeMillis(1_700_000_000_000L)
        .build();
  }

  static LogEvent info(String format, Object... parameters) {
    return event(
        "dev.warp.proxy.WarpServer", Level.INFO, new ParameterizedMessage(format, parameters));
  }

  private JsonObject json(ConsoleRecord record) {
    String frame = new String(record.frame("abc"), java.nio.charset.StandardCharsets.UTF_8);
    String data = frame.lines().filter(line -> line.startsWith("data: ")).findFirst().orElseThrow();
    return JsonParser.parseString(data.substring(6)).getAsJsonObject();
  }

  @Nested
  @DisplayName("records")
  class Records {

    @Test
    @DisplayName("should carry the fields of the JSON log format, the tag and the format")
    void fields() {
      ConsoleRecord record =
          ConsoleRecord.of(7, info("{} joined from {}", "Alice", "lobby"), redactor);

      JsonObject json = json(record);

      assertEquals(7, json.get("id").getAsLong());
      assertEquals("INFO", json.get("level").getAsString());
      assertEquals("dev.warp.proxy.WarpServer", json.get("logger").getAsString());
      assertEquals("warp", json.get("tag").getAsString());
      assertEquals("main", json.get("thread").getAsString());
      assertEquals("Alice joined from lobby", json.get("message").getAsString());
      assertEquals("{} joined from {}", json.get("format").getAsString());
      assertEquals("Alice", json.getAsJsonArray("parameters").get(0).getAsString());
      assertEquals("2023-11-14T22:13:20Z", json.get("timestamp").getAsString());
    }

    @Test
    @DisplayName("should frame a record as an SSE event whose id names the proxy's run")
    void frame() {
      String frame =
          new String(
              ConsoleRecord.of(12, info("hello"), redactor).frame("abc"),
              java.nio.charset.StandardCharsets.UTF_8);

      assertTrue(frame.startsWith("id: abc-12\nevent: log\ndata: {"), frame);
      assertTrue(frame.endsWith("}\n\n"), frame);
    }

    @Test
    @DisplayName("should keep a command's reply as its component, and its context")
    void component() {
      LogEvent event =
          Log4jLogEvent.newBuilder()
              .setLoggerName("dev.warp.proxy.console.Console")
              .setLevel(Level.INFO)
              .setMessage(new ComponentMessage(Component.text("3 online", NamedTextColor.AQUA)))
              .setContextData(context(Map.of(ConsoleRecord.SESSION_KEY, "abc")))
              .build();

      JsonObject json = json(ConsoleRecord.of(1, event, redactor));

      assertEquals("3 online", json.get("message").getAsString());
      assertEquals("aqua", json.getAsJsonObject("component").get("color").getAsString());
      assertEquals(
          "abc", json.getAsJsonObject("context").get(ConsoleRecord.SESSION_KEY).getAsString());
    }

    @Test
    @DisplayName("should strip escape sequences from messages")
    void escapes() {
      ConsoleRecord record = ConsoleRecord.of(1, info("\u001B[31mred\u001B[0m"), redactor);

      assertEquals("red", record.message());
    }
  }

  @Nested
  @DisplayName("redaction")
  class Redaction {

    @Test
    @DisplayName("should mask the proxy's secrets wherever they appear")
    void secrets() {
      ConsoleRecord record = ConsoleRecord.of(1, info("secret is {}", SECRET), redactor);

      assertEquals("secret is " + Redactor.MASK, record.message());
      assertFalse(json(record).toString().contains(SECRET), "nor in the parameters");
    }

    @Test
    @DisplayName("should mask bearer tokens and passwords, but not short words")
    void credentials() {
      assertEquals(
          "Authorization: Bearer " + Redactor.MASK,
          redactor.clean("Authorization: Bearer abc.def"));
      assertEquals("url?token=" + Redactor.MASK + "&a=1", redactor.clean("url?token=xyz&a=1"));
      assertEquals("password: " + Redactor.MASK, redactor.clean("password: hunter2"));
      assertEquals("a short story", redactor.clean("a short story"));
    }

    @Test
    @DisplayName("should mask secrets in stack traces")
    void exceptions() {
      LogEvent event =
          Log4jLogEvent.newBuilder()
              .setLoggerName("x")
              .setLevel(Level.ERROR)
              .setMessage(new ParameterizedMessage("failed"))
              .setThrown(new IllegalStateException("bad value " + SECRET))
              .build();

      JsonObject json = json(ConsoleRecord.of(1, event, redactor));

      assertTrue(json.get("exception").getAsString().contains("bad value " + Redactor.MASK));
    }
  }

  @Nested
  @DisplayName("filters")
  class Filters {

    private ConsoleRecord record(String logger, Level level, String message) {
      return ConsoleRecord.of(1, event(logger, level, new ParameterizedMessage(message)), redactor);
    }

    @Test
    @DisplayName("should keep the lines of the level asked for and more severe")
    void level() {
      LogFilter filter = LogFilter.parse(Map.of("level", List.of("warn")));

      assertFalse(filter.test(record("dev.warp.proxy.WarpServer", Level.INFO, "x")));
      assertTrue(filter.test(record("dev.warp.proxy.WarpServer", Level.WARN, "x")));
      assertTrue(filter.test(record("dev.warp.proxy.WarpServer", Level.ERROR, "x")));
    }

    @Test
    @DisplayName("should keep tags, prefixes of tags, and leave others out")
    void tags() {
      LogFilter keep = LogFilter.parse(Map.of("tags", List.of("plugin:*,warp")));
      LogFilter leave = LogFilter.parse(Map.of("tags", List.of("-netty")));

      assertTrue(keep.test(record("primemc", Level.INFO, "x")));
      assertTrue(keep.test(record("dev.warp.proxy.WarpServer", Level.INFO, "x")));
      assertFalse(keep.test(record("dev.warp.proxy.antibot.AntiBotService", Level.INFO, "x")));
      assertFalse(leave.test(record("io.netty.channel.X", Level.WARN, "x")));
      assertTrue(leave.test(record("dev.warp.proxy.WarpServer", Level.INFO, "x")));
    }

    @Test
    @DisplayName("should keep the lines that contain a text, whatever its case")
    void grep() {
      LogFilter filter = LogFilter.parse(Map.of("grep", List.of("ALICE")));

      assertTrue(filter.test(record("x", Level.INFO, "alice joined")));
      assertFalse(filter.test(record("x", Level.INFO, "bob joined")));
    }

    @Test
    @DisplayName("should refuse an unknown level")
    void unknownLevel() {
      org.junit.jupiter.api.Assertions.assertThrows(
          IllegalArgumentException.class, () -> LogFilter.parse(Map.of("level", List.of("loud"))));
    }
  }

  @Nested
  @DisplayName("history")
  class History {

    private final LogHistory history = new LogHistory(5, redactor);

    private void log(int count) {
      for (int i = 1; i <= count; i++) {
        history.append(info("line {}", i));
      }
    }

    private static List<String> messages(LogHistory.Replay replay) {
      List<String> messages = new ArrayList<>();
      replay.records().forEach(record -> messages.add(record.message()));
      return messages;
    }

    @Test
    @DisplayName("should replay the last lines, oldest first")
    void lastLines() {
      log(3);

      LogHistory.Replay replay = history.replay(0, LogFilter.DEFAULT, 2);

      assertEquals(List.of("line 2", "line 3"), messages(replay));
      assertEquals(3, replay.last());
    }

    @Test
    @DisplayName("should keep only its capacity, and tell a resuming client what it lost")
    void capacity() {
      log(8);

      LogHistory.Replay all = history.replay(0, LogFilter.DEFAULT, 100);
      LogHistory.Replay resumed = history.replay(1, LogFilter.DEFAULT, 100);

      assertEquals(List.of("line 4", "line 5", "line 6", "line 7", "line 8"), messages(all));
      assertEquals(2, resumed.lost());
    }

    @Test
    @DisplayName("should resume after the last line a client has, without a gap or a repeat")
    void resume() {
      log(4);

      LogHistory.Replay replay = history.replay(2, LogFilter.DEFAULT, 100);

      assertEquals(List.of("line 3", "line 4"), messages(replay));
      assertEquals(0, replay.lost());
    }

    @Test
    @DisplayName("should filter the replay as the live lines")
    void filtered() {
      history.append(info("alice"));
      history.append(info("bob"));

      LogHistory.Replay replay =
          history.replay(0, LogFilter.parse(Map.of("grep", List.of("bob"))), 100);

      assertEquals(List.of("bob"), messages(replay));
    }

    @Test
    @DisplayName("should hand every line to the subscribers")
    void subscribers() {
      List<ConsoleRecord> received = new ArrayList<>();
      LogHistory.Subscriber subscriber = received::add;
      history.subscribe(subscriber);

      log(2);
      history.unsubscribe(subscriber);
      log(1);

      assertEquals(2, received.size());
      assertEquals(2, received.getLast().id());
      assertNull(received.getFirst().context(ConsoleRecord.SESSION_KEY));
    }
  }

  private static SortedArrayStringMap context(Map<String, String> values) {
    SortedArrayStringMap map = new SortedArrayStringMap();
    values.forEach(map::putValue);
    return map;
  }
}
