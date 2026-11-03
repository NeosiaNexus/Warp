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
package dev.warp.protocol.text;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.NumberBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

/**
 * Writes a component tree as compact JSON text, escaped as the Gson of the vanilla game escapes it,
 * so that the text is the one a vanilla server sends.
 *
 * <p>Gson escapes the quote, the backslash and every control character ({@code \b \f \n \r \t} by
 * name, the others as {@code \}{@code u00XX}), and U+2028 and U+2029, which JavaScript reads as
 * line breaks. Up to 1.12.2 the game's Gson also escapes {@code < > & = '} (HTML-safe, Gson's
 * default).
 */
final class JsonTree implements TreeSink {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private final StringBuilder out = new StringBuilder(64);
  private final boolean htmlSafe;

  JsonTree(boolean htmlSafe) {
    this.htmlSafe = htmlSafe;
  }

  /** Returns the JSON text written so far. */
  String text() {
    return out.toString();
  }

  @Override
  public boolean homogeneousLists() {
    return false;
  }

  // ---------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------

  @Override
  public void string(String key, String value) {
    name(key);
    quote(value);
  }

  @Override
  public void bool(String key, boolean value) {
    name(key);
    out.append(value);
  }

  @Override
  public void integer(String key, int value) {
    name(key);
    out.append(value);
  }

  @Override
  public void intArray(String key, int[] values) {
    name(key);
    out.append('[');
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(values[i]);
    }
    out.append(']');
  }

  @Override
  public void tag(String key, BinaryTag tag) {
    name(key);
    value(tag);
  }

  @Override
  public void beginObject(String key) {
    name(key);
    out.append('{');
  }

  @Override
  public void beginList(String key, Kind kind, int size) {
    name(key);
    out.append('[');
  }

  // ---------------------------------------------------------------------------
  // Values
  // ---------------------------------------------------------------------------

  @Override
  public void stringValue(String value) {
    element();
    quote(value);
  }

  @Override
  public void beginObjectValue() {
    element();
    out.append('{');
  }

  @Override
  public void endObject() {
    out.append('}');
  }

  @Override
  public void endList() {
    out.append(']');
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  /** Starts a field: a comma unless it is the first of its object, then the quoted name. */
  private void name(String key) {
    if (out.charAt(out.length() - 1) != '{') {
      out.append(',');
    }
    quote(key);
    out.append(':');
  }

  /** Starts a value: a comma unless it is the root or the first element of its list. */
  private void element() {
    if (!out.isEmpty() && out.charAt(out.length() - 1) != '[') {
      out.append(',');
    }
  }

  /** Writes an NBT value as vanilla converts it to JSON: numbers, strings, arrays and objects. */
  private void value(BinaryTag tag) {
    switch (tag) {
      case StringBinaryTag string -> quote(string.value());
      case NumberBinaryTag number -> out.append(number.numberValue());
      case CompoundBinaryTag compound -> {
        out.append('{');
        boolean first = true;
        for (var entry : compound) {
          if (!first) {
            out.append(',');
          }
          first = false;
          quote(entry.getKey());
          out.append(':');
          value(entry.getValue());
        }
        out.append('}');
      }
      case ListBinaryTag list -> {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          value(list.get(i));
        }
        out.append(']');
      }
      case ByteArrayBinaryTag bytes -> {
        out.append('[');
        for (int i = 0; i < bytes.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          out.append(bytes.get(i));
        }
        out.append(']');
      }
      case IntArrayBinaryTag ints -> {
        out.append('[');
        for (int i = 0; i < ints.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          out.append(ints.get(i));
        }
        out.append(']');
      }
      case LongArrayBinaryTag longs -> {
        out.append('[');
        for (int i = 0; i < longs.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          out.append(longs.get(i));
        }
        out.append(']');
      }
      default -> throw new IllegalArgumentException("No JSON form for NBT " + tag.type());
    }
  }

  /** Writes {@code text} as a JSON string literal, escaped as Gson escapes it. */
  private void quote(String text) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\u2028', '\u2029' -> unicodeEscape(c);
        case '<', '>', '&', '=', '\'' -> {
          if (htmlSafe) {
            unicodeEscape(c);
          } else {
            out.append(c);
          }
        }
        default -> {
          if (c < 0x20) {
            unicodeEscape(c);
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  private void unicodeEscape(char c) {
    out.append("\\u")
        .append(HEX[(c >> 12) & 0xF])
        .append(HEX[(c >> 8) & 0xF])
        .append(HEX[(c >> 4) & 0xF])
        .append(HEX[c & 0xF]);
  }
}
