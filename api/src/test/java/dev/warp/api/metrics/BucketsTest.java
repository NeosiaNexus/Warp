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
package dev.warp.api.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Buckets")
class BucketsTest {

  @Test
  @DisplayName("should place an observation in the first bucket whose bound is at least the value")
  void indexOf() {
    Buckets buckets = Buckets.of(0.1, 0.5, 1);

    assertEquals(0, buckets.indexOf(0));
    assertEquals(0, buckets.indexOf(0.1));
    assertEquals(1, buckets.indexOf(0.1000001));
    assertEquals(2, buckets.indexOf(1));
    assertEquals(3, buckets.indexOf(1.5));
    assertEquals(3, buckets.indexOf(Double.POSITIVE_INFINITY));
    assertEquals(0, buckets.indexOf(-3));
  }

  @Test
  @DisplayName("should keep the presets sorted and under the cap")
  void presets() {
    for (Buckets preset : List.of(Buckets.FAST, Buckets.NETWORK, Buckets.LONG)) {
      List<Double> bounds = preset.bounds();
      assertEquals(bounds.stream().sorted().toList(), bounds);
      assertEquals(bounds.size(), preset.size());
      assertEquals(true, preset.size() <= Buckets.MAX_BUCKETS);
    }
    assertEquals(0.001, Buckets.FAST.bound(0));
    assertEquals(30.0, Buckets.NETWORK.bound(Buckets.NETWORK.size() - 1));
  }

  @Test
  @DisplayName("should refuse empty, too many, unsorted or infinite bounds")
  void validation() {
    assertThrows(IllegalArgumentException.class, Buckets::of);
    assertThrows(IllegalArgumentException.class, () -> Buckets.of(1, 1));
    assertThrows(IllegalArgumentException.class, () -> Buckets.of(2, 1));
    assertThrows(IllegalArgumentException.class, () -> Buckets.of(1, Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> Buckets.of(Double.NaN));
    double[] tooMany = new double[Buckets.MAX_BUCKETS + 1];
    for (int i = 0; i < tooMany.length; i++) {
      tooMany[i] = i + 1;
    }
    assertThrows(IllegalArgumentException.class, () -> Buckets.of(tooMany));
  }

  @Test
  @DisplayName("should compare by bounds")
  void equality() {
    assertEquals(Buckets.of(1, 2), Buckets.of(1, 2));
    assertEquals(Buckets.of(1, 2).hashCode(), Buckets.of(1, 2).hashCode());
    assertNotEquals(Buckets.of(1, 2), Buckets.of(1, 3));
    assertEquals("Buckets[1.0, 2.0]", Buckets.of(1, 2).toString());
  }
}
