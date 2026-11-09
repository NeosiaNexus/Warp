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

  private static String lower(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }
}
