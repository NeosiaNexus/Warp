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
package dev.warp.proxy.migrate;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes a commented {@code warp.conf}, in the layout of the default one: two-space indentation,
 * one comment above each value saying where it came from.
 */
final class HoconWriter {

  /** A value written as is, such as a duration ({@code 5s}) or a bare word ({@code auto}). */
  record Raw(String text) {}

  /** The width of the default warp.conf's comments. */
  private static final int WIDTH = 100;

  private final StringBuilder out = new StringBuilder();
  private int depth;

  /**
   * Writes a comment, one line per line of the text.
   *
   * @param text the comment, without the leading {@code #}
   */
  void comment(String text) {
    for (String line : text.split("\n", -1)) {
      for (String wrapped : wrap(line, WIDTH - 2 - 2 * depth)) {
        indent().append('#').append(wrapped.isEmpty() ? "" : " " + wrapped).append('\n');
      }
    }
  }

  /** Breaks a comment line at spaces so that it fits the width; a long word stays whole. */
  private static List<String> wrap(String line, int width) {
    if (line.length() <= width) {
      return List.of(line);
    }
    List<String> lines = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String word : line.split(" ", -1)) {
      if (!current.isEmpty() && current.length() + 1 + word.length() > width) {
        lines.add(current.toString());
        current.setLength(0);
      }
      if (!current.isEmpty()) {
        current.append(' ');
      }
      current.append(word);
    }
    lines.add(current.toString());
    return lines;
  }

  /** Writes an empty line. */
  void blank() {
    out.append('\n');
  }

  /**
   * Writes {@code key = value}. Strings are quoted and escaped, numbers and booleans written as is,
   * lists of strings in brackets, and {@link Raw} values verbatim.
   *
   * @param key the key, quoted if it needs to be
   * @param value the value
   */
  void value(String key, Object value) {
    indent().append(quoteKey(key)).append(" = ").append(render(value)).append('\n');
  }

  /**
   * Opens a block, {@code key} followed by an opening brace.
   *
   * @param key the key, quoted if it needs to be
   */
  void open(String key) {
    indent().append(quoteKey(key)).append(" {\n");
    depth++;
  }

  /** Closes the innermost block. */
  void close() {
    depth--;
    indent().append("}\n");
  }

  /**
   * Returns the text written so far.
   *
   * @return the HOCON document
   */
  String text() {
    return out.toString();
  }

  /**
   * Quotes a string for HOCON.
   *
   * @param text the string
   * @return the quoted string
   */
  static String quote(String text) {
    StringBuilder quoted = new StringBuilder("\"");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> quoted.append("\\\"");
        case '\\' -> quoted.append("\\\\");
        case '\n' -> quoted.append("\\n");
        case '\r' -> quoted.append("\\r");
        case '\t' -> quoted.append("\\t");
        default -> {
          if (c < 0x20) {
            quoted.append(String.format("\\u%04x", (int) c));
          } else {
            quoted.append(c);
          }
        }
      }
    }
    return quoted.append('"').toString();
  }

  private static String quoteKey(String key) {
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      boolean plain =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_';
      if (!plain) {
        return quote(key);
      }
    }
    return key.isEmpty() ? "\"\"" : key;
  }

  private static String render(Object value) {
    if (value instanceof String s) {
      return quote(s);
    }
    if (value instanceof Raw raw) {
      return raw.text();
    }
    if (value instanceof List<?> list) {
      StringBuilder rendered = new StringBuilder("[");
      for (Object element : list) {
        if (rendered.length() > 1) {
          rendered.append(", ");
        }
        rendered.append(render(element));
      }
      return rendered.append(']').toString();
    }
    return String.valueOf(value);
  }

  private StringBuilder indent() {
    return out.append("  ".repeat(depth));
  }
}
