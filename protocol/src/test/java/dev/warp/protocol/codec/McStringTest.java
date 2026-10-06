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
package dev.warp.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("McString codec")
class McStringTest {

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("read")
  class Read {

    @Test
    @DisplayName("should decode empty string")
    void emptyString() {
      ByteBuf buf = encodedString("");
      try {
        assertEquals("", McString.read(buf));
        assertEquals(0, buf.readableBytes());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should decode ASCII string")
    void asciiString() {
      ByteBuf buf = encodedString("Hello, World!");
      try {
        assertEquals("Hello, World!", McString.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should decode multibyte UTF-8 string")
    void multibyteUtf8() {
      ByteBuf buf = encodedString("Héllo 日本語");
      try {
        assertEquals("Héllo 日本語", McString.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should decode string with supplementary characters (surrogate pairs)")
    void supplementaryCharacters() {
      // Each emoji is a surrogate pair (2 UTF-16 code units, 4 UTF-8 bytes)
      ByteBuf buf = encodedString("\uD83C\uDFAE\uD83C\uDFB2"); // 🎮🎲
      try {
        assertEquals("\uD83C\uDFAE\uD83C\uDFB2", McString.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject negative byte length")
    void negativeLength() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, -1);
        assertThrows(DecoderException.class, () -> McString.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject byte length exceeding character limit")
    void byteLimitExceeded() {
      ByteBuf buf = Unpooled.buffer();
      try {
        // maxChars=5, so max bytes = 15. Write length 16 with enough backing data.
        VarInt.write(buf, 16);
        buf.writeBytes(new byte[16]);
        assertThrows(DecoderException.class, () -> McString.read(buf, 5));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName(
        "should accept the character limit in three-byte characters, the longest it allows")
    void byteLimitReached() {
      // maxChars=5: five three-byte characters are 15 bytes, the most the limit allows.
      ByteBuf buf = encodedString("\u20AC\u20AC\u20AC\u20AC\u20AC");
      try {
        assertEquals("\u20AC\u20AC\u20AC\u20AC\u20AC", McString.read(buf, 5));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject when buffer has insufficient data")
    void notEnoughData() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, 10); // claims 10 bytes
        buf.writeBytes(new byte[5]); // only 5 available
        assertThrows(DecoderException.class, () -> McString.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject when decoded string exceeds character limit")
    void charLimitExceeded() {
      // "abcdef" = 6 chars, 6 bytes. With maxChars=5: 6 <= 5*3=15 (byte check passes),
      // but 6 > 5 (char check fails).
      ByteBuf buf = encodedString("abcdef");
      try {
        assertThrows(DecoderException.class, () -> McString.read(buf, 5));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should advance reader index by VarInt prefix + byte length")
    void advancesReaderIndex() {
      ByteBuf buf = Unpooled.buffer();
      try {
        byte[] bytes = "Hi".getBytes(StandardCharsets.UTF_8);
        VarInt.write(buf, bytes.length);
        buf.writeBytes(bytes);
        buf.writeByte(0xAA); // sentinel

        assertEquals("Hi", McString.read(buf));
        assertEquals(3, buf.readerIndex()); // 1 (VarInt) + 2 (data)
        assertEquals((byte) 0xAA, buf.readByte());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should accept string at exactly the maximum character limit")
    void atExactLimit() {
      ByteBuf buf = encodedString("abc");
      try {
        assertEquals("abc", McString.read(buf, 3));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should throw on empty buffer")
    void emptyBuffer() {
      ByteBuf buf = Unpooled.buffer(0);
      try {
        assertThrows(DecoderException.class, () -> McString.read(buf));
      } finally {
        buf.release();
      }
    }

    /** Encodes a string in the Minecraft wire format: VarInt byte-length + UTF-8 payload. */
    private ByteBuf encodedString(String str) {
      ByteBuf buf = Unpooled.buffer();
      byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
      VarInt.write(buf, bytes.length);
      buf.writeBytes(bytes);
      return buf;
    }
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("write")
  class Write {

    @Test
    @DisplayName("should encode empty string")
    void emptyString() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, "");
        assertEquals(0, VarInt.read(buf)); // 0 bytes
        assertEquals(0, buf.readableBytes());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should encode ASCII string with correct VarInt prefix")
    void asciiString() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, "Hello");
        int byteCount = VarInt.read(buf);
        assertEquals(5, byteCount);
        String decoded = buf.toString(buf.readerIndex(), byteCount, StandardCharsets.UTF_8);
        assertEquals("Hello", decoded);
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should encode multibyte UTF-8 string")
    void multibyteUtf8() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, "日本語"); // 3 chars, 9 UTF-8 bytes
        int byteCount = VarInt.read(buf);
        assertEquals(9, byteCount);
        String decoded = buf.toString(buf.readerIndex(), byteCount, StandardCharsets.UTF_8);
        assertEquals("日本語", decoded);
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject string exceeding character limit")
    void charLimitExceeded() {
      ByteBuf buf = Unpooled.buffer();
      try {
        assertThrows(IllegalArgumentException.class, () -> McString.write(buf, "abcdef", 5));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should accept string at exactly the maximum character limit")
    void atExactLimit() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, "abc", 3); // should not throw
        VarInt.read(buf); // skip prefix
        assertEquals("abc", buf.toString(buf.readerIndex(), 3, StandardCharsets.UTF_8));
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Skip
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("skip")
  class Skip {

    @Test
    @DisplayName("should advance past empty string")
    void emptyString() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, 0);
        buf.writeByte(0xBB); // sentinel
        McString.skip(buf);
        assertEquals((byte) 0xBB, buf.readByte());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should advance past regular string without decoding")
    void regularString() {
      ByteBuf buf = Unpooled.buffer();
      try {
        byte[] bytes = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        VarInt.write(buf, bytes.length);
        buf.writeBytes(bytes);
        buf.writeByte(0xCC); // sentinel

        McString.skip(buf);
        assertEquals((byte) 0xCC, buf.readByte());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject negative byte length")
    void negativeLength() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, -1);
        assertThrows(DecoderException.class, () -> McString.skip(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject when buffer has insufficient data")
    void notEnoughData() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, 100); // claims 100 bytes
        buf.writeBytes(new byte[10]); // only 10
        assertThrows(DecoderException.class, () -> McString.skip(buf));
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Size
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("encodedSize")
  class EncodedSize {

    @Test
    @DisplayName("should return 1 for empty string")
    void emptyString() {
      // VarInt(0) = 1 byte, payload = 0 bytes
      assertEquals(1, McString.encodedSize(""));
    }

    @Test
    @DisplayName("should return correct size for ASCII string")
    void asciiString() {
      // "Hello" = 5 bytes, VarInt(5) = 1 byte → total 6
      assertEquals(6, McString.encodedSize("Hello"));
    }

    @Test
    @DisplayName("should return correct size for multibyte string")
    void multibyteString() {
      // "日本語" = 9 UTF-8 bytes, VarInt(9) = 1 byte → total 10
      assertEquals(10, McString.encodedSize("日本語"));
    }

    @Test
    @DisplayName("should account for multi-byte VarInt prefix on large strings")
    void largeString() {
      String str = "a".repeat(200);
      // 200 bytes, VarInt(200) = 2 bytes → total 202
      assertEquals(202, McString.encodedSize(str));
    }
  }

  // ---------------------------------------------------------------------------
  // Roundtrip
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("roundtrip")
  class Roundtrip {

    @Test
    @DisplayName("should preserve various strings through write-then-read cycle")
    void writeRead() {
      String[] testValues = {
        "",
        "Hello",
        "Héllo",
        "日本語",
        "\uD83C\uDFAE", // 🎮
        "Mixed: café \u2615 日本 \uD83C\uDFAE",
        "a".repeat(32_767), // max default length
      };

      for (String value : testValues) {
        ByteBuf buf = Unpooled.buffer();
        try {
          McString.write(buf, value);
          assertEquals(McString.encodedSize(value), buf.readableBytes());
          assertEquals(value, McString.read(buf));
          assertEquals(0, buf.readableBytes());
        } finally {
          buf.release();
        }
      }
    }

    @Test
    @DisplayName("should handle consecutive strings in the same buffer")
    void consecutive() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McString.write(buf, "first");
        McString.write(buf, "日本語");
        McString.write(buf, "");
        McString.write(buf, "last");

        assertEquals("first", McString.read(buf));
        assertEquals("日本語", McString.read(buf));
        assertEquals("", McString.read(buf));
        assertEquals("last", McString.read(buf));
        assertEquals(0, buf.readableBytes());
      } finally {
        buf.release();
      }
    }
  }
}
