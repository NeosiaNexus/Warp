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
package dev.warp.protocol.packet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("TextComponent utility")
class TextComponentTest {

  @Test
  @DisplayName("should produce valid JSON for pre-1.20.3")
  void jsonFormat() {
    byte[] result = TextComponent.plainText("Server is full", ProtocolVersion.MINECRAFT_1_19_3);
    // Should be a VarInt-prefixed JSON string containing {"text":"Server is full"}
    assertTrue(result.length > 0);
    // Decode the VarInt prefix manually and check the JSON
    String json =
        new String(
            result,
            findJsonStart(result),
            result.length - findJsonStart(result),
            StandardCharsets.UTF_8);
    assertTrue(json.contains("\"text\""));
    assertTrue(json.contains("Server is full"));
  }

  @Test
  @DisplayName("should produce valid NBT for 1.20.3+")
  void nbtFormat() {
    byte[] result = TextComponent.plainText("Kicked", ProtocolVersion.MINECRAFT_1_20_3);
    // Should start with 0x08 (TAG_String)
    assertTrue(result.length > 3);
    assertTrue(result[0] == 0x08, "Should start with TAG_String (0x08)");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(ints = {4, 47, 340, 578, 735, 754, 758, 759, 760, 764})
  @DisplayName("should write the same plain JSON component for every version before 1.20.3")
  void sameJsonBefore1203(int protocol) {
    // Plain text has no hover or click event, the only parts whose JSON changed (in 1.16).
    ProtocolVersion version = Objects.requireNonNull(ProtocolVersion.byProtocolId(protocol));
    String json = "{\"text\":\"Servers: [lobby]\"}";

    byte[] result = TextComponent.plainText("Servers: [lobby]", version);

    byte[] expected = new byte[1 + json.length()];
    expected[0] = (byte) json.length();
    System.arraycopy(json.getBytes(StandardCharsets.US_ASCII), 0, expected, 1, json.length());
    assertArrayEquals(expected, result);
  }

  @Test
  @DisplayName("should produce a VarInt-prefixed JSON string on request, whatever the version")
  void jsonOnRequest() {
    byte[] result = TextComponent.plainTextJson("Invalid username");

    ByteBuf buf = Unpooled.wrappedBuffer(result);
    assertEquals("{\"text\":\"Invalid username\"}", McString.read(buf));
    assertFalse(buf.isReadable());
    assertArrayEquals(
        result, TextComponent.plainText("Invalid username", ProtocolVersion.oldest()));
  }

  @Test
  @DisplayName("should escape special JSON characters")
  void jsonEscaping() {
    byte[] result = TextComponent.plainText("He said \"hello\"", ProtocolVersion.MINECRAFT_1_19_3);
    String str = new String(result, StandardCharsets.UTF_8);
    assertTrue(str.contains("\\\"hello\\\""));
  }

  private int findJsonStart(byte[] data) {
    // Skip VarInt prefix (1-5 bytes)
    for (int i = 0; i < Math.min(5, data.length); i++) {
      if ((data[i] & 0x80) == 0) return i + 1;
    }
    return 0;
  }
}
