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

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Reads and writes the durations of {@code warp.conf}, in one format everywhere: HOCON's, a whole
 * number and a unit, such as {@code 200ms}, {@code 30s}, {@code 5m}, {@code 1h} or {@code 30d}.
 *
 * <p>A unit is required: a bare number is refused, except {@code 0}, which is zero in any unit.
 * HOCON reads a bare number as milliseconds, Velocity writes its timeouts in milliseconds, and Gate
 * took one for seconds and scaled it twice (minekube/gate#1342): a value whose unit the reader has
 * to guess is a value someone gets wrong.
 */
public final class Durations {

  /**
   * A whole number and a unit, as HOCON writes durations; long names (HOCON's {@code 30 seconds})
   * included. Nanoseconds and microseconds are left out: nothing in Warp is that precise.
   */
  private static final Pattern DURATION =
      Pattern.compile(
          "(\\d{1,15})\\s*(ms|milli|millis|millisecond|milliseconds|s|second|seconds"
              + "|m|minute|minutes|h|hour|hours|d|day|days)");

  private static final Pattern BARE_NUMBER = Pattern.compile("\\d{1,15}");

  private Durations() {}

  /**
   * Reads a duration; an absent key takes {@code fallback}.
   *
   * @param node the node of the duration, possibly virtual
   * @param key the dotted path of the key, for error messages
   * @param fallback the value of an absent key
   * @return the duration
   * @throws ConfigurationException if the value is not a duration with a unit
   */
  public static Duration parse(ConfigurationNode node, String key, Duration fallback)
      throws ConfigurationException {
    if (node.virtual()) {
      return fallback;
    }
    String value = node.isMap() || node.isList() ? null : node.getString();
    if (value == null) {
      throw new ConfigurationException(key + " must be a duration such as 200ms, 30s or 5m");
    }
    return parse(value, key);
  }

  /**
   * Parses a duration.
   *
   * @param value the text, such as {@code 30s}
   * @param key the dotted path of the key, for error messages
   * @return the duration
   * @throws ConfigurationException if the value is not a duration with a unit
   */
  public static Duration parse(String value, String key) throws ConfigurationException {
    String text = value.strip().toLowerCase(Locale.ROOT);
    if (BARE_NUMBER.matcher(text).matches()) {
      if (Long.parseLong(text) == 0) {
        return Duration.ZERO;
      }
      throw new ConfigurationException(
          key
              + " needs a unit: write "
              + text
              + "ms for milliseconds, or "
              + text
              + "s for seconds (durations take ms, s, m, h or d)");
    }
    Matcher matcher = DURATION.matcher(text);
    if (!matcher.matches()) {
      throw new ConfigurationException(
          key + " must be a duration such as 200ms, 30s, 5m, 1h or 30d, got '" + value + "'");
    }
    long amount = Long.parseLong(matcher.group(1));
    try {
      return switch (matcher.group(2)) {
        case "ms", "milli", "millis", "millisecond", "milliseconds" -> Duration.ofMillis(amount);
        case "s", "second", "seconds" -> Duration.ofSeconds(amount);
        case "m", "minute", "minutes" -> Duration.ofMinutes(amount);
        case "h", "hour", "hours" -> Duration.ofHours(amount);
        default -> Duration.ofDays(amount);
      };
    } catch (ArithmeticException e) {
      throw new ConfigurationException(key + " is too large: '" + value + "'", e);
    }
  }

  /**
   * Formats a duration the way the configuration writes it: the largest unit that divides it, such
   * as {@code 100ms}, {@code 5s}, {@code 30m}, {@code 2h} or {@code 30d}; zero is {@code 0s}.
   *
   * @param duration the duration
   * @return the duration, as the configuration writes it
   */
  public static String format(Duration duration) {
    long millis = duration.toMillis();
    if (millis == 0) {
      return "0s";
    }
    if (millis % 86_400_000 == 0) {
      return millis / 86_400_000 + "d";
    }
    if (millis % 3_600_000 == 0) {
      return millis / 3_600_000 + "h";
    }
    if (millis % 60_000 == 0) {
      return millis / 60_000 + "m";
    }
    if (millis % 1_000 == 0) {
      return millis / 1_000 + "s";
    }
    return millis + "ms";
  }
}
