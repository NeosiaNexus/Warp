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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.jspecify.annotations.Nullable;

/**
 * Asks the terminal for its background colour (OSC 11), so that the theme fits a light terminal as
 * well as a dark one.
 *
 * <p>The query is followed by a request for the terminal's attributes (DA1), which every terminal
 * answers, in order: once that answer arrives, an OSC 11 answer that has not come never will, and
 * the probe stops at once instead of waiting out its time-out. A terminal that answers neither in
 * {@value #TIMEOUT_MILLIS} ms leaves the default, dark. The probe runs once, before the first
 * prompt, so that no answer can land in what the operator types.
 */
final class BackgroundProbe {

  /** How long the terminal has to answer: enough for SSH, short enough to go unnoticed. */
  static final long TIMEOUT_MILLIS = 250;

  /** The background query, then the attributes request that ends the exchange. */
  private static final String QUERY = "\u001B]11;?\u001B\\\u001B[c";

  /** {@code rgb:RRRR/GGGG/BBBB}, each component 1 to 4 hexadecimal digits. */
  private static final Pattern COLOR =
      Pattern.compile(
          "\\]11;rgba?:([0-9a-fA-F]{1,4})/([0-9a-fA-F]{1,4})/([0-9a-fA-F]{1,4})(?:/[0-9a-fA-F]+)?");

  /** The end of the DA1 answer: {@code ESC [ ? ... c}. */
  private static final Pattern ATTRIBUTES = Pattern.compile("\u001B\\[\\?[0-9;]*c");

  private BackgroundProbe() {}

  /**
   * Asks the terminal whether its background is light.
   *
   * @param terminal the terminal, not yet read by a line reader
   * @return whether the background is light, or {@code null} when the terminal did not say
   */
  static @Nullable Boolean query(Terminal terminal) {
    Attributes saved = null;
    try {
      saved = terminal.enterRawMode();
      PrintWriter writer = terminal.writer();
      writer.write(QUERY);
      writer.flush();
      NonBlockingReader reader = terminal.reader();
      StringBuilder reply = new StringBuilder();
      long deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000;
      while (true) {
        long left = (deadline - System.nanoTime()) / 1_000_000;
        if (left <= 0) {
          break;
        }
        int c = reader.read(left);
        if (c < 0) {
          break;
        }
        reply.append((char) c);
        if (c == 'c' && ATTRIBUTES.matcher(reply).find()) {
          break;
        }
      }
      return parse(reply);
    } catch (IOException | RuntimeException e) {
      return null;
    } finally {
      if (saved != null) {
        try {
          terminal.setAttributes(saved);
        } catch (RuntimeException e) {
          // The terminal is gone: the console falls back to plain lines later on.
        }
      }
    }
  }

  /**
   * Reads the background out of a terminal's answers: light when black text reads better on it than
   * white text does (a relative luminance above about 0.18, the middle of perceived lightness).
   *
   * @param reply what the terminal answered
   * @return whether the background is light, or {@code null} when the answer holds no colour
   */
  static @Nullable Boolean parse(CharSequence reply) {
    Matcher matcher = COLOR.matcher(reply);
    if (!matcher.find()) {
      return null;
    }
    int rgb =
        (component(matcher.group(1)) << 16)
            | (component(matcher.group(2)) << 8)
            | component(matcher.group(3));
    return Theme.contrast(rgb, 0x000000) > Theme.contrast(rgb, 0xFFFFFF);
  }

  /** Scales a component of 1 to 4 hexadecimal digits to 8 bits. */
  private static int component(String hex) {
    int value = Integer.parseInt(hex, 16);
    int max = (1 << (4 * hex.length())) - 1;
    return Math.round(value * 255f / max);
  }
}
