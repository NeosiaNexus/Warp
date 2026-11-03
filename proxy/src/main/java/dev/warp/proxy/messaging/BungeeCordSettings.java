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
package dev.warp.proxy.messaging;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * How the proxy answers the BungeeCord channel: the {@code bungeecord-channel} block of the
 * configuration.
 *
 * <p>Each server may use the sub-channels of the rule that matches its name best: the rule named
 * after it, else the rule with the longest prefix pattern ({@code game-*}) that matches it, else
 * the rule {@code *}. A server no rule matches may use none. Patterns only end with {@code *}, so
 * that two patterns of the same length never match the same name: which rule applies never depends
 * on their order.
 *
 * @param enabled whether the proxy answers the channel at all
 * @param allow the sub-channels each rule allows, by server name or pattern (lowercase)
 * @param forwardHold how long a {@code Forward} to a server nobody plays on waits for a player to
 *     carry it; zero drops it at once
 */
public record BungeeCordSettings(
    boolean enabled, Map<String, Set<BungeeCordSubchannel>> allow, Duration forwardHold) {

  /** The rule that matches every server. */
  public static final String EVERY_SERVER = "*";

  /** How long a {@code Forward} waits by default for a player to carry it. */
  public static final Duration DEFAULT_FORWARD_HOLD = Duration.ofSeconds(30);

  /** Enabled, every server may use every sub-channel, as on BungeeCord and Velocity. */
  public static final BungeeCordSettings DEFAULTS =
      new BungeeCordSettings(
          true,
          Map.of(EVERY_SERVER, EnumSet.allOf(BungeeCordSubchannel.class)),
          DEFAULT_FORWARD_HOLD);

  /** Not answered: the channel passes through as any channel nobody speaks. */
  public static final BungeeCordSettings DISABLED =
      new BungeeCordSettings(false, Map.of(), Duration.ZERO);

  /**
   * Checks and copies the rules.
   *
   * @param enabled whether the proxy answers the channel at all
   * @param allow the sub-channels each rule allows, by server name or pattern
   * @param forwardHold how long a {@code Forward} waits for a player to carry it
   * @throws IllegalArgumentException if a rule's name is not a server name or a pattern, or the
   *     hold is negative
   */
  public BungeeCordSettings {
    Map<String, Set<BungeeCordSubchannel>> copy = new LinkedHashMap<>();
    for (var rule : allow.entrySet()) {
      String key = rule.getKey().toLowerCase(Locale.ROOT);
      int star = key.indexOf('*');
      if (key.isEmpty() || (star >= 0 && star != key.length() - 1)) {
        throw new IllegalArgumentException(
            "'"
                + rule.getKey()
                + "' is neither a server name nor a pattern: a pattern is a prefix followed by *,"
                + " such as game-*, or * alone");
      }
      Set<BungeeCordSubchannel> subchannels = EnumSet.noneOf(BungeeCordSubchannel.class);
      subchannels.addAll(rule.getValue());
      copy.put(key, Set.copyOf(subchannels));
    }
    allow = Map.copyOf(copy);
    if (forwardHold.isNegative()) {
      throw new IllegalArgumentException("The forward hold cannot be negative: " + forwardHold);
    }
  }

  /**
   * Returns the sub-channels a server may use.
   *
   * @param server the server's name, lowercase
   * @return the sub-channels, empty if none (or the channel is disabled)
   */
  public Set<BungeeCordSubchannel> allowed(String server) {
    if (!enabled) {
      return Set.of();
    }
    Set<BungeeCordSubchannel> exact = allow.get(server);
    if (exact != null) {
      return exact;
    }
    Set<BungeeCordSubchannel> best = null;
    int bestLength = -1;
    for (var rule : allow.entrySet()) {
      String key = rule.getKey();
      if (key.endsWith(EVERY_SERVER)) {
        String prefix = key.substring(0, key.length() - 1);
        if (prefix.length() > bestLength && server.startsWith(prefix)) {
          best = rule.getValue();
          bestLength = prefix.length();
        }
      }
    }
    return best != null ? best : Set.of();
  }

  /**
   * Reads a rule's list: sub-channel names in any case, {@code *} for all of them, and {@code
   * !Name} to take one away from those listed before it.
   *
   * @param entries the list, in order
   * @return the sub-channels it allows
   * @throws IllegalArgumentException if an entry names no sub-channel (the message lists them)
   */
  public static Set<BungeeCordSubchannel> parseRule(List<String> entries) {
    Set<BungeeCordSubchannel> allowed = EnumSet.noneOf(BungeeCordSubchannel.class);
    for (String raw : entries) {
      String entry = raw.strip();
      boolean remove = entry.startsWith("!");
      String name = remove ? entry.substring(1).strip() : entry;
      Set<BungeeCordSubchannel> named;
      if (name.equals(EVERY_SERVER)) {
        named = EnumSet.allOf(BungeeCordSubchannel.class);
      } else {
        BungeeCordSubchannel subchannel = BungeeCordSubchannel.fromConfigName(name);
        if (subchannel == null) {
          throw new IllegalArgumentException(
              "'"
                  + raw
                  + "' is not a BungeeCord sub-channel. Use * for all of them, !Name to take one"
                  + " away, or one of: "
                  + Stream.of(BungeeCordSubchannel.values())
                      .map(BungeeCordSubchannel::wireName)
                      .collect(Collectors.joining(", ")));
        }
        named = EnumSet.of(subchannel);
      }
      if (remove) {
        allowed.removeAll(named);
      } else {
        allowed.addAll(named);
      }
    }
    return allowed;
  }

  /**
   * Describes the rules for the startup log, most specific first.
   *
   * @return e.g. {@code lobby: all, game-*: Connect, GetServers, *: none}
   */
  public String describe() {
    if (!enabled) {
      return "disabled";
    }
    List<String> keys = new ArrayList<>(allow.keySet());
    keys.sort(
        Comparator.comparing((String key) -> key.endsWith(EVERY_SERVER))
            .thenComparing(Comparator.comparingInt(String::length).reversed())
            .thenComparing(Comparator.naturalOrder()));
    List<String> rules = new ArrayList<>();
    for (String key : keys) {
      Set<BungeeCordSubchannel> subchannels = allow.getOrDefault(key, Set.of());
      String list;
      if (subchannels.size() == BungeeCordSubchannel.values().length) {
        list = "all";
      } else if (subchannels.isEmpty()) {
        list = "none";
      } else {
        list =
            subchannels.stream()
                .sorted()
                .map(BungeeCordSubchannel::wireName)
                .collect(Collectors.joining(", "));
      }
      rules.add(key + ": " + list);
    }
    return String.join("; ", rules) + "; forward-hold " + forwardHold.toMillis() + "ms";
  }
}
