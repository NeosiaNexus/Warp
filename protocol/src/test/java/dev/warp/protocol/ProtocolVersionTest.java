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
package dev.warp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ProtocolVersion")
class ProtocolVersionTest {

  @Nested
  @DisplayName("registry")
  class Registry {

    @Test
    @DisplayName("should list every version once, oldest first, in an unmodifiable list")
    void values() {
      List<ProtocolVersion> values = ProtocolVersion.values();

      for (int i = 1; i < values.size(); i++) {
        assertTrue(
            values.get(i - 1).protocol() <= values.get(i).protocol(),
            values.get(i - 1) + " before " + values.get(i));
        assertNotEquals(values.get(i - 1), values.get(i));
      }
      assertThrows(
          UnsupportedOperationException.class, () -> values.add(ProtocolVersion.MINECRAFT_1_8));
    }

    @Test
    @DisplayName("should name the oldest and the newest version")
    void oldestAndLatest() {
      assertSame(ProtocolVersion.MINECRAFT_1_7_2, ProtocolVersion.oldest());
      assertSame(ProtocolVersion.values().getLast(), ProtocolVersion.latest());
    }

    @Test
    @DisplayName("should find a version by protocol ID, the first one registered for a shared ID")
    void byProtocolId() {
      assertSame(ProtocolVersion.MINECRAFT_1_8, ProtocolVersion.byProtocolId(47));
      assertSame(ProtocolVersion.MINECRAFT_1_20, ProtocolVersion.byProtocolId(763));
      assertNull(ProtocolVersion.byProtocolId(46));
    }
  }

  @Nested
  @DisplayName("identity and order")
  class IdentityAndOrder {

    @Test
    @DisplayName("should tell apart versions sharing a protocol ID, yet order them together")
    void sharedProtocolId() {
      ProtocolVersion v1200 = ProtocolVersion.MINECRAFT_1_20;
      ProtocolVersion v1201 = ProtocolVersion.MINECRAFT_1_20_1;

      assertEquals(v1200.protocol(), v1201.protocol());
      assertNotEquals(v1200, v1201);
      assertEquals(0, v1200.compareTo(v1201));
      assertEquals("1.20", v1200.name());
      assertEquals("1.20.1", v1201.name());
    }

    @Test
    @DisplayName("should equal itself only, and nothing that is not a version")
    void equality() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_8;

      assertEquals(version, version);
      assertEquals(version.hashCode(), version.hashCode());
      assertNotEquals(version, ProtocolVersion.MINECRAFT_1_9);
      Object name = version.name();
      assertFalse(version.equals(name));
    }

    @Test
    @DisplayName("should compare inclusively at the bounds")
    void comparisons() {
      ProtocolVersion older = ProtocolVersion.MINECRAFT_1_8;
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_12_2;
      ProtocolVersion newer = ProtocolVersion.MINECRAFT_1_16;

      assertTrue(version.isAtLeast(version));
      assertTrue(version.isAtMost(version));
      assertFalse(version.isNewerThan(version));
      assertFalse(version.isOlderThan(version));
      assertTrue(version.isNewerThan(older));
      assertTrue(version.isOlderThan(newer));
      assertTrue(version.isBetween(version, version));
      assertTrue(version.isBetween(older, newer));
      assertFalse(older.isBetween(version, newer));
      assertFalse(newer.isBetween(older, version));
      assertTrue(older.compareTo(version) < 0);
      assertTrue(newer.compareTo(version) > 0);
    }

    @Test
    @DisplayName("should have a configuration phase from 1.20.2")
    void configurationState() {
      assertFalse(ProtocolVersion.MINECRAFT_1_20_1.supportsConfigurationState());
      assertTrue(ProtocolVersion.MINECRAFT_1_20_2.supportsConfigurationState());
    }
  }
}
