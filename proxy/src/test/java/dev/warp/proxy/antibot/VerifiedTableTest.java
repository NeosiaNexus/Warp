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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("VerifiedTable")
class VerifiedTableTest {

  @Nested
  @DisplayName("as a map")
  class AsAMap {

    @Test
    @DisplayName("should find what it holds, update in place and forget what it removes")
    void basics() {
      VerifiedTable table = new VerifiedTable(10);

      assertTrue(table.put(1, 2, 100, 100, (byte) 0));
      assertFalse(table.put(1, 2, 100, 150, (byte) 1), "an update, not a new entry");
      VerifiedTable.Entry entry = table.find(1, 2);

      assertNotNull(entry);
      assertEquals(new VerifiedTable.Entry(100, 150, (byte) 1), entry);
      assertNull(table.find(1, 3));
      assertTrue(table.remove(1, 2));
      assertFalse(table.remove(1, 2));
      assertNull(table.find(1, 2));
      assertEquals(0, table.size());
    }

    @Test
    @DisplayName("should agree with a hash map over random inserts and removals (backward shift)")
    void randomAgainstHashMap() {
      SplittableRandom random = new SplittableRandom(42);
      VerifiedTable table = new VerifiedTable(2_000);
      Map<Long, Integer> reference = new HashMap<>();
      for (int i = 0; i < 50_000; i++) {
        // Few distinct low keys collide into long probe runs.
        long low = random.nextLong(1_500) << 32;
        if (random.nextInt(3) == 0) {
          assertEquals(reference.remove(low) != null, table.remove(7, low));
        } else if (reference.size() < 2_000 || reference.containsKey(low)) {
          int seen = 1 + random.nextInt(1_000_000);
          table.put(7, low, 1, seen, (byte) 0);
          reference.put(low, seen);
        }
      }
      assertEquals(reference.size(), table.size());
      for (long low = 0; low < 1_500; low++) {
        VerifiedTable.Entry entry = table.find(7, low << 32);
        Integer seen = reference.get(low << 32);
        assertEquals(seen, entry == null ? null : entry.lastSeen(), "key " + low);
      }
    }
  }

  @Nested
  @DisplayName("bounds")
  class Bounds {

    @Test
    @DisplayName("should never hold more than its capacity, evicting a least recently seen one")
    void evicts() {
      VerifiedTable table = new VerifiedTable(100);
      for (int i = 1; i <= 100; i++) {
        table.put(0, (long) i << 40, i, i, (byte) 0);
      }

      table.put(0, 1L << 62, 1000, 1000, (byte) 0);

      assertEquals(100, table.size());
      assertNotNull(table.find(0, 1L << 62), "the newcomer is in");
    }

    @Test
    @DisplayName("should sweep the identities last seen before the cutoff, slice by slice")
    void sweeps() {
      VerifiedTable table = new VerifiedTable(1_000);
      for (int i = 1; i <= 1_000; i++) {
        table.put(0, (long) i * 0x9E3779B97F4A7C15L, i, i, (byte) 0);
      }

      int from = 0;
      do {
        from = table.sweep(from, 97, 501);
      } while (from != 0);

      assertEquals(500, table.size());
      for (int i = 1; i <= 1_000; i++) {
        assertEquals(i > 500, table.find(0, (long) i * 0x9E3779B97F4A7C15L) != null, "id " + i);
      }
    }
  }
}
