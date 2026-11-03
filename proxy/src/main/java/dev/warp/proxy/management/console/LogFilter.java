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
package dev.warp.proxy.management.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;

/**
 * Which log lines a client of the stream receives: a least level, tags to keep or leave out, and a
 * text the message must hold. Filters apply to the replay as to the live lines.
 *
 * <p>Tags are those of the console ({@code proxy}, {@code antibot}, {@code plugin:primemc}); a
 * trailing {@code *} matches a prefix ({@code plugin:*}), a leading {@code -} leaves a tag out
 * ({@code -netty}). With no tag to keep, every tag not left out is kept.
 *
 * @param level the least severe level kept
 * @param include the tags kept, or empty for all
 * @param exclude the tags left out
 * @param grep a text the message must contain, ignoring case, or {@code null}
 */
record LogFilter(Level level, List<String> include, List<String> exclude, @Nullable String grep) {

  /** Every line of level {@code INFO} or more severe. */
  static final LogFilter DEFAULT = new LogFilter(Level.INFO, List.of(), List.of(), null);

  /**
   * Copies the lists, lowering the text to find.
   *
   * @param level the least severe level kept
   * @param include the tags kept
   * @param exclude the tags left out
   * @param grep the text to find, or {@code null}
   */
  LogFilter {
    include = List.copyOf(include);
    exclude = List.copyOf(exclude);
    grep = grep == null || grep.isEmpty() ? null : grep.toLowerCase(Locale.ROOT);
  }

  /**
   * Reads a filter from query parameters: {@code level}, {@code tags} (comma-separated, repeatable)
   * and {@code grep}.
   *
   * @param parameters the query's parameters
   * @return the filter
   * @throws IllegalArgumentException if the level is unknown
   */
  static LogFilter parse(Map<String, List<String>> parameters) {
    Level level = Level.INFO;
    String levelName = last(parameters, "level");
    if (levelName != null && !levelName.isBlank()) {
      level = Level.getLevel(levelName.strip().toUpperCase(Locale.ROOT));
      if (level == null) {
        throw new IllegalArgumentException(
            "unknown level " + levelName + "; use trace, debug, info, warn or error");
      }
    }
    List<String> include = new ArrayList<>();
    List<String> exclude = new ArrayList<>();
    for (String value : parameters.getOrDefault("tags", List.of())) {
      for (String tag : value.split(",", -1)) {
        String clean = tag.strip().toLowerCase(Locale.ROOT);
        if (clean.startsWith("-") && clean.length() > 1) {
          exclude.add(clean.substring(1));
        } else if (!clean.isEmpty() && !clean.equals("-")) {
          include.add(clean);
        }
      }
    }
    return new LogFilter(level, include, exclude, last(parameters, "grep"));
  }

  /**
   * Returns whether a record passes.
   *
   * @param record the record
   * @return {@code true} if the client receives it
   */
  boolean test(ConsoleRecord record) {
    if (!record.level().isMoreSpecificThan(level)) {
      return false;
    }
    String tag = record.tag();
    for (String pattern : exclude) {
      if (matches(pattern, tag)) {
        return false;
      }
    }
    if (!include.isEmpty() && include.stream().noneMatch(pattern -> matches(pattern, tag))) {
      return false;
    }
    return grep == null || record.message().toLowerCase(Locale.ROOT).contains(grep);
  }

  /**
   * Describes the filter as the {@code hello} event and the audit log show it.
   *
   * @return its JSON
   */
  JsonObject json() {
    JsonObject json = new JsonObject();
    json.addProperty("level", level.name().toLowerCase(Locale.ROOT));
    JsonArray tags = new JsonArray();
    include.forEach(tags::add);
    exclude.forEach(tag -> tags.add("-" + tag));
    json.add("tags", tags);
    json.addProperty("grep", grep);
    return json;
  }

  private static boolean matches(String pattern, String tag) {
    return pattern.endsWith("*")
        ? tag.startsWith(pattern.substring(0, pattern.length() - 1))
        : tag.equals(pattern);
  }

  private static @Nullable String last(Map<String, List<String>> parameters, String name) {
    List<String> values = parameters.get(name);
    return values == null || values.isEmpty() ? null : values.getLast();
  }
}
