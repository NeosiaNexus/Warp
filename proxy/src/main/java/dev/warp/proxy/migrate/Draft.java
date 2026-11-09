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
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The {@code warp.conf} being built: Warp keys with their values and the source setting each one
 * came from, rendered in the order they were set, nested blocks included.
 */
final class Draft {

  /** A node of the tree: a value with its origin, or a block of children. */
  private static final class Node {
    private final Map<String, Node> children = new LinkedHashMap<>();
    private @Nullable Object value;
    private @Nullable String origin;

    Map<String, Node> children() {
      return children;
    }

    @Nullable Object value() {
      return value;
    }

    @Nullable String origin() {
      return origin;
    }

    void set(Object newValue, String newOrigin) {
      value = newValue;
      origin = newOrigin;
    }
  }

  private final Node root = new Node();

  /**
   * Sets a value under a dotted key, such as {@code status.motd}.
   *
   * @param key the Warp key, dotted
   * @param value the value: a string, number, boolean, list of strings or {@link HoconWriter.Raw}
   * @param origin the source setting it came from, written as a comment above it
   */
  void set(String key, Object value, String origin) {
    set(List.of(key.split("\\.", -1)), value, origin);
  }

  /**
   * Sets a value under a key whose parts may hold dots, such as a route's host name.
   *
   * @param path the Warp key, one element per level
   * @param value the value
   * @param origin the source setting it came from, written as a comment above it
   */
  void set(List<String> path, Object value, String origin) {
    Node node = root;
    for (String part : path) {
      node = node.children().computeIfAbsent(part, k -> new Node());
    }
    node.set(value, origin);
  }

  /**
   * Returns whether a key was set.
   *
   * @param key the Warp key, dotted
   * @return whether {@link #set} was called for it
   */
  boolean has(String key) {
    Node node = root;
    for (String part : key.split("\\.", -1)) {
      Node child = node.children().get(part);
      if (child == null) {
        return false;
      }
      node = child;
    }
    return node.value() != null;
  }

  /**
   * Returns the value of a key.
   *
   * @param key the Warp key, dotted
   * @return the value, or null when the key was not set
   */
  @Nullable Object get(String key) {
    Node node = root;
    for (String part : key.split("\\.", -1)) {
      Node child = node.children().get(part);
      if (child == null) {
        return null;
      }
      node = child;
    }
    return node.value();
  }

  /**
   * Returns the keys set, dotted, in order.
   *
   * @return the keys
   */
  List<String> keys() {
    List<String> keys = new ArrayList<>();
    collect(root, "", keys);
    return List.copyOf(keys);
  }

  /**
   * Renders the configuration.
   *
   * @param header the comment at the top of the file
   * @return the HOCON document
   */
  String render(String header) {
    HoconWriter writer = new HoconWriter();
    writer.comment(header);
    writer.blank();
    render(writer, root, true);
    return writer.text();
  }

  private static void render(HoconWriter writer, Node node, boolean top) {
    boolean first = true;
    for (Map.Entry<String, Node> entry : node.children().entrySet()) {
      Node child = entry.getValue();
      if (top && !first) {
        writer.blank();
      }
      first = false;
      if (child.value() != null) {
        if (child.origin() != null) {
          writer.comment(child.origin());
        }
        writer.value(entry.getKey(), child.value());
      } else {
        writer.open(entry.getKey());
        render(writer, child, false);
        writer.close();
      }
    }
  }

  private static void collect(Node node, String prefix, List<String> keys) {
    for (Map.Entry<String, Node> entry : node.children().entrySet()) {
      String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      if (entry.getValue().value() != null) {
        keys.add(key);
      } else {
        collect(entry.getValue(), key, keys);
      }
    }
  }
}
