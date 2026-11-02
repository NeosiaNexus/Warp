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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DrainStatus")
class DrainStatusTest {

  private static final Instant START = Instant.parse("2026-11-02T12:00:00Z");

  @Test
  @DisplayName("should describe a proxy that is not draining")
  void notDraining() {
    DrainStatus status = DrainStatus.notDraining(12);

    assertFalse(status.isDraining());
    assertEquals(DrainPhase.NONE, status.phase());
    assertEquals(12, status.players());
    assertNull(status.trigger());
    assertNull(status.startedAt());
    assertNull(status.outcome());
    assertEquals(Duration.ZERO, status.elapsed());
  }

  @Test
  @DisplayName("should describe a drain in progress, then over")
  void draining() {
    DrainStatus waiting =
        new DrainStatus(
            DrainPhase.WAITING,
            DrainTrigger.SIGNAL,
            START,
            Duration.ofSeconds(9),
            3,
            40,
            2,
            1,
            4,
            null);
    DrainStatus over =
        new DrainStatus(
            DrainPhase.STOPPING,
            DrainTrigger.SIGNAL,
            START,
            Duration.ofSeconds(60),
            0,
            42,
            2,
            1,
            4,
            DrainOutcome.DRAINED);

    assertTrue(waiting.isDraining());
    assertTrue(over.isDraining());
    assertEquals(DrainOutcome.DRAINED, over.outcome());
  }

  @Test
  @DisplayName("should refuse negative counts and durations")
  void negative() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DrainStatus(
                DrainPhase.NONE, null, null, Duration.ofSeconds(-1), 0, 0, 0, 0, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DrainStatus(DrainPhase.NONE, null, null, Duration.ZERO, -1, 0, 0, 0, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DrainStatus(
                DrainPhase.WAITING, DrainTrigger.HTTP, START, Duration.ZERO, 0, 0, 0, 0, -2, null));
  }

  @Test
  @DisplayName("should refuse a drain without a trigger or start, and a proxy at rest with one")
  void triggerAndStart() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DrainStatus(
                DrainPhase.ANNOUNCING, null, START, Duration.ZERO, 0, 0, 0, 0, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DrainStatus(
                DrainPhase.ANNOUNCING,
                DrainTrigger.HTTP,
                null,
                Duration.ZERO,
                0,
                0,
                0,
                0,
                0,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DrainStatus(
                DrainPhase.NONE, DrainTrigger.HTTP, START, Duration.ZERO, 0, 0, 0, 0, 0, null));
  }

  @Test
  @DisplayName("should carry an outcome from the stopping phase on, and only then")
  void outcome() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DrainStatus(
                DrainPhase.WAITING,
                DrainTrigger.HTTP,
                START,
                Duration.ZERO,
                0,
                0,
                0,
                0,
                0,
                DrainOutcome.DRAINED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DrainStatus(
                DrainPhase.STOPPING, DrainTrigger.HTTP, START, Duration.ZERO, 0, 0, 0, 0, 0, null));
    assertDoesNotThrow(
        () ->
            new DrainStatus(
                DrainPhase.STOPPING,
                DrainTrigger.PLUGIN,
                START,
                Duration.ZERO,
                1,
                0,
                0,
                0,
                0,
                DrainOutcome.TIMED_OUT));
  }
}
