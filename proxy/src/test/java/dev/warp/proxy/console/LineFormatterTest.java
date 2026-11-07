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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.time.ZoneOffset;
import java.util.Set;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LineFormatter")
class LineFormatterTest {

  /** 2026-11-03 14:02:11 UTC. */
  private static final long TIME = 1_793_714_531_000L;

  private static final ConsoleMode PLAIN = ConsoleMode.PLAIN;
  private static final ConsoleMode COLOURED =
      new ConsoleMode(ColorDepth.TRUE_COLOR, false, false, null, true, null);
  private static final ConsoleMode TTY =
      new ConsoleMode(ColorDepth.ANSI_16, true, true, null, true, null);

  private static final NameDirectory NAMES =
      new NameDirectory() {
        @Override
        public boolean isPlayer(String name) {
          return Set.of("Steve", "Alex").contains(name);
        }

        @Override
        public boolean isServer(String name) {
          return Set.of("lobby", "survival").contains(name);
        }
      };

  private static LogEvent event(
      Level level, String logger, Message message, @Nullable Throwable thrown) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName(logger)
        .setLevel(level)
        .setMessage(message)
        .setTimeMillis(TIME)
        .setThrown(thrown)
        .build();
  }

  private static LogEvent info(String format, Object... parameters) {
    return event(
        Level.INFO,
        "dev.warp.proxy.connection.ConnectedPlayer",
        new ParameterizedMessage(format, parameters),
        null);
  }

  private static String text(ConsoleMode mode, LogEvent event) {
    return java.util.Objects.requireNonNull(format(mode, event));
  }

  private static @Nullable String format(ConsoleMode mode, LogEvent event) {
    ConsoleLine line = new LineFormatter(mode, ZoneOffset.UTC).format(event, NAMES);
    return line == null ? null : line.text();
  }

  @Nested
  @DisplayName("a warning with a code")
  class Codes {

    private LogEvent coded(String message) {
      return Log4jLogEvent.newBuilder()
          .setLoggerName("dev.warp.proxy.WarpServer")
          .setLevel(Level.WARN)
          .setMarker(
              org.apache.logging.log4j.MarkerManager.getMarker(
                  dev.warp.proxy.logging.LogCode.PORT_IN_USE.id()))
          .setMessage(new org.apache.logging.log4j.message.SimpleMessage(message))
          .setTimeMillis(TIME)
          .build();
    }

    @Test
    @DisplayName("should end the first line with the code, in plain text too")
    void shouldEndWithTheCode() {
      // given
      LogEvent event = coded("Cannot listen\n  more");

      // when
      String line = text(PLAIN, event);

      // then
      assertTrue(line.startsWith("14:02:11 WARN  warp       Cannot listen  W-START-002\n"), line);
    }

    @Test
    @DisplayName("should dim the code and link it to its documentation where links show")
    void shouldLinkTheCode() {
      // given
      ConsoleMode links =
          new ConsoleMode(
              ColorDepth.TRUE_COLOR, false, true, null, true, Theme.DARK, false, true, null);

      // when
      String line = text(links, coded("Cannot listen"));

      // then
      String muted = Palette.of(ColorDepth.TRUE_COLOR, Theme.DARK, true).open(Style.MUTED);
      String link = dev.warp.proxy.logging.LogCode.PORT_IN_USE.documentation();
      assertTrue(
          line.endsWith(
              "\u001B]8;;"
                  + link
                  + "\u001B\\"
                  + muted
                  + "W-START-002"
                  + Ansi.RESET
                  + "\u001B]8;;\u001B\\"),
          line);
    }

    @Test
    @DisplayName("should colour the same line in the light theme's colours")
    void shouldFollowTheTheme() {
      // given
      ConsoleMode light =
          new ConsoleMode(
              ColorDepth.TRUE_COLOR, false, true, null, true, Theme.LIGHT, false, false, null);

      // when
      String line = text(light, coded("Cannot listen"));

      // then
      assertTrue(
          line.contains(Palette.of(ColorDepth.TRUE_COLOR, Theme.LIGHT, false).open(Style.WARN)));
      assertFalse(
          line.contains(Palette.of(ColorDepth.TRUE_COLOR, Theme.DARK, false).open(Style.WARN)));
    }
  }

  @Nested
  @DisplayName("a log line")
  class Lines {

    @Test
    @DisplayName("has the time, a five-column level, the source tag and the message")
    void layout() {
      assertEquals(
          "14:02:11 INFO  conn       Steve joined lobby",
          format(PLAIN, info("{} joined {}", "Steve", "lobby")));
      assertEquals(
          "14:02:11 WARN  antibot    attack",
          format(
              PLAIN,
              event(Level.WARN, "dev.warp.proxy.antibot.X", new SimpleMessage("attack"), null)));
    }

    @Test
    @DisplayName("is plain text without colours: not a single escape code")
    void plain() {
      String line = format(PLAIN, info("{} joined {} in {} ms", "Steve", "lobby", 12));

      assertNotNull(line);
      assertFalse(line.contains("\u001B"));
    }

    @Test
    @DisplayName("reads the same coloured once the colours are removed")
    void colouredMatchesPlain() {
      LogEvent event =
          info("{} joined {} from {} in {} ms", "Steve", "lobby", "/10.0.0.1:5000", 12);

      String coloured = format(COLOURED, event);

      assertNotNull(coloured);
      assertTrue(coloured.contains("\u001B["));
      assertEquals(format(PLAIN, event), Ansi.strip(coloured));
    }

    @Test
    @DisplayName("lines up the lines of a multi-line message under the first")
    void multiLine() {
      assertEquals(
          "14:02:11 INFO  conn       first\n" + " ".repeat(LineFormatter.MESSAGE_COLUMN) + "second",
          format(PLAIN, info("first\nsecond")));
    }

    @Test
    @DisplayName("marks a change of day, as the console shows times only")
    void dayChange() {
      LineFormatter formatter = new LineFormatter(PLAIN, ZoneOffset.UTC);
      LogEvent today = info("before midnight");
      LogEvent tomorrow =
          Log4jLogEvent.newBuilder()
              .setLoggerName("dev.warp.proxy.WarpServer")
              .setLevel(Level.INFO)
              .setMessage(new SimpleMessage("after midnight"))
              .setTimeMillis(TIME + 86_400_000L)
              .build();

      var _ = formatter.format(today, NAMES);
      ConsoleLine line = formatter.format(tomorrow, NAMES);

      assertNotNull(line);
      assertEquals("--- 2026-11-04 ---\n14:02:11 INFO  warp       after midnight", line.text());
    }

    @Test
    @DisplayName("removes control characters and escape codes a message carries")
    void cleans() {
      String line = format(COLOURED, info("{} joined", "Ste\rve\u001B[2J"));

      assertNotNull(line);
      assertTrue(Ansi.strip(line).endsWith("Steve joined"));
      assertFalse(line.contains("\r"));
      assertFalse(line.contains("[2J"));
    }
  }

  @Nested
  @DisplayName("highlighting")
  class Highlighting {

    private String styled(Style style, String text) {
      return Palette.of(ColorDepth.TRUE_COLOR, Theme.DARK, false).open(style) + text + Ansi.RESET;
    }

    @Test
    @DisplayName("colours players, servers and numbers, and dims addresses")
    void values() {
      String line =
          format(
              COLOURED,
              info("{} joined {} from {} in {} ms", "Steve", "lobby", "/10.0.0.1:5000", 12));

      assertNotNull(line);
      assertTrue(line.contains(styled(Style.PLAYER, "Steve")));
      assertTrue(line.contains(styled(Style.SERVER, "lobby")));
      assertTrue(line.contains(styled(Style.MUTED, "/10.0.0.1:5000")));
      assertTrue(line.contains(styled(Style.NUMBER, "12")));
    }

    @Test
    @DisplayName("knows values by their type")
    void types() {
      String line =
          format(COLOURED, info("{} from {}", 3.5, new InetSocketAddress("127.0.0.1", 25565)));

      assertNotNull(line);
      assertTrue(line.contains(styled(Style.NUMBER, "3.5")));
      assertTrue(
          line.contains(
              Palette.of(ColorDepth.TRUE_COLOR, Theme.DARK, false).open(Style.MUTED)
                  + "/127.0.0.1:25565"));
    }

    @Test
    @DisplayName("never colours the words of the message itself")
    void words() {
      String line = format(COLOURED, info("Steve joined lobby {} times", 2));

      assertNotNull(line);
      assertTrue(line.contains("Steve joined lobby " + styled(Style.NUMBER, "2") + " times"));
    }

    @Test
    @DisplayName("leaves a value it cannot place uncoloured, with the exact text")
    void adjacent() {
      LogEvent event = info("{}{}", "Steve", "lobby");

      assertEquals("14:02:11 INFO  conn       Stevelobby", Ansi.strip(text(COLOURED, event)));
    }

    @Test
    @DisplayName("keeps escaped and unfilled placeholders as text")
    void placeholders() {
      assertEquals(
          "14:02:11 INFO  conn       Steve {} {}",
          Ansi.strip(text(COLOURED, info("{} \\{} {}", "Steve"))));
    }
  }

  @Nested
  @DisplayName("stack traces")
  class Traces {

    private Throwable nested() {
      IllegalStateException cause = new IllegalStateException("inner");
      cause.setStackTrace(
          new StackTraceElement[] {
            new StackTraceElement("io.netty.A", "a", "A.java", 1),
            new StackTraceElement("io.netty.B", "b", "B.java", 2),
            new StackTraceElement("io.netty.C", "c", "C.java", 3),
            new StackTraceElement("io.netty.D", "d", "D.java", 4),
            new StackTraceElement("dev.warp.proxy.Main", "main", "Main.java", 9),
          });
      RuntimeException top = new RuntimeException("outer", cause);
      top.setStackTrace(
          new StackTraceElement[] {
            new StackTraceElement("dev.warp.proxy.X", "run", "X.java", 5),
            new StackTraceElement("dev.warp.proxy.Main", "main", "Main.java", 9),
          });
      return top;
    }

    @Test
    @DisplayName("show every frame on a plain output, the shared ones counted")
    void full() {
      String line =
          format(
              PLAIN, event(Level.ERROR, "dev.warp.proxy.X", new SimpleMessage("boom"), nested()));

      assertNotNull(line);
      assertTrue(line.contains("java.lang.RuntimeException: outer"));
      assertTrue(line.contains("Caused by: java.lang.IllegalStateException: inner"));
      assertTrue(line.contains("at io.netty.C.c(C.java:3)"));
      assertTrue(line.contains("... 1 more"));
    }

    @Test
    @DisplayName("collapse runs of library frames on a coloured console, Warp's frames kept")
    void compact() {
      String line =
          Ansi.strip(
              text(
                  COLOURED,
                  event(Level.ERROR, "dev.warp.proxy.X", new SimpleMessage("boom"), nested())));

      assertTrue(line.contains("at io.netty.A.a(A.java:1)"));
      assertTrue(line.contains("… 3 frames in io.netty"));
      assertFalse(line.contains("C.java"));
      assertTrue(line.contains("at dev.warp.proxy.X.run(X.java:5)"));
    }

    @Test
    @DisplayName("survive a cause that loops back")
    void circular() {
      RuntimeException a = new RuntimeException("a");
      RuntimeException b = new RuntimeException("b", a);
      a.initCause(b);

      String line = format(PLAIN, event(Level.ERROR, "x.y.Z", new SimpleMessage("loop"), a));

      assertNotNull(line);
      assertTrue(line.contains("[circular reference: java.lang.RuntimeException]"));
    }
  }

  @Nested
  @DisplayName("special records")
  class Special {

    @Test
    @DisplayName("print a block without the time, level and source")
    void block() {
      ConsoleBlock block = ConsoleBlock.builder("Title").text("  hello").end().build();

      assertEquals(
          "  hello", format(PLAIN, event(Level.INFO, "dev.warp.proxy.WarpServer", block, null)));
    }

    @Test
    @DisplayName("render a component sent to the console in colour, indented")
    void component() {
      Message message = new ComponentMessage(Component.text("Hi", NamedTextColor.GREEN));

      assertEquals(
          "  Hi",
          format(PLAIN, event(Level.INFO, "dev.warp.proxy.console.Console", message, null)));
      String coloured =
          format(COLOURED, event(Level.INFO, "dev.warp.proxy.console.Console", message, null));
      assertNotNull(coloured);
      assertTrue(coloured.contains("\u001B["));
    }

    @Test
    @DisplayName("leave out the echo of a command where a terminal already shows it")
    void echo() {
      LogEvent echo =
          Log4jLogEvent.newBuilder()
              .setLoggerName("dev.warp.proxy.console.Console")
              .setLevel(Level.INFO)
              .setMarker(LineFormatter.ECHO)
              .setMessage(new SimpleMessage("> glist"))
              .setTimeMillis(TIME)
              .build();

      assertNull(format(TTY, echo));
      assertEquals("14:02:11 INFO  console    > glist", format(PLAIN, echo));
    }

    @Test
    @DisplayName("leave out the records meant for the log file only")
    void fileOnly() {
      Marker fileOnly = MarkerManager.getMarker(ConsoleMarkers.FILE_ONLY_NAME);
      LogEvent event =
          Log4jLogEvent.newBuilder()
              .setLoggerName("dev.warp.proxy.config.WarpConfig")
              .setLevel(Level.INFO)
              .setMarker(fileOnly)
              .setMessage(new SimpleMessage("  bind: 0.0.0.0:25565"))
              .setTimeMillis(TIME)
              .build();

      assertNull(format(PLAIN, event));
    }
  }
}
