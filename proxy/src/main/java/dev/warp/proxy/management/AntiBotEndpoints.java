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
package dev.warp.proxy.management;

import dev.warp.api.antibot.AttackReport;
import dev.warp.api.antibot.Signal;
import dev.warp.api.antibot.VerificationFailure;
import dev.warp.proxy.antibot.AntiBotStatus;
import dev.warp.proxy.limit.Refusal;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Renders the anti-bot's status for the management server: the JSON of {@code GET /antibot} and the
 * Prometheus text of {@code GET /metrics} (names in the scheme of {@code
 * observability-architecture.md}: {@code warp_<subsystem>_<name>_<unit>}, labels from bounded enums
 * only).
 */
final class AntiBotEndpoints {

  private AntiBotEndpoints() {}

  // ---------------------------------------------------------------------------
  // JSON
  // ---------------------------------------------------------------------------

  /**
   * Describes where the anti-bot stands.
   *
   * @param status the snapshot
   * @return the JSON of {@code GET /antibot}
   */
  static JsonObject json(AntiBotStatus status) {
    JsonObject body = new JsonObject();
    body.addProperty("enabled", status.enabled());
    body.addProperty("level", lower(status.level()));
    body.addProperty("lockdown", status.lockdown());
    AttackReport attack = status.attack();
    body.add("attack", attack == null ? null : attack(attack));

    JsonObject signals = new JsonObject();
    for (Signal signal : Signal.values()) {
      JsonObject one = new JsonObject();
      one.addProperty("lastSecond", status.signals().lastSecond().get(signal));
      one.addProperty("average10s", status.signals().averages().get(signal));
      one.addProperty("baseline", status.signals().baselines().get(signal));
      long threshold = status.signals().thresholds().getOrDefault(signal, Long.MAX_VALUE);
      one.addProperty("threshold", threshold == Long.MAX_VALUE ? null : threshold);
      signals.add(lower(signal), one);
    }
    body.add("signals", signals);

    JsonObject pending = new JsonObject();
    pending.addProperty("connections", status.pendingConnections());
    pending.addProperty("max", status.maxPendingConnections());
    body.add("notLoggedIn", pending);

    JsonObject lane = new JsonObject();
    lane.addProperty("inFlight", status.authenticationsInFlight());
    lane.addProperty("max", status.maxAuthentications());
    lane.addProperty("waiting", status.authenticationQueue());
    lane.addProperty("grantedFromLine", status.authenticationsQueued());
    lane.addProperty("waitedSeconds", status.authenticationWaitSeconds());
    body.add("authentications", lane);

    JsonObject verified = new JsonObject();
    verified.addProperty("identities", status.verified());
    verified.addProperty("capacity", status.verifiedCapacity());
    verified.addProperty("droppedChanges", status.droppedChanges());
    verified.addProperty("shared", status.sharedStore());
    body.add("verified", verified);

    AntiBotStatus.Verifications verifications = status.verifications();
    JsonObject verifying = new JsonObject();
    verifying.addProperty("running", verifications.running());
    verifying.addProperty("max", verifications.maxRunning());
    verifying.addProperty("waiting", verifications.waiting());
    verifying.addProperty("maxWaiting", verifications.maxWaiting());
    verifying.addProperty("grantedFromLine", verifications.grantedFromLine());
    verifying.addProperty("turnedAway", verifications.turnedAway());
    verifying.addProperty("waitedSeconds", verifications.waitedSeconds());
    verifying.addProperty("passed", verifications.passed());
    verifying.addProperty("escalated", verifications.escalated());
    JsonObject failed = new JsonObject();
    for (VerificationFailure reason : VerificationFailure.values()) {
      failed.addProperty(lower(reason), verifications.failed().getOrDefault(reason, 0L));
    }
    verifying.add("failed", failed);
    JsonObject tokens = new JsonObject();
    tokens.addProperty("issued", verifications.tokensIssued());
    tokens.addProperty("accepted", verifications.tokensAccepted());
    tokens.addProperty("rejected", verifications.tokensRejected());
    Instant keySince = verifications.tokenKeySince();
    tokens.addProperty("keySince", keySince == null ? null : keySince.toString());
    verifying.add("tokens", tokens);
    body.add("verification", verifying);

    body.addProperty("blocklisted", status.blocklisted() < 0 ? null : status.blocklisted());
    body.addProperty("checks", status.checks());

    JsonObject refused = new JsonObject();
    for (Refusal reason : Refusal.values()) {
      refused.addProperty(reason.label(), status.refusals().getOrDefault(reason, 0L));
    }
    body.add("refused", refused);
    body.addProperty("time", status.now().toString());
    return body;
  }

  private static JsonObject attack(AttackReport attack) {
    JsonObject body = new JsonObject();
    body.addProperty("startedAt", attack.startedAt().toString());
    body.addProperty("endedAt", attack.endedAt() == null ? null : attack.endedAt().toString());
    body.addProperty("trigger", lower(attack.trigger()));
    body.addProperty("triggerRate", attack.triggerRate());
    body.addProperty("threshold", attack.threshold());
    body.add("peaks", counts(attack.peaks()));
    body.add("totals", counts(attack.totals()));
    JsonArray sources = new JsonArray();
    for (AttackReport.Source source : attack.topSources()) {
      JsonObject one = new JsonObject();
      one.addProperty("network", source.network());
      one.addProperty("events", source.events());
      sources.add(one);
    }
    body.add("topSources", sources);
    return body;
  }

  private static JsonObject counts(Map<Signal, Long> values) {
    JsonObject body = new JsonObject();
    values.forEach((signal, value) -> body.addProperty(lower(signal), value));
    return body;
  }

  // ---------------------------------------------------------------------------
  // Prometheus
  // ---------------------------------------------------------------------------

  /**
   * Writes the anti-bot's and the limits' metrics in the Prometheus text format, version 0.0.4.
   *
   * @param status the snapshot
   * @return the text of {@code GET /metrics}
   */
  static String metrics(AntiBotStatus status) {
    StringBuilder out = new StringBuilder(4096);
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

    header(out, "warp_antibot_signal_per_second", "gauge", "Each signal over the last second.");
    for (Signal signal : Signal.values()) {
      sample(
          out,
          "warp_antibot_signal_per_second",
          "signal",
          lower(signal),
          status.signals().lastSecond().getOrDefault(signal, 0L));
    }
    header(
        out,
        "warp_antibot_signal_baseline",
        "gauge",
        "Each signal's learned baseline, per second.");
    for (Signal signal : Signal.values()) {
      sample(
          out,
          "warp_antibot_signal_baseline",
          "signal",
          lower(signal),
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

    header(
        out,
        "warp_connections_refused_total",
        "counter",
        "Connections refused or closed before login, by reason.");
    for (Refusal reason : Refusal.values()) {
      sample(
          out,
          "warp_connections_refused_total",
          "reason",
          reason.label(),
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
    header(
        out,
        "warp_auth_lane_wait_seconds",
        "summary",
        "How long logins granted an authentication from the line waited.");
    out.append("warp_auth_lane_wait_seconds_sum ")
        .append(number(status.authenticationWaitSeconds()))
        .append('\n');
    out.append("warp_auth_lane_wait_seconds_count ")
        .append(status.authenticationsQueued())
        .append('\n');
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
    header(
        out,
        "warp_antibot_queue_wait_seconds",
        "summary",
        "How long logins granted a verification from the line waited.");
    out.append("warp_antibot_queue_wait_seconds_sum ")
        .append(number(verifications.waitedSeconds()))
        .append('\n');
    out.append("warp_antibot_queue_wait_seconds_count ")
        .append(verifications.grantedFromLine())
        .append('\n');
    header(
        out,
        "warp_antibot_verifications_total",
        "counter",
        "Verifications of unknown identities by result: passed, escalated (passed, not"
            + " trusted), busy (no room), or failed by reason.");
    sample(out, "warp_antibot_verifications_total", "result", "passed", verifications.passed());
    sample(
        out, "warp_antibot_verifications_total", "result", "escalated", verifications.escalated());
    sample(out, "warp_antibot_verifications_total", "result", "busy", verifications.turnedAway());
    for (VerificationFailure reason : VerificationFailure.values()) {
      sample(
          out,
          "warp_antibot_verifications_total",
          "result",
          "failed_" + lower(reason),
          verifications.failed().getOrDefault(reason, 0L));
    }
    header(
        out,
        "warp_antibot_tokens_total",
        "counter",
        "Verification tokens issued to transferred clients, and accepted or rejected on return.");
    sample(out, "warp_antibot_tokens_total", "result", "issued", verifications.tokensIssued());
    sample(out, "warp_antibot_tokens_total", "result", "accepted", verifications.tokensAccepted());
    sample(out, "warp_antibot_tokens_total", "result", "rejected", verifications.tokensRejected());
    return out.toString();
  }

  private static void gauge(StringBuilder out, String name, String help, double value) {
    header(out, name, "gauge", help);
    out.append(name).append(' ').append(number(value)).append('\n');
  }

  private static void header(StringBuilder out, String name, String type, String help) {
    out.append("# HELP ").append(name).append(' ').append(help).append('\n');
    out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
  }

  private static void sample(
      StringBuilder out, String name, String label, String value, double sample) {
    out.append(name)
        .append('{')
        .append(label)
        .append("=\"")
        .append(value)
        .append("\"} ")
        .append(number(sample))
        .append('\n');
  }

  private static String number(double value) {
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      return Long.toString((long) value);
    }
    return String.format(Locale.ROOT, "%.6f", value);
  }

  private static String lower(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }
}
