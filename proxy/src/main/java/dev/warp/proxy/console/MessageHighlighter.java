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

import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerInfo;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Colours the values of a parameterised log message ({@code "{} joined {}"}): player names, server
 * names, numbers and durations, addresses dimmed. Only the parameters are coloured, never the words
 * of the message itself, so nothing is highlighted by accident.
 *
 * <p>The text is never formatted again: the parameters are found in the message Log4j already
 * formatted, between the literal parts of the format. The console therefore always shows exactly
 * the text of the log file, and calls no {@code toString()} on objects that another thread may be
 * changing; a message whose format does not match its text is shown uncoloured.
 */
final class MessageHighlighter {

  /** A number, possibly with a unit: {@code 12}, {@code 3.5 s}, {@code 250ms}, {@code 87%}. */
  private static final Pattern NUMBER =
      Pattern.compile(
          "[-+]?\\d[\\d,_]*(?:\\.\\d+)?(?:\\s?(?:ns|µs|us|ms|s|min|h|d|%|B|KiB|MiB|GiB|KB|MB|GB|x|"
              + "players?|bytes?))?");

  /** An IPv4 or bracketed IPv6 address, possibly with a port, as Java prints socket addresses. */
  private static final Pattern ADDRESS =
      Pattern.compile(
          "[\\w.-]*/?(?:\\d{1,3}(?:\\.\\d{1,3}){3}|\\[[0-9a-fA-F:.%\\w]+\\])(?::\\d{1,5})?");

  private MessageHighlighter() {}

  /**
   * Appends a message with its parameters coloured.
   *
   * @param format the message's format, with {@code {}} placeholders, or {@code null}
   * @param parameters the message's parameters, or {@code null}
   * @param formatted the message as Log4j formatted it
   * @param names tells player and server names apart
   * @param out where to append
   */
  static void append(
      @Nullable String format,
      Object @Nullable [] parameters,
      String formatted,
      NameDirectory names,
      Styled out) {
    if (!out.depth().colored()
        || format == null
        || parameters == null
        || parameters.length == 0
        || format.equals(formatted)) {
      out.append(formatted);
      return;
    }
    List<String> literals = literals(format);
    int values = Math.min(literals.size() - 1, parameters.length);
    if (values <= 0 || !split(literals, values, formatted, parameters, names, out)) {
      out.append(formatted);
    }
  }

  /**
   * Splits {@code formatted} at the literals and appends each piece; returns {@code false},
   * appending nothing, when the text does not match the literals.
   */
  private static boolean split(
      List<String> literals,
      int values,
      String formatted,
      Object[] parameters,
      NameDirectory names,
      Styled out) {
    String first = literals.getFirst();
    if (!formatted.startsWith(first)) {
      return false;
    }
    // The literal after the last value runs to the end, placeholders without a value included.
    String last = String.join("{}", literals.subList(values, literals.size()));
    int end = formatted.length() - last.length();
    if (!formatted.endsWith(last) || end < first.length()) {
      return false;
    }
    int[] starts = new int[values];
    int[] ends = new int[values];
    int position = first.length();
    for (int i = 0; i < values; i++) {
      starts[i] = position;
      if (i == values - 1) {
        ends[i] = end;
      } else {
        String next = literals.get(i + 1);
        // Two placeholders side by side leave no way to tell where one value ends.
        int found = next.isEmpty() ? -1 : formatted.indexOf(next, position);
        if (found < 0 || found > end) {
          return false;
        }
        ends[i] = found;
        position = found + next.length();
      }
      if (ends[i] < starts[i]) {
        return false;
      }
    }
    out.append(first);
    for (int i = 0; i < values; i++) {
      String text = formatted.substring(starts[i], ends[i]);
      out.append(classify(parameters[i], text, names), text);
      out.append(i == values - 1 ? last : literals.get(i + 1));
    }
    return true;
  }

  /**
   * Returns the literal parts of a format, around its placeholders, with escaped placeholders
   * ({@code \{}}) as text.
   */
  static List<String> literals(String format) {
    List<String> literals = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int length = format.length();
    int i = 0;
    while (i < length) {
      char c = format.charAt(i);
      if (c == '\\' && format.startsWith("{}", i + 1)) {
        current.append("{}");
        i += 3;
      } else if (c == '{' && i + 1 < length && format.charAt(i + 1) == '}') {
        literals.add(current.toString());
        current.setLength(0);
        i += 2;
      } else {
        current.append(c);
        i++;
      }
    }
    literals.add(current.toString());
    return literals;
  }

  /**
   * Returns the style of a value: by its type when it tells, else by its text.
   *
   * @param value the parameter
   * @param text its text in the message
   * @param names tells player and server names apart
   * @return the style, or {@code null} for none
   */
  static @Nullable Style classify(@Nullable Object value, String text, NameDirectory names) {
    if (text.isEmpty()) {
      return null;
    }
    return switch (value) {
      case Number _, Duration _ -> Style.NUMBER;
      case SocketAddress _, InetAddress _ -> Style.MUTED;
      case Player _ -> Style.PLAYER;
      case RegisteredServer _, ServerInfo _ -> Style.SERVER;
      case null, default -> classifyText(text, names);
    };
  }

  private static @Nullable Style classifyText(String text, NameDirectory names) {
    if (text.length() <= 16 && names.isPlayer(text)) {
      return Style.PLAYER;
    }
    if (names.isServer(text)) {
      return Style.SERVER;
    }
    char first = text.charAt(0);
    boolean digit = Character.isDigit(first) || first == '-' || first == '+';
    if (digit && NUMBER.matcher(text).matches()) {
      return Style.NUMBER;
    }
    if (ADDRESS.matcher(text).matches()) {
      return Style.MUTED;
    }
    return null;
  }
}
