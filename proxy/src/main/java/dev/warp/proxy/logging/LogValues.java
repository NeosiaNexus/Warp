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

import org.jspecify.annotations.Nullable;

/**
 * Makes a value that a remote client or a player chose safe to put in a log line: a command, an
 * operator's name, a forwarded address, a path or a filter.
 *
 * <p>The console and file layouts already strip control characters, but a JSON layout, a plugin's
 * log sink or a log shipper receives the message as it was formatted: a line break in such a value
 * would forge a second line there, and an escape sequence could repaint the terminal of whoever
 * reads the file. Every such value goes through {@link #safe(String)} at the logging call.
 */
public final class LogValues {

  /** The most characters of a value {@link #safe(String)} keeps. */
  public static final int MAX_LENGTH = 256;

  private LogValues() {}

  /**
   * Returns the value on one line, with nothing that a terminal or a log reader would act on, cut
   * at {@link #MAX_LENGTH} characters.
   *
   * @param value the value, or {@code null}
   * @return the value made safe, {@code null} as {@code "null"}
   * @see #safe(String, int)
   */
  public static String safe(@Nullable String value) {
    return safe(value, MAX_LENGTH);
  }

  /**
   * Returns the value on one line, with nothing that a terminal or a log reader would act on.
   *
   * <p>Carriage returns and line feeds become {@code \r} and {@code \n}, tabs become {@code \t},
   * and every other control character, line or paragraph separator (U+2028, U+2029), invisible
   * format character (such as a bidirectional override, which makes a line read differently from
   * what it holds) and unpaired surrogate becomes its {@code \}{@code uXXXX} escape. A value longer
   * than {@code maxLength} characters keeps its first {@code maxLength} (never half of a surrogate
   * pair) and ends with {@code ...(N more)}, N counting the characters left out.
   *
   * @param value the value, or {@code null}
   * @param maxLength the most characters of the value to keep, 1 or more
   * @return the value made safe, {@code null} as {@code "null"}
   * @throws IllegalArgumentException if {@code maxLength} is less than 1
   */
  public static String safe(@Nullable String value, int maxLength) {
    if (maxLength < 1) {
      throw new IllegalArgumentException("maxLength must be 1 or more, not " + maxLength);
    }
    if (value == null) {
      return "null";
    }
    int kept = keptLength(value, maxLength);
    // Line breaks first, and explicitly: they are what forges a second log line.
    String oneLine = value.substring(0, kept).replace("\r", "\\r").replace("\n", "\\n");
    StringBuilder out = new StringBuilder(oneLine.length() + 16);
    for (int i = 0; i < oneLine.length(); ) {
      int codePoint = oneLine.codePointAt(i);
      i += Character.charCount(codePoint);
      if (codePoint == '\t') {
        out.append("\\t");
      } else if (unsafe(codePoint)) {
        for (char unit : Character.toChars(codePoint)) {
          out.append(String.format("\\u%04X", (int) unit));
        }
      } else {
        out.appendCodePoint(codePoint);
      }
    }
    if (kept < value.length()) {
      out.append("...(").append(value.length() - kept).append(" more)");
    }
    return out.toString();
  }

  private static int keptLength(String value, int maxLength) {
    if (value.length() <= maxLength) {
      return value.length();
    }
    boolean splitsPair =
        Character.isHighSurrogate(value.charAt(maxLength - 1))
            && Character.isLowSurrogate(value.charAt(maxLength));
    return splitsPair ? maxLength - 1 : maxLength;
  }

  private static boolean unsafe(int codePoint) {
    return switch (Character.getType(codePoint)) {
      case Character.CONTROL,
          Character.LINE_SEPARATOR,
          Character.PARAGRAPH_SEPARATOR,
          Character.FORMAT,
          Character.SURROGATE ->
          true;
      default -> false;
    };
  }
}
