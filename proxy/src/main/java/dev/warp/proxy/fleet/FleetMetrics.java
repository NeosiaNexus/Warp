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
package dev.warp.proxy.fleet;

import dev.warp.api.fleet.NodeState;
import dev.warp.api.metrics.Buckets;
import dev.warp.api.metrics.Counter;
import dev.warp.api.metrics.Gauge;
import dev.warp.api.metrics.Histogram;
import dev.warp.api.metrics.Metrics;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** The fleet's metrics, {@code warp_fleet_*}: see {@code docs/metrics.md}. */
public final class FleetMetrics {

  /** What a claim ended in. */
  public enum ClaimOutcome {
    CLAIMED,
    OWNED_KICKED,
    OWNED_REFUSED,
    TIMEOUT,
    STORE_DOWN
  }

  /** What a reconcile corrected. */
  public enum Correction {
    NODE_ADDED,
    NODE_REMOVED,
    PLAYER_ADDED,
    PLAYER_REMOVED,
    PLAYER_MOVED,
    OWN_REPLAYED,
    OWN_RELEASED,
    OWN_CONFLICT
  }

  private final Gauge.Child storeUp;
  private final Gauge.Child[] nodes;
  private final Gauge.Child remotePlayers;
  private final Counter.Child[] claims;
  private final Counter.Child busIn;
  private final Counter.Child busOut;
  private final Counter.Child reapedPlayers;
  private final Counter.Child[] corrections;
  private final Counter storeCommands;
  private final Histogram storeLatency;
  private final ConcurrentMap<String, Counter.Child> commandOk = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter.Child> commandFailed = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Histogram.Child> latencies = new ConcurrentHashMap<>();

  /**
   * Registers the metrics.
   *
   * @param metrics the registry
   */
  public FleetMetrics(Metrics metrics) {
    this.storeUp =
        metrics
            .gauge(
                "warp_fleet_store_up",
                "1 while the fleet store answers (always 1 on a single node), 0 while it is down.")
            .labels();
    Gauge nodeGauge =
        metrics.gauge(
            "warp_fleet_nodes", "Proxies of the fleet, this one included, by state.", "state");
    this.nodes = new Gauge.Child[NodeState.values().length];
    for (NodeState state : NodeState.values()) {
      nodes[state.ordinal()] = nodeGauge.labels(lower(state));
    }
    this.remotePlayers =
        metrics
            .gauge("warp_fleet_players_remote", "Players on the other proxies of the fleet.")
            .labels();
    Counter claimCounter =
        metrics.counter(
            "warp_fleet_claims_total", "Logins settled against the fleet, by outcome.", "outcome");
    this.claims = new Counter.Child[ClaimOutcome.values().length];
    for (ClaimOutcome outcome : ClaimOutcome.values()) {
      claims[outcome.ordinal()] = claimCounter.labels(lower(outcome));
    }
    Counter bus =
        metrics.counter(
            "warp_fleet_bus_messages_total",
            "Messages on the fleet bus, notices and requests included, by direction.",
            "direction");
    this.busIn = bus.labels("in");
    this.busOut = bus.labels("out");
    this.reapedPlayers =
        metrics
            .counter(
                "warp_fleet_reaped_players_total",
                "Player records this proxy removed from the store for a dead proxy.")
            .labels();
    Counter correctionCounter =
        metrics.counter(
            "warp_fleet_reconcile_corrections_total",
            "Differences between this proxy's view and the store that a reconcile repaired.",
            "kind");
    this.corrections = new Counter.Child[Correction.values().length];
    for (Correction kind : Correction.values()) {
      corrections[kind.ordinal()] = correctionCounter.labels(lower(kind));
    }
    this.storeCommands =
        metrics.counter(
            "warp_fleet_store_commands_total",
            "Calls to the fleet store, by command and outcome.",
            "command",
            "outcome");
    this.storeLatency =
        metrics.histogram(
            "warp_fleet_store_latency_seconds",
            "How long the fleet store takes to answer, by command.",
            Buckets.NETWORK,
            "command");
  }

  void storeUp(boolean up) {
    storeUp.set(up ? 1 : 0);
  }

  void nodes(int ready, int draining) {
    nodes[NodeState.READY.ordinal()].set(ready);
    nodes[NodeState.DRAINING.ordinal()].set(draining);
    nodes[NodeState.DOWN.ordinal()].set(0);
  }

  void remotePlayers(int count) {
    remotePlayers.set(count);
  }

  void claim(ClaimOutcome outcome) {
    claims[outcome.ordinal()].inc();
  }

  void busIn() {
    busIn.inc();
  }

  void busOut() {
    busOut.inc();
  }

  void reaped(int players) {
    reapedPlayers.add(players);
  }

  void corrected(Correction kind) {
    corrections[kind.ordinal()].inc();
  }

  void command(String command, boolean ok, long nanos) {
    (ok ? commandOk : commandFailed)
        .computeIfAbsent(command, c -> storeCommands.labels(c, ok ? "ok" : "failed"))
        .inc();
    latencies.computeIfAbsent(command, storeLatency::labels).observeNanos(nanos);
  }

  private static String lower(Enum<?> constant) {
    return constant.name().toLowerCase(Locale.ROOT);
  }
}
