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
package dev.warp.proxy.antibot;

import dev.warp.api.antibot.AttackLevel;
import dev.warp.api.antibot.AttackReport;
import dev.warp.api.antibot.VerificationFailure;
import dev.warp.proxy.limit.Refusal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Where the anti-bot stands, for {@code /warp antibot}, the management server's {@code GET
 * /antibot} and its metrics: one consistent snapshot.
 *
 * @param enabled whether the detector, the store and lockdown run
 * @param level the detector's level
 * @param lockdown whether lockdown is on
 * @param attack the attack in progress, or {@code null}
 * @param signals the detector's latest second
 * @param pendingConnections connections not logged in now
 * @param maxPendingConnections their cap, or 0 for none
 * @param authenticationsInFlight authentication slots taken
 * @param maxAuthentications authentication slots, or 0 for no limit
 * @param authenticationQueue logins waiting for a slot
 * @param authenticationsQueued logins granted a slot from the line since the start
 * @param authenticationWaitSeconds how long those waited, in all
 * @param verified trusted identities
 * @param verifiedCapacity the most trusted identities remembered
 * @param droppedChanges changes to the store dropped as its thread was too far behind
 * @param blocklisted addresses on the blocklist, or -1 when it is off
 * @param refusals connections refused before login since the start, by reason
 * @param checks how many plugin checks are registered
 * @param sharedStore whether a plugin shares the trusted identities
 * @param verifications the verification of unknown identities
 * @param now when the snapshot was taken
 */
public record AntiBotStatus(
    boolean enabled,
    AttackLevel level,
    boolean lockdown,
    @Nullable AttackReport attack,
    AttackDetector.Snapshot signals,
    int pendingConnections,
    int maxPendingConnections,
    int authenticationsInFlight,
    int maxAuthentications,
    int authenticationQueue,
    long authenticationsQueued,
    double authenticationWaitSeconds,
    int verified,
    int verifiedCapacity,
    long droppedChanges,
    int blocklisted,
    Map<Refusal, Long> refusals,
    int checks,
    boolean sharedStore,
    Verifications verifications,
    Instant now) {

  /**
   * The verification of unknown identities: the probes running and waiting, their outcomes since
   * the start, and the tokens of verifications before authentication.
   *
   * @param running the verifications in progress
   * @param maxRunning the most at once
   * @param waiting the logins in line for one
   * @param maxWaiting the most in line
   * @param grantedFromLine the logins that got a verification from the line since the start
   * @param turnedAway the logins told the proxy was busy: the line full, or waited too long
   * @param waitedSeconds the time logins spent in line, in all
   * @param passed the verifications passed
   * @param escalated the verifications passed with two flags or more, which trusted nobody
   * @param failed the verifications failed, by reason
   * @param tokensIssued the tokens stored in transferred clients
   * @param tokensAccepted the tokens that came back valid
   * @param tokensRejected the tokens that came back invalid, expired or forged
   * @param tokenKeySince when the current token key started signing; {@code null} when the anti-bot
   *     is off
   */
  public record Verifications(
      int running,
      int maxRunning,
      int waiting,
      int maxWaiting,
      long grantedFromLine,
      long turnedAway,
      double waitedSeconds,
      long passed,
      long escalated,
      Map<VerificationFailure, Long> failed,
      long tokensIssued,
      long tokensAccepted,
      long tokensRejected,
      @Nullable Instant tokenKeySince) {

    /** Makes the failures an unmodifiable copy. */
    public Verifications {
      failed = Map.copyOf(failed);
    }

    /**
     * Returns the verifications failed for every reason.
     *
     * @return the sum
     */
    public long failedTotal() {
      long total = 0;
      for (long count : failed.values()) {
        total += count;
      }
      return total;
    }
  }

  /** Makes the refusals an unmodifiable copy. */
  public AntiBotStatus {
    refusals = Map.copyOf(refusals);
  }

  /**
   * Returns the blocklist's lines, when asked separately.
   *
   * @param cidrs the listed networks
   * @return the text of {@code GET /antibot/blocklist}: one CIDR per line
   */
  public static String blocklistText(List<String> cidrs) {
    StringBuilder text = new StringBuilder();
    for (String cidr : cidrs) {
      text.append(cidr).append('\n');
    }
    return text.toString();
  }
}
