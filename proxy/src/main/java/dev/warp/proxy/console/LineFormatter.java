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

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.zone.ZoneRules;
import java.util.function.Function;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.Message;
import org.jspecify.annotations.Nullable;

/**
 * Formats a log record into console text:
 *
 * <pre>
 * 14:02:11 INFO  antibot   Attack over after 42 s: 1,204 connections refused
 * </pre>
 *
 * <p>The time without the date (the file has it), a fixed-width level badge, a short {@link
 * SourceTags source tag}, then the message with its values coloured. Lines of a multi-line message
 * line up under the first; stack traces follow, rendered by {@link ThrowableRenderer}. Components
 * sent to the console (command replies) and {@link ConsoleBlock blocks} print without the prefix.
 */
final class LineFormatter {

  /** Marks the echo of a command typed at the console: a terminal already shows it. */
  static final Marker ECHO = MarkerManager.getMarker("WARP_CONSOLE_ECHO");

  private static final String TIME_PLACEHOLDER = "00:00:00";

  /** Where messages start: time, level badge and source tag, each followed by a space. */
  static final int MESSAGE_COLUMN = TIME_PLACEHOLDER.length() + 1 + 5 + 1 + SourceTags.WIDTH + 1;

  private static final String CONTINUATION = "\n" + " ".repeat(MESSAGE_COLUMN);

  /** Indentation of command replies and stack traces. */
  private static final String INDENT = "  ";

  private final ColorDepth depth;
  private final Glyphs glyphs;
  private final boolean echoed;
  private final ZoneRules zone;
  private final Function<Component, String> components;

  /** The last second formatted and its text: a record, so that both change at once. */
  private volatile Second second = new Second(Long.MIN_VALUE, TIME_PLACEHOLDER, Long.MIN_VALUE);

  private record Second(long epochSecond, String text, long epochDay) {}

  /** The day of the last line, to mark a change of day: the console shows times only. */
  private volatile long lastDay = Long.MIN_VALUE;

  /**
   * Creates a formatter.
   *
   * @param mode what the console may write
   * @param zone the time zone of the times shown
   */
  LineFormatter(ConsoleMode mode, ZoneId zone) {
    this.depth = mode.colors();
    this.glyphs = Glyphs.of(mode.unicode());
    // A terminal (the line discipline, or JLine) already shows what is typed at it.
    this.echoed = mode.tty() || mode.interactive();
    this.zone = zone.getRules();
    if (depth.colored()) {
      ANSIComponentSerializer ansi =
          ANSIComponentSerializer.builder().colorLevel(depth.adventureLevel()).build();
      this.components = ansi::serialize;
    } else {
      this.components = PlainTextComponentSerializer.plainText()::serialize;
    }
  }

  /**
   * Formats a record.
   *
   * @param event the record
   * @param names tells player and server names apart
   * @return the console line, or {@code null} when the console does not show this record
   */
  // Message#getFormat is deprecated, but it is the only way to the format of a parameterised
  // message.
  @SuppressWarnings("deprecation")
  @Nullable ConsoleLine format(LogEvent event, NameDirectory names) {
    Message message = event.getMessage();
    Level level = event.getLevel();
    if (message instanceof ConsoleBlock block) {
      return new ConsoleLine(level, block.render(depth, glyphs), null);
    }
    if (message instanceof ComponentMessage component) {
      String text = INDENT + components.apply(component.component()).replace("\n", "\n" + INDENT);
      return new ConsoleLine(level, text, null);
    }
    Marker marker = event.getMarker();
    if (marker != null
        && (marker.isInstanceOf(ConsoleMarkers.FILE_ONLY_NAME)
            || (echoed && marker.isInstanceOf(ECHO)))) {
      return null;
    }

    Styled out = new Styled(depth, glyphs);
    String time = time(event.getInstant().getEpochSecond());
    long day = second.epochDay();
    if (day != lastDay) {
      if (lastDay != Long.MIN_VALUE) {
        out.append(Style.MUTED, glyphs.rule().repeat(3) + " " + LocalDate.ofEpochDay(day) + " ")
            .append(Style.MUTED, glyphs.rule().repeat(3))
            .append('\n');
      }
      lastDay = day;
    }
    int start = out.length();
    out.append(Style.MUTED, time).append(' ');
    int body = out.length();
    badge(level, out);
    String tag = SourceTags.of(event.getLoggerName() == null ? "" : event.getLoggerName());
    out.append(Style.MUTED, tag).pad(SourceTags.WIDTH - tag.length()).append(' ');

    String formatted = message == null ? "" : message.getFormattedMessage();
    String clean = Ansi.clean(formatted == null ? "" : formatted);
    Styled text = new Styled(depth, glyphs);
    if (message != null && clean.equals(formatted)) {
      MessageHighlighter.append(message.getFormat(), message.getParameters(), clean, names, text);
    } else {
      text.append(clean);
    }
    String rendered = text.toString();
    out.append(rendered.indexOf('\n') < 0 ? rendered : rendered.replace("\n", CONTINUATION));

    Throwable thrown = event.getThrown();
    if (thrown != null) {
      ThrowableRenderer.append(thrown, INDENT, depth.colored(), out);
    }
    String line = out.toString();
    return new ConsoleLine(level, line, start == 0 ? line.substring(body) : null);
  }

  /** {@code ERROR}, {@code WARN }, {@code INFO }...: five columns whatever the level. */
  private static void badge(Level level, Styled out) {
    Style style =
        level.isMoreSpecificThan(Level.ERROR)
            ? Style.ERROR
            : level.isMoreSpecificThan(Level.WARN)
                ? Style.WARN
                : level.isMoreSpecificThan(Level.INFO) ? Style.INFO : Style.DEBUG;
    String name = level.name();
    if (name.length() > 5) {
      name = name.substring(0, 5);
    }
    out.append(style, name).pad(5 - name.length()).append(' ');
  }

  /** {@code HH:mm:ss} in the formatter's zone, formatted once per second. */
  private String time(long epochSecond) {
    Second cached = second;
    if (cached.epochSecond() == epochSecond) {
      return cached.text();
    }
    long local =
        epochSecond
            + zone.getOffset(java.time.Instant.ofEpochSecond(epochSecond)).getTotalSeconds();
    int ofDay = (int) Math.floorMod(local, 86_400L);
    char[] text = {
      digit(ofDay / 36_000),
      digit(ofDay / 3600 % 10),
      ':',
      digit(ofDay % 3600 / 600),
      digit(ofDay % 600 / 60),
      ':',
      digit(ofDay % 60 / 10),
      digit(ofDay % 10)
    };
    String formatted = new String(text);
    second = new Second(epochSecond, formatted, Math.floorDiv(local, 86_400L));
    return formatted;
  }

  private static char digit(int value) {
    return (char) ('0' + value);
  }
}
