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
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("TextComponent utility")
class TextComponentTest {

  // ---------------------------------------------------------------------------
  // Before 1.20.3: JSON string
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("before 1.20.3")
  class JsonFormat {

    static Stream<ProtocolVersion> jsonVersions() {
      return ProtocolVersion.values().stream()
          .filter(version -> version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jsonVersions")
    @DisplayName("should write a VarInt-prefixed JSON string")
    void prefixedJsonString(ProtocolVersion version) {
      byte[] encoded = TextComponent.plainText("Kicked", version);

      // {"text":"Kicked"} is 17 bytes.
      assertArrayEquals(concat(new byte[] {0x11}, utf8("{\"text\":\"Kicked\"}")), encoded);
    }

    @Test
    @DisplayName("should write a two-byte VarInt for a JSON string of 128 bytes or more")
    void twoByteLength() {
      byte[] encoded = TextComponent.plainText("x".repeat(200), ProtocolVersion.MINECRAFT_1_20_2);

      // 211 bytes of JSON: VarInt 0xD3 0x01.
      String json = "{\"text\":\"" + "x".repeat(200) + "\"}";
      assertArrayEquals(concat(new byte[] {(byte) 0xD3, 0x01}, utf8(json)), encoded);
    }

    @Test
    @DisplayName("should be read whole by a strict string reader")
    void strictRead() {
      ByteBuf buf =
          Unpooled.wrappedBuffer(
              TextComponent.plainText(
                  "Could not connect to backend server", ProtocolVersion.MINECRAFT_1_8));

      assertEquals("{\"text\":\"Could not connect to backend server\"}", McString.read(buf));
      assertFalse(buf.isReadable(), "nothing may follow the string");
    }
  }

  // ---------------------------------------------------------------------------
  // From 1.20.3: NBT string tag
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("from 1.20.3")
  class NbtFormat {

    static Stream<ProtocolVersion> nbtVersions() {
      return ProtocolVersion.values().stream()
          .filter(version -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nbtVersions")
    @DisplayName("should write a network NBT string tag")
    void stringTag(ProtocolVersion version) {
      byte[] encoded = TextComponent.plainText("Kicked", version);

      // TAG_String, unsigned-short length 6, the text.
      assertArrayEquals(concat(new byte[] {0x08, 0x00, 0x06}, utf8("Kicked")), encoded);
    }

    @Test
    @DisplayName("should write modified UTF-8, which the client reads with DataInput.readUTF")
    void modifiedUtf8() throws IOException {
      String text = "a\u0000é€😀"; // a, NUL, e acute, euro sign, an emoji

      byte[] encoded = TextComponent.plainText(text, ProtocolVersion.MINECRAFT_1_20_3);

      // 'a' 61, NUL C0 80, é C3 A9, € E2 82 AC, emoji as two 3-byte surrogates: 14 bytes.
      assertArrayEquals(
          HexFormat.of().parseHex("08000e" + "61" + "c080" + "c3a9" + "e282ac" + "eda0bdedb880"),
          encoded);
      assertEquals(text, readStringTag(encoded));
    }

    @Test
    @DisplayName("should switch from one to two bytes at U+0080 and to three at U+0800")
    void encodingBoundaries() throws IOException {
      String text = "\u007F\u0080\u07FF\u0800";

      byte[] encoded = TextComponent.plainText(text, ProtocolVersion.MINECRAFT_1_20_3);

      assertArrayEquals(
          HexFormat.of().parseHex("080008" + "7f" + "c280" + "dfbf" + "e0a080"), encoded);
      assertEquals(text, readStringTag(encoded));
    }

    @Test
    @DisplayName("should accept 65 535 bytes of text and reject one more")
    void lengthLimit() throws IOException {
      String longest = "€".repeat(21_845); // 3 bytes each

      byte[] encoded = TextComponent.plainText(longest, ProtocolVersion.MINECRAFT_1_20_3);

      assertEquals(3 + 65_535, encoded.length);
      assertEquals(longest, readStringTag(encoded));
      assertThrows(
          IllegalArgumentException.class,
          () -> TextComponent.plainText(longest + "a", ProtocolVersion.MINECRAFT_1_20_3));
    }

    /** Reads {@code encoded} as the client does, requiring a string tag and nothing after it. */
    private static String readStringTag(byte[] encoded) throws IOException {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
      assertEquals(0x08, in.readByte(), "TAG_String");
      String text = in.readUTF();
      assertEquals(0, in.available(), "nothing may follow the tag");
      return text;
    }
  }

  // ---------------------------------------------------------------------------
  // JSON text component
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("plainTextJson")
  class PlainTextJson {

    @Test
    @DisplayName("should wrap the text in a text component")
    void wraps() {
      assertEquals(
          "{\"text\":\"Invalid username\"}", TextComponent.plainTextJson("Invalid username"));
    }

    @Test
    @DisplayName("should escape quotes, backslashes and control characters")
    void escapes() {
      String json = TextComponent.plainTextJson("say \"hi\" \\ \n\r\t\u0001\u001f");

      assertEquals("{\"text\":\"say \\\"hi\\\" \\\\ \\n\\r\\t\\u0001\\u001f\"}", json);
    }

    @Test
    @DisplayName("should keep other characters as they are")
    void keepsUnicode() {
      assertEquals("{\"text\":\"é€😀 /\"}", TextComponent.plainTextJson("é€😀 /"));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private byte[] concat(byte[] head, byte[] tail) {
    byte[] out = new byte[head.length + tail.length];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(tail, 0, out, head.length, tail.length);
    return out;
  }
}
