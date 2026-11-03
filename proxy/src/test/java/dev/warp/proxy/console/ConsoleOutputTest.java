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
package dev.warp.proxy.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Console output")
class ConsoleOutputTest {

  private static ConsoleLine line(Level level, String text) {
    return new ConsoleLine(level, text, text);
  }

  @Nested
  @DisplayName("ConsoleBuffer, once full")
  class DropPolicy {

    @Test
    @DisplayName("drops DEBUG and TRACE from half its capacity, and counts them")
    void debug() {
      ConsoleBuffer buffer = new ConsoleBuffer(4, 4);
      assertTrue(buffer.offer(line(Level.DEBUG, "1")));
      assertTrue(buffer.offer(line(Level.TRACE, "2")));

      assertFalse(buffer.offer(line(Level.DEBUG, "3")));
      assertFalse(buffer.offer(line(Level.TRACE, "4")));
      assertEquals(2, buffer.takeDropped());
      assertEquals(0, buffer.takeDropped());
    }

    @Test
    @DisplayName("drops INFO from its capacity")
    void info() {
      ConsoleBuffer buffer = new ConsoleBuffer(4, 4);
      for (int i = 0; i < 4; i++) {
        assertTrue(buffer.offer(line(Level.INFO, "info " + i)));
      }

      assertFalse(buffer.offer(line(Level.INFO, "one too many")));
      assertEquals(1, buffer.takeDropped());
    }

    @Test
    @DisplayName("never drops WARN or ERROR: they use the reserve")
    void warnings() {
      ConsoleBuffer buffer = new ConsoleBuffer(4, 4);
      for (int i = 0; i < 4; i++) {
        assertTrue(buffer.offer(line(Level.INFO, "info " + i)));
      }

      assertTrue(buffer.offer(line(Level.WARN, "warn")));
      assertTrue(buffer.offer(line(Level.ERROR, "error")));
      assertTrue(buffer.offer(line(Level.FATAL, "fatal")));
      assertEquals(7, buffer.size());
      assertEquals(0, buffer.takeDropped());
    }

    @Test
    @DisplayName("gives its lines back in order")
    void order() throws InterruptedException {
      ConsoleBuffer buffer = new ConsoleBuffer(8, 0);
      var _ = buffer.offer(line(Level.INFO, "a"));
      var _ = buffer.offer(line(Level.WARN, "b"));

      assertEquals("a", java.util.Objects.requireNonNull(buffer.poll(0)).text());
      List<ConsoleLine> rest = new ArrayList<>();
      assertEquals(1, buffer.drainTo(rest, 10));
      assertEquals("b", rest.getFirst().text());
      assertNull(buffer.poll(0));
    }
  }

  @Nested
  @DisplayName("ConsoleOutput")
  class Writer {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private ConsoleOutput output() {
      ConsoleOutput output =
          new ConsoleOutput(
              ConsoleMode.PLAIN,
              null,
              new PrintStream(bytes, true, StandardCharsets.UTF_8),
              new ConsoleBuffer(ConsoleOutput.CAPACITY, 16));
      output.start();
      return output;
    }

    private LogEvent event(String message) {
      return Log4jLogEvent.newBuilder()
          .setLoggerName("dev.warp.proxy.WarpServer")
          .setLevel(Level.INFO)
          .setMessage(new SimpleMessage(message))
          .setTimeMillis(0)
          .build();
    }

    private List<String> written() {
      return bytes.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    @DisplayName("writes every line queued before it closes")
    void drains() {
      ConsoleOutput output = output();
      for (int i = 0; i < 100; i++) {
        output.submit(event("line " + i));
      }

      output.close();

      List<String> lines = written();
      assertEquals(100, lines.size());
      assertTrue(lines.getLast().endsWith("line 99"));
    }

    @Test
    @DisplayName("counts a line repeated word for word instead of printing it again")
    void repeats() {
      ConsoleOutput output = output();
      for (int i = 0; i < 5; i++) {
        output.submit(event("Connection refused"));
      }
      output.submit(event("something else"));

      output.close();

      List<String> lines = written();
      assertEquals(3, lines.size(), String.join("\n", lines));
      assertTrue(lines.get(0).endsWith("Connection refused"));
      assertTrue(lines.get(1).endsWith("... repeated 4 times"));
      assertTrue(lines.get(2).endsWith("something else"));
    }

    @Test
    @DisplayName("is plain text, without a terminal")
    void plain() {
      ConsoleOutput output = output();

      output.submit(event("hello"));
      output.close();

      assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("\u001B"));
      assertNull(output.terminal());
    }
  }
}
