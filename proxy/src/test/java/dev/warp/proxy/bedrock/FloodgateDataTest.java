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
package dev.warp.proxy.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FloodgateData")
class FloodgateDataTest {

  /** What Geyser writes for a player without a linked account and without the skin uploader. */
  static final String GEYSER_PLAINTEXT =
      "1.21.50\0Steve Jr\0"
          + "2535428912345678\0"
          + "1\0en_US\0"
          + "0\0"
          + "2\0"
          + "198.51.100.7\0"
          + "null\0"
          + "0\0"
          + "-1\0"
          + "null";

  @Nested
  @DisplayName("parse()")
  class Parse {

    @Test
    @DisplayName("should read the twelve fields in Floodgate's order")
    void readsGeyser() {
      FloodgateData data = FloodgateData.parse(GEYSER_PLAINTEXT);

      assertEquals("1.21.50", data.version());
      assertEquals("Steve Jr", data.username());
      assertEquals("2535428912345678", data.xuid());
      assertEquals(2535428912345678L, data.xuidValue());
      assertEquals(1, data.deviceOs());
      assertEquals("en_US", data.language());
      assertEquals(0, data.uiProfile());
      assertEquals(2, data.inputMode());
      assertEquals("198.51.100.7", data.ip());
      assertNull(data.linkedPlayer());
      assertFalse(data.fromProxy());
      assertEquals(-1, data.subscribeId());
      assertEquals("null", data.verifyCode());
    }

    @Test
    @DisplayName("should write back exactly what it read")
    void roundTrip() {
      assertEquals(GEYSER_PLAINTEXT, FloodgateData.parse(GEYSER_PLAINTEXT).encode());
    }

    @Test
    @DisplayName("should read a linked account as javaUsername;javaUuid;bedrockUuid")
    void linked() {
      UUID java = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
      UUID bedrock = new UUID(0, 2535428912345678L);
      String plaintext =
          GEYSER_PLAINTEXT.replace(
              "\0null\0" + "0\0", "\0Notch;" + java + ";" + bedrock + "\0" + "1\0");

      FloodgateData data = FloodgateData.parse(plaintext);

      FloodgateData.LinkedPlayer link = data.linkedPlayer();
      assertNotNull(link);
      assertEquals("Notch", link.javaUsername());
      assertEquals(java, link.javaId());
      assertEquals(bedrock, link.bedrockId());
      assertTrue(data.fromProxy());
      assertEquals(plaintext, data.encode());
    }

    @Test
    @DisplayName("should refuse another field count or a field that is not a number")
    void malformed() {
      assertThrows(IllegalArgumentException.class, () -> FloodgateData.parse("a\0b"));
      assertThrows(
          IllegalArgumentException.class,
          () -> FloodgateData.parse(GEYSER_PLAINTEXT + "\0thirteenth"));
      assertThrows(
          IllegalArgumentException.class,
          () -> FloodgateData.parse(GEYSER_PLAINTEXT.replace("\0en_US\0" + "0\0", "\0en_US\0x\0")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              FloodgateData.parse(GEYSER_PLAINTEXT.replace("2535428912345678", "xuid"))
                  .xuidValue());
      assertThrows(
          IllegalArgumentException.class,
          () -> FloodgateData.parse(GEYSER_PLAINTEXT.replace("2535428912345678", "0")).xuidValue());
    }

    @Test
    @DisplayName("should take a linked field that is not three parts as no link, as Floodgate does")
    void oddLink() {
      assertNull(FloodgateData.LinkedPlayer.parse(""));
      assertNull(FloodgateData.LinkedPlayer.parse("null"));
      assertNull(FloodgateData.LinkedPlayer.parse("Notch;only-two"));
      assertThrows(
          IllegalArgumentException.class, () -> FloodgateData.LinkedPlayer.parse("Notch;x;y"));
    }
  }

  @Test
  @DisplayName("fromProxy() should change the flag alone")
  void fromProxy() {
    FloodgateData data = FloodgateData.parse(GEYSER_PLAINTEXT).fromProxy(true);

    assertTrue(data.fromProxy());
    assertEquals(GEYSER_PLAINTEXT.replace("\0null\0" + "0\0", "\0null\0" + "1\0"), data.encode());
  }
}
