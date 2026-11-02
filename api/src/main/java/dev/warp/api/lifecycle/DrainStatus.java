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
package dev.warp.api.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Where a drain stands: its phase and what it did so far. A snapshot, taken when it was asked for.
 *
 * @param phase the phase, {@link DrainPhase#NONE} when the proxy is not draining
 * @param trigger what started the drain, or {@code null} when not draining
 * @param startedAt when the drain started, or {@code null} when not draining
 * @param elapsed how long the drain has been running, zero when not draining
 * @param players the players connected now
 * @param transferred the players sent away with a Transfer packet
 * @param kept the players the drain kept rather than move, each counted once, whether they left
 *     since or not
 * @param kicked the players the drain disconnected on its way: clients it cannot transfer, when
 *     {@code drain.cannot-transfer} is {@code kick}, or a plugin's decision
 * @param refused the logins the proxy refused while draining
 * @param outcome how the drain ended, from {@link DrainPhase#STOPPING} on; {@code null} before
 */
public record DrainStatus(
    DrainPhase phase,
    @Nullable DrainTrigger trigger,
    @Nullable Instant startedAt,
    Duration elapsed,
    int players,
    int transferred,
    int kept,
    int kicked,
    int refused,
    @Nullable DrainOutcome outcome) {

  /**
   * Checks the status is consistent.
   *
   * @throws IllegalArgumentException if a count or the elapsed time is negative, a drain has no
   *     trigger or start time, the proxy not draining has one, or an outcome comes before {@link
   *     DrainPhase#STOPPING}
   */
  public DrainStatus {
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(elapsed, "elapsed");
    if (elapsed.isNegative()) {
      throw new IllegalArgumentException("elapsed must not be negative, got " + elapsed);
    }
    if (players < 0 || transferred < 0 || kept < 0 || kicked < 0 || refused < 0) {
      throw new IllegalArgumentException("counts must not be negative");
    }
    boolean draining = phase != DrainPhase.NONE;
    if (draining != (trigger != null) || draining != (startedAt != null)) {
      throw new IllegalArgumentException(
          "a drain has a trigger and a start time, and only a drain has them; phase " + phase);
    }
    if ((outcome != null) != (phase == DrainPhase.STOPPING)) {
      throw new IllegalArgumentException(
          "a drain has an outcome from the STOPPING phase on, and only then; phase " + phase);
    }
  }

  /**
   * Returns the status of a proxy that is not draining.
   *
   * @param players the players connected
   * @return the status, in {@link DrainPhase#NONE}
   */
  public static DrainStatus notDraining(int players) {
    return new DrainStatus(DrainPhase.NONE, null, null, Duration.ZERO, players, 0, 0, 0, 0, null);
  }

  /**
   * Returns whether the proxy is draining.
   *
   * @return {@code true} in any phase but {@link DrainPhase#NONE}
   */
  public boolean isDraining() {
    return phase != DrainPhase.NONE;
  }
}
