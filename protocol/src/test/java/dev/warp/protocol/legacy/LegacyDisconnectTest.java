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
package dev.warp.protocol.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("LegacyDisconnect")
class LegacyDisconnectTest {

  private static final String SECTION = "§";

  @Nested
  @DisplayName("ofStatus()")
  class OfStatus {

    @Test
    @DisplayName("should answer a 1.3 ping with the plain first line and the counts, § separated")
    void minecraft13() {
      LegacyDisconnect answer =
          LegacyDisconnect.ofStatus(
              LegacyPing.Format.MINECRAFT_1_3,
              127,
              "Warp",
              SECTION + "bA " + SECTION + "lWarp" + SECTION + "r Proxy\nsecond line",
              3,
              500);

      assertEquals("A Warp Proxy" + SECTION + "3" + SECTION + "500", answer.reason());
    }

    @Test
    @DisplayName("should answer 1.4 to 1.6 pings with every field, colors kept, first line only")
    void minecraft14() {
      LegacyDisconnect answer =
          LegacyDisconnect.ofStatus(
              LegacyPing.Format.MINECRAFT_1_6,
              127,
              "Warp 1.7.2-26.3",
              SECTION + "bA Warp Proxy\nsecond line",
              3,
              500);

      assertEquals(
          SECTION + "1\0" + "127\0Warp 1.7.2-26.3\0" + SECTION + "bA Warp Proxy\0" + "3\0" + "500",
          answer.reason());
    }

    @Test
    @DisplayName("should keep a separator out of the version name and description")
    void separators() {
      LegacyDisconnect answer =
          LegacyDisconnect.ofStatus(LegacyPing.Format.MINECRAFT_1_4, 127, "a\0b", "c\0d", 0, 0);

      assertEquals(SECTION + "1\0" + "127\0a b\0c d\0" + "0\0" + "0", answer.reason());
    }
  }

  @Test
  @DisplayName("should cut a reason longer than the length prefix announces")
  void truncates() {
    LegacyDisconnect answer = new LegacyDisconnect("x".repeat(LegacyDisconnect.MAX_LENGTH + 10));

    assertEquals(LegacyDisconnect.MAX_LENGTH, answer.reason().length());
  }
}
