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
package dev.warp.api.antibot;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * An attack the detector measured: what set it off and what it amounted to, as far as it went.
 *
 * @param startedAt when the detector entered {@link AttackLevel#ATTACK}
 * @param endedAt when it went back to {@link AttackLevel#NORMAL}, or {@code null} while the attack
 *     goes on
 * @param trigger the signal that crossed its threshold first
 * @param triggerRate that signal's value when it did, per second ({@link
 *     Signal#PENDING_CONNECTIONS} is a count)
 * @param threshold the threshold it crossed: its floor, or its baseline times the factor
 * @param peaks the highest value of each signal in one second, since the start
 * @param totals how many events of each signal since the start; {@link Signal#PENDING_CONNECTIONS},
 *     a gauge, has none
 * @param topSources the networks that sent the most unknown logins, failed authentications and
 *     refused connections, most first
 */
public record AttackReport(
    Instant startedAt,
    @Nullable Instant endedAt,
    Signal trigger,
    long triggerRate,
    long threshold,
    Map<Signal, Long> peaks,
    Map<Signal, Long> totals,
    List<Source> topSources) {

  /**
   * Checks the values and makes the collections unmodifiable copies.
   *
   * @throws NullPointerException if a value but {@code endedAt} is {@code null}
   * @throws IllegalArgumentException if the attack ends before it starts
   */
  public AttackReport {
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(trigger, "trigger");
    if (endedAt != null && endedAt.isBefore(startedAt)) {
      throw new IllegalArgumentException("endedAt " + endedAt + " is before " + startedAt);
    }
    peaks = copy(peaks);
    totals = copy(totals);
    topSources = List.copyOf(topSources);
  }

  /**
   * Returns how long the attack lasted, or has lasted so far.
   *
   * @param now the time to measure an ongoing attack to
   * @return the duration
   */
  public Duration duration(Instant now) {
    return Duration.between(startedAt, endedAt != null ? endedAt : now);
  }

  private static Map<Signal, Long> copy(Map<Signal, Long> values) {
    EnumMap<Signal, Long> copy = new EnumMap<>(Signal.class);
    copy.putAll(values);
    return Collections.unmodifiableMap(copy);
  }

  /**
   * A network among the sources of an attack.
   *
   * @param network the network, in CIDR notation: an IPv4 /24 or an IPv6 /48 by default
   * @param events how many hostile events came from it during the attack
   */
  public record Source(String network, long events) {

    /**
     * Checks the values.
     *
     * @throws NullPointerException if the network is {@code null}
     * @throws IllegalArgumentException if the count is negative
     */
    public Source {
      Objects.requireNonNull(network, "network");
      if (events < 0) {
        throw new IllegalArgumentException("events must not be negative, got " + events);
      }
    }
  }
}
