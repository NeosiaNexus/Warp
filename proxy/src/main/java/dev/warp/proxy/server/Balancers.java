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
package dev.warp.proxy.server;

import dev.warp.api.server.Balancer;
import dev.warp.api.server.RegisteredServer;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

/**
 * The built-in balancing strategies, by the names {@code warp.conf} takes.
 *
 * <p>Every strategy is deterministic given its inputs, except {@code random} and {@code weighted},
 * and breaks ties by the order of the group, so that an operator can predict where a player goes.
 */
public final class Balancers {

  /** Each server in turn. */
  public static final String ROUND_ROBIN = "round-robin";

  /** The server with the fewest players. */
  public static final String LEAST_CONNECTIONS = "least-connections";

  /** The server whose last ping came back fastest. */
  public static final String LOWEST_LATENCY = "lowest-latency";

  /** Any server, each equally likely. */
  public static final String RANDOM = "random";

  /** Any server, each as likely as its weight. */
  public static final String WEIGHTED = "weighted";

  /** The first server with room, up to a capacity. */
  public static final String FILL = "fill";

  /** The strategy names, in the order they are documented. */
  public static final List<String> NAMES =
      List.of(ROUND_ROBIN, LEAST_CONNECTIONS, LOWEST_LATENCY, RANDOM, WEIGHTED, FILL);

  private static final Set<String> NAME_SET = Set.copyOf(NAMES);

  private Balancers() {}

  /**
   * Returns whether a name is a built-in strategy.
   *
   * @param name the name, in any case
   * @return {@code true} if {@link #of} or {@link #fill} knows it
   */
  public static boolean isStrategy(String name) {
    return NAME_SET.contains(name.toLowerCase(Locale.ROOT));
  }

  /**
   * Returns a fresh balancer for a strategy that needs no setting.
   *
   * @param name the strategy, in any case
   * @return the balancer, or {@code null} if the name is unknown or is {@link #FILL}
   */
  public static @Nullable Balancer of(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case ROUND_ROBIN -> roundRobin();
      case LEAST_CONNECTIONS -> leastConnections();
      case LOWEST_LATENCY -> lowestLatency();
      case RANDOM -> random();
      case WEIGHTED -> weighted();
      default -> null;
    };
  }

  /**
   * Returns a balancer that picks each server in turn, in the order of the group. The cursor
   * advances once per pick, whichever servers are available at the time.
   *
   * @return a new balancer
   */
  public static Balancer roundRobin() {
    AtomicInteger cursor = new AtomicInteger();
    return candidates -> {
      int index = Math.floorMod(cursor.getAndIncrement(), candidates.size());
      return Optional.of(candidates.get(index).server());
    };
  }

  /**
   * Returns a balancer that picks the server with the fewest players, the first of the group among
   * equals.
   *
   * @return a new balancer
   */
  public static Balancer leastConnections() {
    return candidates -> min(candidates, Comparator.comparingInt(Balancer.Candidate::connections));
  }

  /**
   * Returns a balancer that picks the server whose last ping came back fastest. Servers that never
   * answered a ping come last; among equals, the one with fewer players, then the first of the
   * group.
   *
   * @return a new balancer
   */
  public static Balancer lowestLatency() {
    Comparator<Balancer.Candidate> byLatency =
        Comparator.comparingLong(
            (Balancer.Candidate c) -> c.latency().map(Duration::toNanos).orElse(Long.MAX_VALUE));
    return candidates ->
        min(candidates, byLatency.thenComparingInt(Balancer.Candidate::connections));
  }

  /**
   * Returns a balancer that picks any server, each equally likely.
   *
   * @return a new balancer
   */
  public static Balancer random() {
    return candidates ->
        Optional.of(
            candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).server());
  }

  /**
   * Returns a balancer that picks any server, each as likely as its weight relative to the others.
   *
   * @return a new balancer
   */
  public static Balancer weighted() {
    return candidates -> {
      long total = 0;
      for (Balancer.Candidate candidate : candidates) {
        total += candidate.weight();
      }
      long roll = ThreadLocalRandom.current().nextLong(total);
      for (Balancer.Candidate candidate : candidates) {
        roll -= candidate.weight();
        if (roll < 0) {
          return Optional.of(candidate.server());
        }
      }
      return Optional.of(candidates.getLast().server());
    };
  }

  /**
   * Returns a balancer that fills the servers one after the other: the first server of the group
   * with fewer than {@code capacity} players, or the least filled one when every server is at
   * capacity.
   *
   * @param capacity the players a server takes before the next one is filled, at least 1
   * @return a new balancer
   * @throws IllegalArgumentException if {@code capacity} is less than 1
   */
  public static Balancer fill(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("A fill capacity is at least 1, got " + capacity);
    }
    return candidates -> {
      for (Balancer.Candidate candidate : candidates) {
        if (candidate.connections() < capacity) {
          return Optional.of(candidate.server());
        }
      }
      return min(candidates, Comparator.comparingInt(Balancer.Candidate::connections));
    };
  }

  private static Optional<RegisteredServer> min(
      List<Balancer.Candidate> candidates, Comparator<Balancer.Candidate> order) {
    Balancer.Candidate best = candidates.getFirst();
    for (int i = 1; i < candidates.size(); i++) {
      Balancer.Candidate candidate = candidates.get(i);
      if (order.compare(candidate, best) < 0) {
        best = candidate;
      }
    }
    return Optional.of(best.server());
  }
}
