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
package dev.warp.proxy.fleet.valkey;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** A RESP3 value, as the server sends it. */
public sealed interface RespValue {

  /**
   * A simple string ({@code +OK}).
   *
   * @param value the text
   */
  record Simple(String value) implements RespValue {}

  /**
   * A blob ({@code $5\r\nhello}) or verbatim string.
   *
   * @param bytes the bytes, as sent
   */
  record Blob(byte[] bytes) implements RespValue {
    /**
     * Returns the blob as UTF-8 text.
     *
     * @return the text
     */
    public String text() {
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  /**
   * An integer ({@code :42}).
   *
   * @param value the number
   */
  record Int(long value) implements RespValue {}

  /**
   * A double ({@code ,1.5}).
   *
   * @param value the number
   */
  record Dbl(double value) implements RespValue {}

  /**
   * A boolean ({@code #t}).
   *
   * @param value the truth
   */
  record Bool(boolean value) implements RespValue {}

  /** The null ({@code _}), or a null blob or array of RESP2. */
  record Null() implements RespValue {}

  /**
   * An error ({@code -ERR ...} or a blob error).
   *
   * @param message the error, its code first
   */
  record Err(String message) implements RespValue {
    /**
     * Returns the error's code, its first word.
     *
     * @return {@code ERR}, {@code NOSCRIPT}, {@code MOVED}...
     */
    public String code() {
      int space = message.indexOf(' ');
      return space < 0 ? message : message.substring(0, space);
    }
  }

  /**
   * An array ({@code *n}) or a set ({@code ~n}).
   *
   * @param items the elements
   */
  record Arr(List<RespValue> items) implements RespValue {}

  /**
   * A map ({@code %n}): keys and values alternate.
   *
   * @param entries the keys and values, alternating
   */
  record MapV(List<RespValue> entries) implements RespValue {}

  /**
   * A push ({@code >n}): a pub/sub message, or an invalidation.
   *
   * @param items the elements
   */
  record Push(List<RespValue> items) implements RespValue {}

  /**
   * A big number ({@code (}), kept as text.
   *
   * @param digits the number, as digits
   */
  record Big(String digits) implements RespValue {}

  /**
   * Returns the value as text: a simple or blob string, an integer, a double or a boolean.
   *
   * @return the text, or {@code null} for the null value
   * @throws IllegalStateException for an aggregate or an error
   */
  default @org.jspecify.annotations.Nullable String text() {
    return switch (this) {
      case Simple(String value) -> value;
      case Blob blob -> blob.text();
      case Int(long value) -> Long.toString(value);
      case Dbl(double value) -> Double.toString(value);
      case Bool(boolean value) -> Boolean.toString(value);
      case Big(String digits) -> digits;
      case Null() -> null;
      case Err(String message) -> throw new IllegalStateException(message);
      case Arr _, MapV _, Push _ -> throw new IllegalStateException("aggregate, not text");
    };
  }

  /**
   * Returns the value as a list: an array, set or push, or the entries of a map.
   *
   * @return the items, empty for the null value
   * @throws IllegalStateException for a scalar or an error
   */
  default List<RespValue> items() {
    return switch (this) {
      case Arr(List<RespValue> items) -> items;
      case Push(List<RespValue> items) -> items;
      case MapV(List<RespValue> entries) -> entries;
      case Null() -> List.of();
      case Err(String message) -> throw new IllegalStateException(message);
      default -> throw new IllegalStateException("scalar, not an aggregate");
    };
  }
}
