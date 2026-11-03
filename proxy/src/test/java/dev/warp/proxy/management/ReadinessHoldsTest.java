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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.lifecycle.ReadinessHold;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReadinessHolds")
class ReadinessHoldsTest {

  private final ReadinessHolds holds = new ReadinessHolds();

  @Test
  @DisplayName("should hold readiness until every hold is released")
  void holdsUntilReleased() {
    ReadinessHold lobby = holds.hold("no lobby yet");
    ReadinessHold cache = holds.hold("cache warming");

    lobby.release();

    assertTrue(holds.isHeld());
    assertEquals(List.of("cache warming"), holds.reasons());

    cache.close();

    assertFalse(holds.isHeld());
    assertFalse(cache.isHeld());
  }

  @Test
  @DisplayName("should report the reasons sorted, two holds with one reason counting twice")
  void reasons() {
    holds.hold("b");
    holds.hold("a");
    ReadinessHold again = holds.hold("b");

    assertEquals(List.of("a", "b", "b"), holds.reasons());

    again.release();

    assertEquals(List.of("a", "b"), holds.reasons());
  }

  @Test
  @DisplayName("should ignore a second release")
  void releaseTwice() {
    ReadinessHold first = holds.hold("x");
    ReadinessHold second = holds.hold("x");

    first.release();
    first.release();

    assertTrue(second.isHeld());
    assertTrue(holds.isHeld());
  }

  @Test
  @DisplayName("should refuse a hold without a reason")
  void blankReason() {
    assertThrows(IllegalArgumentException.class, () -> holds.hold(" "));
    assertFalse(holds.isHeld());
  }
}
