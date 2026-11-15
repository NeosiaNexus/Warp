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
package dev.warp.proxy.metrics;

import dev.warp.api.antibot.Signal;
import dev.warp.api.antibot.VerificationFailure;
import dev.warp.api.metrics.Collector;
import dev.warp.api.metrics.MetricType;
import dev.warp.api.metrics.SampleVisitor;
import dev.warp.proxy.antibot.AntiBotStatus;
import dev.warp.proxy.limit.Refusal;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * The anti-bot's and the limits' metrics, from an {@link AntiBotStatus} snapshot taken at scrape
 * time: the names {@code GET /metrics} exposed before the registry existed, unchanged.
 */
public final class AntiBotCollector implements Collector {

  private static final List<String> NONE = List.of();
  private static final List<String> SIGNAL = List.of("signal");
  private static final List<String> REASON = List.of("reason");
  private static final List<String> RESULT = List.of("result");

  private final Supplier<@Nullable AntiBotStatus> status;

  /**
   * Creates the collector.
   *
   * @param status takes a snapshot; {@code null} while the anti-bot is not started, which writes
   *     nothing
   */
  public AntiBotCollector(Supplier<@Nullable AntiBotStatus> status) {
    this.status = status;
  }

  @Override
  public void collect(SampleVisitor out) {
    AntiBotStatus status = this.status.get();
    if (status == null) {
      return;
    }
    gauge(out, "warp_antibot_level", "Attack level: 0 normal, 1 attack.", status.level().ordinal());
    gauge(out, "warp_antibot_lockdown", "1 while lockdown is on.", status.lockdown() ? 1 : 0);
    gauge(
        out,
        "warp_antibot_verified_identities",
        "Identities the anti-bot trusts.",
        status.verified());
    gauge(
        out,
        "warp_antibot_verified_capacity",
        "The most identities the anti-bot remembers.",
        status.verifiedCapacity());

    out.family(
        "warp_antibot_signal_per_second", MetricType.GAUGE, "Each signal over the last second.");
    for (Signal signal : Signal.values()) {
      out.sample(
          "warp_antibot_signal_per_second",
          SIGNAL,
          List.of(lower(signal)),
          status.signals().lastSecond().getOrDefault(signal, 0L));
    }
    out.family(
        "warp_antibot_signal_baseline",
        MetricType.GAUGE,
        "Each signal's learned baseline, per second.");
    for (Signal signal : Signal.values()) {
      out.sample(
          "warp_antibot_signal_baseline",
          SIGNAL,
          List.of(lower(signal)),
          status.signals().baselines().getOrDefault(signal, 0.0));
    }

    gauge(
        out,
        "warp_connections_pending",
        "Connections not logged in yet.",
        status.pendingConnections());
    gauge(
        out,
        "warp_connections_pending_max",
        "The cap on connections not logged in; 0 for none.",
        status.maxPendingConnections());

    out.family(
        "warp_connections_refused_total",
        MetricType.COUNTER,
        "Connections refused or closed before login, by reason.");
    for (Refusal reason : Refusal.values()) {
      out.sample(
          "warp_connections_refused_total",
          REASON,
          List.of(reason.label()),
          status.refusals().getOrDefault(reason, 0L));
    }

    gauge(
        out,
        "warp_auth_in_flight",
        "Online-mode authentications running.",
        status.authenticationsInFlight());
    gauge(
        out,
        "warp_auth_lane_waiting",
        "Online logins waiting in line for an authentication.",
        status.authenticationQueue());
    out.family(
        "warp_auth_lane_wait_seconds",
        MetricType.SUMMARY,
        "How long logins granted an authentication from the line waited.");
    out.sample("warp_auth_lane_wait_seconds_sum", NONE, NONE, status.authenticationWaitSeconds());
    out.sample("warp_auth_lane_wait_seconds_count", NONE, NONE, status.authenticationsQueued());
    if (status.blocklisted() >= 0) {
      gauge(out, "warp_antibot_blocklisted", "Addresses on the blocklist.", status.blocklisted());
    }

    AntiBotStatus.Verifications verifications = status.verifications();
    gauge(
        out,
        "warp_antibot_verifying",
        "Verifications of unknown identities running.",
        verifications.running());
    gauge(
        out,
        "warp_antibot_queue_length",
        "Logins waiting in line for a verification.",
        verifications.waiting());
    out.family(
        "warp_antibot_queue_wait_seconds",
        MetricType.SUMMARY,
        "How long logins granted a verification from the line waited.");
    out.sample("warp_antibot_queue_wait_seconds_sum", NONE, NONE, verifications.waitedSeconds());
    out.sample(
        "warp_antibot_queue_wait_seconds_count", NONE, NONE, verifications.grantedFromLine());
    out.family(
        "warp_antibot_verifications_total",
        MetricType.COUNTER,
        "Verifications of unknown identities by result: passed, escalated (passed, not trusted),"
            + " busy (no room), or failed by reason.");
    result(out, "warp_antibot_verifications_total", "passed", verifications.passed());
    result(out, "warp_antibot_verifications_total", "escalated", verifications.escalated());
    result(out, "warp_antibot_verifications_total", "busy", verifications.turnedAway());
    for (VerificationFailure reason : VerificationFailure.values()) {
      result(
          out,
          "warp_antibot_verifications_total",
          "failed_" + lower(reason),
          verifications.failed().getOrDefault(reason, 0L));
    }
    out.family(
        "warp_antibot_tokens_total",
        MetricType.COUNTER,
        "Verification tokens issued to transferred clients, and accepted or rejected on return.");
    result(out, "warp_antibot_tokens_total", "issued", verifications.tokensIssued());
    result(out, "warp_antibot_tokens_total", "accepted", verifications.tokensAccepted());
    result(out, "warp_antibot_tokens_total", "rejected", verifications.tokensRejected());

    AntiBotStatus.World world = status.world();
    gauge(
        out,
        "warp_antibot_world_players",
        "Players in the virtual world right now: being checked, held for a server, or held by a"
            + " plugin.",
        world.holding());
    out.family(
        "warp_antibot_world_checks_total",
        MetricType.COUNTER,
        "World checks by result: passed, retried (a physics mismatch given its second fall),"
            + " mismatched (off the gravity after the retry too: let in, untrusted), or failed.");
    result(out, "warp_antibot_world_checks_total", "passed", world.checksPassed());
    result(out, "warp_antibot_world_checks_total", "retried", world.checksRetried());
    result(out, "warp_antibot_world_checks_total", "mismatched", world.checksMismatched());
    result(out, "warp_antibot_world_checks_total", "failed", world.checksFailed());
    out.family(
        "warp_antibot_holds_total",
        MetricType.COUNTER,
        "Players held in the world for want of a server, by how the hold ended: released to a"
            + " server, or expired.");
    result(out, "warp_antibot_holds_total", "started", world.holds());
    result(out, "warp_antibot_holds_total", "released", world.holdsReleased());
    result(out, "warp_antibot_holds_total", "expired", world.holdsExpired());
  }

  private static void result(SampleVisitor out, String name, String result, double value) {
    out.sample(name, RESULT, List.of(result), value);
  }

  private static void gauge(SampleVisitor out, String name, String help, double value) {
    out.family(name, MetricType.GAUGE, help);
    out.sample(name, NONE, NONE, value);
  }

  private static String lower(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }
}
