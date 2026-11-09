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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The parsed configuration of the proxy being migrated, whatever its format: nested tables of
 * {@link String}, {@link Long}, {@link Double}, {@link Boolean}, {@link List} and {@link Map}
 * values. Every key read is remembered, so that the report can account for the ones nobody read.
 */
final class Source {

  /** A value of a kind the migration did not expect, such as a table where a string was. */
  record Mismatch(String key, String expected, String actual) {}

  private final Map<String, Object> root;
  private final Set<String> consumed = new LinkedHashSet<>();
  private final List<Mismatch> mismatches = new ArrayList<>();

  Source(Map<String, Object> root) {
    this.root = root;
  }

  /**
   * Returns a string value, consuming the key.
   *
   * @param path the key, one element per level
   * @return the value, empty when absent or of another kind
   */
  Optional<String> string(String... path) {
    Object value = consume(path);
    if (value instanceof String s) {
      return Optional.of(s);
    }
    if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
      return Optional.of(value.toString());
    }
    return mismatch(value, "a string", path);
  }

  /**
   * Returns an integer value, consuming the key.
   *
   * @param path the key, one element per level
   * @return the value, empty when absent or of another kind
   */
  Optional<Long> integer(String... path) {
    Object value = consume(path);
    if (value instanceof Long l) {
      return Optional.of(l);
    }
    if (value instanceof Double d && d == Math.rint(d)) {
      return Optional.of(d.longValue());
    }
    if (value instanceof String s) {
      try {
        return Optional.of(Long.parseLong(s.strip()));
      } catch (NumberFormatException e) {
        // Not a number written as a string either: reported below.
      }
    }
    return mismatch(value, "a number", path);
  }

  /**
   * Returns a boolean value, consuming the key.
   *
   * @param path the key, one element per level
   * @return the value, empty when absent or of another kind
   */
  Optional<Boolean> bool(String... path) {
    Object value = consume(path);
    if (value instanceof Boolean b) {
      return Optional.of(b);
    }
    if (value instanceof String s) {
      String lower = s.strip().toLowerCase(Locale.ROOT);
      if (lower.equals("true") || lower.equals("false")) {
        return Optional.of(lower.equals("true"));
      }
    }
    return mismatch(value, "true or false", path);
  }

  /**
   * Returns a list of strings, consuming the key. A single string counts as a list of one.
   *
   * @param path the key, one element per level
   * @return the value, empty when absent or of another kind
   */
  Optional<List<String>> strings(String... path) {
    Object value = consume(path);
    if (value instanceof String s) {
      return Optional.of(List.of(s));
    }
    if (value instanceof List<?> list) {
      List<String> strings = new ArrayList<>();
      for (Object element : list) {
        if (element instanceof Map<?, ?> || element instanceof List<?>) {
          return mismatch(value, "a list of strings", path);
        }
        strings.add(String.valueOf(element));
      }
      return Optional.of(List.copyOf(strings));
    }
    return mismatch(value, "a list of strings", path);
  }

  /**
   * Returns a table, consuming its key but none of its entries: the caller reads them, or {@link
   * #consumeAll} drops them.
   *
   * @param path the key, one element per level
   * @return the table, empty when absent or of another kind
   */
  Optional<Map<String, Object>> table(String... path) {
    Object value = consume(path);
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach((k, v) -> copy.put(String.valueOf(k), v));
      return Optional.of(copy);
    }
    return mismatch(value, "a table", path);
  }

  /**
   * Returns a list of tables, consuming the key.
   *
   * @param path the key, one element per level
   * @return the tables, empty when absent or of another kind
   */
  Optional<List<Map<String, Object>>> tables(String... path) {
    Object value = consume(path);
    if (value instanceof List<?> list) {
      List<Map<String, Object>> tables = new ArrayList<>();
      for (Object element : list) {
        if (!(element instanceof Map<?, ?> map)) {
          return mismatch(value, "a list of tables", path);
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((k, v) -> copy.put(String.valueOf(k), v));
        tables.add(copy);
      }
      return Optional.of(tables);
    }
    return mismatch(value, "a list of tables", path);
  }

  /**
   * Returns whether a key is present, without consuming it.
   *
   * @param path the key, one element per level
   * @return whether the key exists
   */
  boolean has(String... path) {
    return lookup(path) != null;
  }

  /**
   * Marks a key and everything under it as read.
   *
   * @param path the key, one element per level
   */
  void consumeAll(String... path) {
    Object value = consume(path);
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String[] child = new String[path.length + 1];
        System.arraycopy(path, 0, child, 0, path.length);
        child[path.length] = String.valueOf(entry.getKey());
        consumeAll(child);
      }
    }
  }

  /**
   * Returns the keys of values nobody read, in the order of the file.
   *
   * @return the keys, as {@code a.b.c}
   */
  List<String> unconsumed() {
    List<String> keys = new ArrayList<>();
    collectUnconsumed(root, "", keys);
    return List.copyOf(keys);
  }

  /**
   * Returns the keys read so far, in the order they were read.
   *
   * @return the keys, as {@code a.b.c}
   */
  List<String> consumed() {
    return List.copyOf(consumed);
  }

  /**
   * Returns the values of a kind the migration did not expect.
   *
   * @return the mismatches, in the order they were met
   */
  List<Mismatch> mismatches() {
    return List.copyOf(mismatches);
  }

  /**
   * Renders a key for the report: {@code servers.lobby}, quoting a part that holds a dot.
   *
   * @param path the key, one element per level
   * @return the dotted key
   */
  static String join(String... path) {
    StringBuilder out = new StringBuilder();
    for (String part : path) {
      if (!out.isEmpty()) {
        out.append('.');
      }
      out.append(part.contains(".") ? "\"" + part + "\"" : part);
    }
    return out.toString();
  }

  /**
   * Renders a value for the report, as it was written: strings quoted, lists in brackets.
   *
   * @param value the value
   * @return its text
   */
  static String describe(@Nullable Object value) {
    if (value instanceof String s) {
      return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    if (value instanceof List<?> list) {
      StringBuilder out = new StringBuilder("[");
      for (Object element : list) {
        if (out.length() > 1) {
          out.append(", ");
        }
        out.append(describe(element));
      }
      return out.append(']').toString();
    }
    if (value instanceof Map<?, ?>) {
      return "{...}";
    }
    return String.valueOf(value);
  }

  private void collectUnconsumed(Map<String, Object> table, String prefix, List<String> keys) {
    for (Map.Entry<String, Object> entry : table.entrySet()) {
      String key = prefix.isEmpty() ? join(entry.getKey()) : prefix + "." + join(entry.getKey());
      if (consumed.contains(key)) {
        continue;
      }
      if (entry.getValue() instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> child = (Map<String, Object>) map;
        if (child.isEmpty()) {
          keys.add(key);
        } else {
          collectUnconsumed(child, key, keys);
        }
      } else {
        keys.add(key);
      }
    }
  }

  private @Nullable Object consume(String... path) {
    consumed.add(join(path));
    return lookup(path);
  }

  private @Nullable Object lookup(String... path) {
    Object current = root;
    for (String part : path) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(part);
      if (current == null) {
        return null;
      }
    }
    return current;
  }

  private <T> Optional<T> mismatch(@Nullable Object value, String expected, String... path) {
    if (value != null) {
      mismatches.add(new Mismatch(join(path), expected, kind(value)));
    }
    return Optional.empty();
  }

  private static String kind(Object value) {
    if (value instanceof Map<?, ?>) {
      return "a table";
    }
    if (value instanceof List<?>) {
      return "a list";
    }
    if (value instanceof String) {
      return "a string";
    }
    if (value instanceof Boolean) {
      return "true or false";
    }
    return "a number";
  }
}
