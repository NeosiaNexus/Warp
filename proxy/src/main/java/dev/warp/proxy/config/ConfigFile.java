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
package dev.warp.proxy.config;

import dev.warp.proxy.config.ConfigurationException.Problem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import com.typesafe.config.ConfigValue;
import org.jspecify.annotations.Nullable;

/**
 * The layout of a HOCON file: every key, where it is, and whether it holds a block, a list or a
 * value. Warp reads it to point each error at its line, and to compare the operator's {@code
 * warp.conf} with the default one, whose keys are the settings that exist.
 */
final class ConfigFile {

  /** What a key holds. */
  enum Shape {
    BLOCK,
    LIST,
    VALUE
  }

  /** A key of the file: its line and shape. */
  record Entry(int line, Shape shape) {}

  /**
   * Blocks whose keys the operator names, such as the servers: {@code *} stands for any name in
   * {@link #normalize}.
   */
  private static final List<String> NAMED_BLOCKS =
      List.of("servers", "bungeecord-channel.allow", "logging.levels");

  /** Named blocks whose names may hold dots: a logger name such as dev.warp.proxy.antibot. */
  private static final String DOTTED_NAMES = "logging.levels";

  private final Map<String, Entry> entries;

  private ConfigFile(Map<String, Entry> entries) {
    this.entries = entries;
  }

  /**
   * Reads the layout of a file.
   *
   * @param file the file
   * @return its layout
   * @throws ConfigurationException if it is not valid HOCON, naming the line
   */
  static ConfigFile read(Path file) throws ConfigurationException {
    try {
      return of(
          ConfigFactory.parseFile(
                  file.toFile(),
                  ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setAllowMissing(false))
              .root());
    } catch (ConfigException e) {
      int line = e.origin() == null ? 0 : e.origin().lineNumber();
      String message = String.valueOf(e.getMessage());
      if (e.origin() != null) {
        String prefix = e.origin().description() + ": ";
        if (message.startsWith(prefix)) {
          message = message.substring(prefix.length());
        }
      }
      throw ConfigurationException.of(
          List.of(
              new Problem(
                  file,
                  Math.max(line, 0),
                  null,
                  "not valid HOCON: "
                      + message
                      + ". Check the quotes, the braces, and the = between each key and its"
                      + " value")));
    }
  }

  /**
   * Reads the layout of HOCON text.
   *
   * @param text the text
   * @return its layout
   * @throws IllegalArgumentException if it is not valid HOCON
   */
  static ConfigFile parse(String text) {
    try {
      return of(
          ConfigFactory.parseString(
                  text, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
              .root());
    } catch (ConfigException e) {
      throw new IllegalArgumentException("invalid HOCON: " + e.getMessage(), e);
    }
  }

  private static ConfigFile of(ConfigObject root) {
    Map<String, Entry> entries = new LinkedHashMap<>();
    walk(root, "", entries);
    return new ConfigFile(entries);
  }

  private static void walk(ConfigObject object, String prefix, Map<String, Entry> entries) {
    for (Map.Entry<String, ConfigValue> child : object.entrySet()) {
      String path = prefix.isEmpty() ? child.getKey() : prefix + "." + child.getKey();
      ConfigValue value = child.getValue();
      int line = value.origin() == null ? 0 : Math.max(value.origin().lineNumber(), 0);
      Shape shape =
          value instanceof ConfigObject
              ? Shape.BLOCK
              : value instanceof ConfigList ? Shape.LIST : Shape.VALUE;
      entries.put(path, new Entry(line, shape));
      if (value instanceof ConfigObject block) {
        walk(block, path, entries);
      }
    }
  }

  /**
   * Returns this layout with the operator's names replaced by {@code *} (see {@link #normalize}):
   * the default file's, to compare others with.
   *
   * @return the normalized layout
   */
  ConfigFile normalized() {
    Map<String, Entry> normalized = new LinkedHashMap<>();
    entries.forEach((key, entry) -> normalized.putIfAbsent(normalize(key), entry));
    return new ConfigFile(normalized);
  }

  /**
   * Returns the line of a key.
   *
   * @param key the dotted key, unquoted
   * @return its line, from 1, or 0 if the file does not have it
   */
  int lineOf(String key) {
    Entry entry = entries.get(key);
    return entry == null ? 0 : entry.line();
  }

  /**
   * Returns whether the file sets a key.
   *
   * @param key the dotted key, unquoted
   * @return whether it is there
   */
  boolean has(String key) {
    return entries.containsKey(key);
  }

  /**
   * Returns a key with the operator's names replaced by {@code *}: {@code servers.lobby.address}
   * reads {@code servers.*.address}.
   *
   * @param key the dotted key
   * @return the key as the default file would have it
   */
  static String normalize(String key) {
    for (String block : NAMED_BLOCKS) {
      if (key.startsWith(block + ".")) {
        if (block.equals(DOTTED_NAMES)) {
          return block + ".*";
        }
        String rest = key.substring(block.length() + 1);
        int dot = rest.indexOf('.');
        return block + ".*" + (dot < 0 ? "" : rest.substring(dot));
      }
    }
    return key;
  }

  /**
   * Compares this file with the default one: every key this file sets must exist there with the
   * same shape. A key under an unknown one is not reported again.
   *
   * @param file the file this layout is read from, named in the problems
   * @param schema the default file's layout, with its named blocks normalized
   * @param optional keys that exist without being in the default file
   * @param moved keys of older layouts, with what replaced them
   * @return the problems, in the order of the file
   */
  List<Problem> compareWith(
      Path file, ConfigFile schema, Set<String> optional, Map<String, String> moved) {
    List<Problem> problems = new ArrayList<>();
    List<String> reportedBlocks = new ArrayList<>();
    List<Map.Entry<String, Entry>> sorted = new ArrayList<>(entries.entrySet());
    sorted.sort(Comparator.comparingInt(e -> e.getValue().line()));
    for (Map.Entry<String, Entry> each : sorted) {
      String key = each.getKey();
      if (reportedBlocks.stream().anyMatch(block -> key.startsWith(block + "."))) {
        continue;
      }
      String normalized = normalize(key);
      int line = each.getValue().line();
      Entry expected = schema.entries.get(normalized);
      if (expected == null) {
        if (optional.contains(normalized)) {
          continue;
        }
        reportedBlocks.add(key);
        problems.add(new Problem(file, line, key, unknown(key, normalized, schema, moved)));
        continue;
      }
      Shape found = each.getValue().shape();
      // A block where a value belongs, or the reverse: Warp would read the key as absent. A list
      // for a value, or the reverse, is the setting's own to explain (status.motd takes both).
      boolean blockMismatch =
          found != expected.shape() && (found == Shape.BLOCK || expected.shape() == Shape.BLOCK);
      if (blockMismatch && !normalized.startsWith("bungeecord-channel.allow.")) {
        reportedBlocks.add(key);
        problems.add(
            new Problem(
                file,
                line,
                key,
                key + ": expected " + describe(expected.shape()) + ", found " + describe(found)));
      }
    }
    return problems;
  }

  private static String unknown(
      String key, String normalized, ConfigFile schema, Map<String, String> moved) {
    String replacement = moved.get(normalized);
    if (replacement != null) {
      return key + " is no longer a setting: " + replacement;
    }
    int dot = normalized.lastIndexOf('.');
    String parent = dot < 0 ? "" : normalized.substring(0, dot);
    String name = normalized.substring(dot + 1);
    List<String> siblings =
        schema.entries.keySet().stream()
            .filter(
                k ->
                    parent.equals(k.lastIndexOf('.') < 0 ? "" : k.substring(0, k.lastIndexOf('.'))))
            .map(k -> k.substring(k.lastIndexOf('.') + 1))
            .filter(k -> !"*".equals(k))
            .collect(Collectors.toList());
    String sibling = Suggestions.closest(name, siblings);
    String where = key.substring(0, Math.max(key.lastIndexOf('.'), 0));
    if (sibling != null) {
      return key
          + " is not a setting of Warp. Did you mean "
          + (where.isEmpty() ? "" : where + ".")
          + sibling
          + "?";
    }
    String elsewhere = elsewhere(name, schema);
    if (elsewhere != null) {
      String block =
          elsewhere.lastIndexOf('.') < 0
              ? null
              : elsewhere.substring(0, elsewhere.lastIndexOf('.'));
      return key
          + " is not a setting of Warp here. Did you mean "
          + elsewhere
          + (block == null ? ", at the top level" : ", in the " + block + " block")
          + "?";
    }
    return key
        + " is not a setting of Warp. A fresh warp.conf documents every setting: rename yours"
        + " and start Warp once to get one.";
  }

  /** The key of the default file whose last part is {@code name}, or close to it. */
  private static @Nullable String elsewhere(String name, ConfigFile schema) {
    String closest = null;
    for (String known : schema.entries.keySet()) {
      String last = known.substring(known.lastIndexOf('.') + 1);
      if (last.equals(name) && !known.contains("*")) {
        return known;
      }
      if (closest == null
          && !known.contains("*")
          && name.length() >= 6
          && Suggestions.closest(name, List.of(last)) != null) {
        closest = known;
      }
    }
    return closest;
  }

  private static String describe(Shape shape) {
    return switch (shape) {
      case BLOCK -> "a block { ... }";
      case LIST -> "a list [ ... ]";
      case VALUE -> "a value";
    };
  }
}
