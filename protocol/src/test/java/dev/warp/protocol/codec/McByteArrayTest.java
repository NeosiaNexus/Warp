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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("McByteArray codec")
class McByteArrayTest {

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("read")
  class Read {

    @Test
    @DisplayName("should decode empty array")
    void emptyArray() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, 0);
        byte[] result = McByteArray.read(buf);
        assertEquals(0, result.length);
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should decode small array")
    void smallArray() {
      byte[] expected = {1, 2, 3, 4, 5};
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, expected.length);
        buf.writeBytes(expected);
        assertArrayEquals(expected, McByteArray.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject negative length")
    void negativeLength() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, -1);
        assertThrows(DecoderException.class, () -> McByteArray.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject array exceeding size limit")
    void tooLarge() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, 100);
        buf.writeBytes(new byte[100]); // enough data
        assertThrows(DecoderException.class, () -> McByteArray.read(buf, 50)); // max 50
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
        assertThrows(DecoderException.class, () -> McByteArray.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should accept array at exactly the size limit")
    void atExactLimit() {
      byte[] data = new byte[50];
      data[0] = 42;
      data[49] = 99;
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, data.length);
        buf.writeBytes(data);
        assertArrayEquals(data, McByteArray.read(buf, 50));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should advance reader index by VarInt prefix + array length")
    void advancesReaderIndex() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, 3);
        buf.writeBytes(new byte[] {10, 20, 30});
        buf.writeByte(0xAA); // sentinel

        McByteArray.read(buf);
        assertEquals(4, buf.readerIndex()); // 1 (VarInt) + 3 (data)
        assertEquals((byte) 0xAA, buf.readByte());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("write")
  class Write {

    @Test
    @DisplayName("should encode empty array")
    void emptyArray() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McByteArray.write(buf, new byte[0]);
        assertEquals(0, VarInt.read(buf));
        assertEquals(0, buf.readableBytes());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should encode array with correct VarInt prefix")
    void withPrefix() {
      byte[] data = {1, 2, 3, 4, 5};
      ByteBuf buf = Unpooled.buffer();
      try {
        McByteArray.write(buf, data);
        assertEquals(5, VarInt.read(buf));
        for (byte b : data) {
          assertEquals(b, buf.readByte());
        }
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
    @DisplayName("should advance past empty array")
    void emptyArray() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, 0);
        buf.writeByte(0xBB); // sentinel
        McByteArray.skip(buf);
        assertEquals((byte) 0xBB, buf.readByte());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should advance past array without reading into memory")
    void regularArray() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, 10);
        buf.writeBytes(new byte[10]);
        buf.writeByte(0xCC); // sentinel
        McByteArray.skip(buf);
        assertEquals((byte) 0xCC, buf.readByte());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject negative length")
    void negativeLength() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, -1);
        assertThrows(DecoderException.class, () -> McByteArray.skip(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject when buffer has insufficient data")
    void notEnoughData() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, 100);
        buf.writeBytes(new byte[10]);
        assertThrows(DecoderException.class, () -> McByteArray.skip(buf));
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
    @DisplayName("should return 1 for empty array")
    void emptyArray() {
      // VarInt(0) = 1 byte
      assertEquals(1, McByteArray.encodedSize(new byte[0]));
    }

    @Test
    @DisplayName("should return correct size for small array")
    void smallArray() {
      // VarInt(5) = 1 byte, payload = 5 bytes → total 6
      assertEquals(6, McByteArray.encodedSize(new byte[5]));
    }

    @Test
    @DisplayName("should account for multi-byte VarInt prefix")
    void largeArray() {
      // VarInt(200) = 2 bytes, payload = 200 bytes → total 202
      assertEquals(202, McByteArray.encodedSize(new byte[200]));
    }
  }

  // ---------------------------------------------------------------------------
  // Short-prefixed (1.7.x)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("readShortPrefixed / writeShortPrefixed")
  class ShortPrefixed {

    @Test
    @DisplayName("should roundtrip short-prefixed byte array")
    void roundtrip() {
      byte[] data = {0x01, 0x02, 0x03, 0x04};
      ByteBuf buf = Unpooled.buffer();
      try {
        McByteArray.writeShortPrefixed(buf, data);
        assertEquals(2 + 4, buf.readableBytes()); // short prefix (2) + data (4)
        assertArrayEquals(data, McByteArray.readShortPrefixed(buf, 256));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject oversized short-prefixed array")
    void oversized() {
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeShort(100); // claim 100 bytes
        buf.writeZero(100);
        assertThrows(DecoderException.class, () -> McByteArray.readShortPrefixed(buf, 10));
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Roundtrip
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("roundtrip")
  class Roundtrip {

    @Test
    @DisplayName("should preserve byte arrays through write-then-read cycle")
    void writeRead() {
      byte[][] testValues = {
        new byte[0], {1, 2, 3}, {0, (byte) 0xFF, Byte.MIN_VALUE, Byte.MAX_VALUE}, new byte[1000],
      };

      for (byte[] value : testValues) {
        ByteBuf buf = Unpooled.buffer();
        try {
          McByteArray.write(buf, value);
          assertEquals(McByteArray.encodedSize(value), buf.readableBytes());
          assertArrayEquals(value, McByteArray.read(buf));
          assertEquals(0, buf.readableBytes());
        } finally {
          buf.release();
        }
      }
    }

    @Test
    @DisplayName("should handle consecutive byte arrays in the same buffer")
    void consecutive() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McByteArray.write(buf, new byte[] {1, 2, 3});
        McByteArray.write(buf, new byte[0]);
        McByteArray.write(buf, new byte[] {99});

        assertArrayEquals(new byte[] {1, 2, 3}, McByteArray.read(buf));
        assertArrayEquals(new byte[0], McByteArray.read(buf));
        assertArrayEquals(new byte[] {99}, McByteArray.read(buf));
        assertEquals(0, buf.readableBytes());
      } finally {
        buf.release();
      }
    }
  }
}
