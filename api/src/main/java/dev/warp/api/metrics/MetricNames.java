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
package dev.warp.api.metrics;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules metric and label names follow, checked when a metric is registered.
 *
 * <p>Names are Prometheus names without colons (colons are for recording rules): letters, digits
 * and underscores, not starting with a digit. Units are base units in the name's suffix ({@code
 * _seconds}, {@code _bytes}), never {@code _ms} or {@code _kb}. Counters end with {@code _total}
 * and nothing else does. Plugin names start with the plugin's prefix, and never with {@code warp_},
 * {@code jvm_} or {@code process_}.
 *
 * <p>Label names follow the same syntax, never start with {@code __}, and are never one of the
 * {@link #RESERVED_LABELS}: labels that take a value per player or per address make a series per
 * player, which no monitoring system survives.
 */
public final class MetricNames {

  /** The label names a metric cannot have: each would make a series per player or address. */
  public static final Set<String> RESERVED_LABELS =
      Set.of("player", "uuid", "name", "address", "host", "ip", "virtual_host", "le", "quantile");

  /** The prefixes a plugin's names cannot start with: the proxy's own metrics. */
  public static final List<String> RESERVED_PREFIXES = List.of("warp_", "jvm_", "process_");

  /** The suffix every counter has, and only counters. */
  public static final String COUNTER_SUFFIX = "_total";

  private static final Pattern NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

  private static final List<String> UNIT_SUFFIXES_REFUSED =
      List.of(
          "_ms",
          "_millis",
          "_milliseconds",
          "_micros",
          "_microseconds",
          "_nanos",
          "_nanoseconds",
          "_minutes",
          "_hours",
          "_kb",
          "_kib",
          "_mb",
          "_mib",
          "_gb",
          "_gib",
          "_percent",
          "_pct");

  private MetricNames() {}

  /**
   * Checks a metric name.
   *
   * @param name the exposed name, prefix included
   * @param type what the metric is: a counter must end with {@code _total}, nothing else may
   * @throws IllegalArgumentException if the name breaks a rule; the message says which
   */
  public static void validate(String name, MetricType type) {
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "metric name '"
              + name
              + "' must be letters, digits and underscores, not starting with a digit");
    }
    boolean total = name.endsWith(COUNTER_SUFFIX);
    if (type == MetricType.COUNTER && !total) {
      throw new IllegalArgumentException("counter '" + name + "' must end with _total");
    }
    if (type != MetricType.COUNTER && total) {
      throw new IllegalArgumentException(
          "'" + name + "' ends with _total, which only a counter may");
    }
    String base = total ? name.substring(0, name.length() - COUNTER_SUFFIX.length()) : name;
    for (String suffix : UNIT_SUFFIXES_REFUSED) {
      if (base.endsWith(suffix)) {
        throw new IllegalArgumentException(
            "'"
                + name
                + "' uses the unit "
                + suffix
                + ": use base units (_seconds, _bytes), and scale the value");
      }
    }
  }

  /**
   * Checks a label name.
   *
   * @param label the label name
   * @throws IllegalArgumentException if the name is invalid, starts with {@code __} or is reserved
   */
  public static void validateLabel(String label) {
    if (!NAME.matcher(label).matches() || label.startsWith("__")) {
      throw new IllegalArgumentException(
          "label name '"
              + label
              + "' must be letters, digits and underscores, not starting with a digit or __");
    }
    if (RESERVED_LABELS.contains(label)) {
      throw new IllegalArgumentException(
          "label '"
              + label
              + "' is reserved: a value per player or address makes a series per player");
    }
  }

  /**
   * Returns whether a name starts with a prefix only the proxy may use.
   *
   * @param name the exposed name
   * @return {@code true} for {@code warp_}, {@code jvm_} and {@code process_} names
   */
  public static boolean hasReservedPrefix(String name) {
    for (String prefix : RESERVED_PREFIXES) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the prefix of a plugin's metric names: its id in lower case, every character that is
   * not a letter, digit or underscore replaced with an underscore, and a trailing underscore.
   * {@code my-plugin} registers {@code lookups_total} as {@code my_plugin_lookups_total}.
   *
   * @param pluginId the plugin's id
   * @return the prefix, ending with an underscore
   */
  public static String pluginPrefix(String pluginId) {
    StringBuilder prefix = new StringBuilder(pluginId.length() + 1);
    for (char c : pluginId.toLowerCase(Locale.ROOT).toCharArray()) {
      boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
      prefix.append(allowed ? c : '_');
    }
    if (prefix.isEmpty() || Character.isDigit(prefix.charAt(0))) {
      prefix.insert(0, "plugin_");
    }
    return prefix.append('_').toString();
  }
}
