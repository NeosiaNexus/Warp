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

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("VarInt codec")
class VarIntTest {

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("read")
  class Read {

    @Test
    @DisplayName("should decode single-byte values (0–127)")
    void singleByte() {
      assertReads(0, 0x00);
      assertReads(1, 0x01);
      assertReads(2, 0x02);
      assertReads(127, 0x7F);
    }

    @Test
    @DisplayName("should decode two-byte values (128–16383)")
    void twoByte() {
      assertReads(128, 0x80, 0x01);
      assertReads(255, 0xFF, 0x01);
      assertReads(300, 0xAC, 0x02);
      assertReads(16383, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should decode three-byte values (16384–2097151)")
    void threeByte() {
      assertReads(16384, 0x80, 0x80, 0x01);
      assertReads(2097151, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should decode four-byte values (2097152–268435455)")
    void fourByte() {
      assertReads(2097152, 0x80, 0x80, 0x80, 0x01);
      assertReads(268435455, 0xFF, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should decode five-byte values including negative integers")
    void fiveByte() {
      assertReads(268435456, 0x80, 0x80, 0x80, 0x80, 0x01);
      assertReads(Integer.MAX_VALUE, 0xFF, 0xFF, 0xFF, 0xFF, 0x07);
      assertReads(-1, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F);
      assertReads(Integer.MIN_VALUE, 0x80, 0x80, 0x80, 0x80, 0x08);
    }

    @Test
    @DisplayName("should reject VarInts exceeding 5 bytes")
    void tooManyBytes() {
      ByteBuf buf = Unpooled.buffer();
      try {
        // 5 continuation bytes — no terminating byte
        buf.writeByte(0x80).writeByte(0x80).writeByte(0x80).writeByte(0x80).writeByte(0x80);
        assertThrows(DecoderException.class, () -> VarInt.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should advance reader index by exact VarInt length")
    void advancesReaderIndex() {
      ByteBuf buf = Unpooled.buffer();
      try {
        // Write a 2-byte VarInt followed by a 1-byte VarInt
        buf.writeByte(0x80).writeByte(0x01); // 128
        buf.writeByte(0x05); // 5

        assertEquals(128, VarInt.read(buf));
        assertEquals(2, buf.readerIndex());

        assertEquals(5, VarInt.read(buf));
        assertEquals(3, buf.readerIndex());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should decode correctly via safe path (limited readable bytes)")
    void safePath() {
      // Buffer with exactly 2 bytes — forces safe path (< 5 readable)
      assertReads(128, 0x80, 0x01);
      assertReads(0, 0x00);
      assertReads(16384, 0x80, 0x80, 0x01);
    }

    @Test
    @DisplayName("should throw on truncated VarInt via safe path")
    void truncated() {
      ByteBuf buf = Unpooled.buffer();
      try {
        // Continuation byte but no following byte
        buf.writeByte(0x80);
        assertThrows(DecoderException.class, () -> VarInt.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should throw on empty buffer")
    void emptyBuffer() {
      ByteBuf buf = Unpooled.buffer(0);
      try {
        assertThrows(DecoderException.class, () -> VarInt.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should not advance reader index on error")
    void readerIndexUnchangedOnError() {
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeByte(0x80).writeByte(0x80).writeByte(0x80).writeByte(0x80).writeByte(0x80);
        int before = buf.readerIndex();
        assertThrows(DecoderException.class, () -> VarInt.read(buf));
        assertEquals(before, buf.readerIndex());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should accept overlong 5th byte (non-canonical but spec-valid)")
    void overlongFifthByte() {
      // 5th byte = 0x7F (only low 4 bits are meaningful for 32-bit value)
      // The excess bits (4-6) are silently discarded, matching Protobuf behavior
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeByte(0xFF).writeByte(0xFF).writeByte(0xFF).writeByte(0xFF).writeByte(0x7F);
        int value = VarInt.read(buf);
        // Only bits 0-3 of the 5th byte contribute: 0x0F << 28 = 0xF0000000
        // Combined with bytes 0-3 (all 0x7F data bits): 0x0FFFFFFF | 0xF0000000 = 0xFFFFFFFF = -1
        assertEquals(-1, value);
      } finally {
        buf.release();
      }
    }

    private void assertReads(int expected, int... bytes) {
      ByteBuf buf = Unpooled.buffer(bytes.length);
      try {
        for (int b : bytes) {
          buf.writeByte(b);
        }
        int startIndex = buf.readerIndex();
        assertEquals(expected, VarInt.read(buf));
        assertEquals(bytes.length, buf.readerIndex() - startIndex);
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
    @DisplayName("should encode single-byte values")
    void singleByte() {
      assertWrites(0, 0x00);
      assertWrites(1, 0x01);
      assertWrites(127, 0x7F);
    }

    @Test
    @DisplayName("should encode two-byte values")
    void twoByte() {
      assertWrites(128, 0x80, 0x01);
      assertWrites(255, 0xFF, 0x01);
      assertWrites(300, 0xAC, 0x02);
      assertWrites(16383, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should encode three-byte values")
    void threeByte() {
      assertWrites(16384, 0x80, 0x80, 0x01);
      assertWrites(2097151, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should encode four-byte values")
    void fourByte() {
      assertWrites(2097152, 0x80, 0x80, 0x80, 0x01);
      assertWrites(268435455, 0xFF, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should encode five-byte values including negative integers")
    void fiveByte() {
      assertWrites(268435456, 0x80, 0x80, 0x80, 0x80, 0x01);
      assertWrites(Integer.MAX_VALUE, 0xFF, 0xFF, 0xFF, 0xFF, 0x07);
      assertWrites(-1, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F);
      assertWrites(Integer.MIN_VALUE, 0x80, 0x80, 0x80, 0x80, 0x08);
    }

    private void assertWrites(int value, int... expectedBytes) {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, value);
        assertEquals(expectedBytes.length, buf.readableBytes());
        for (int expected : expectedBytes) {
          assertEquals((byte) expected, buf.readByte());
        }
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Size
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("size")
  class Size {

    @Test
    @DisplayName("should return 1 for values 0–127")
    void oneByte() {
      assertEquals(1, VarInt.size(0));
      assertEquals(1, VarInt.size(1));
      assertEquals(1, VarInt.size(127));
    }

    @Test
    @DisplayName("should return 2 for values 128–16383")
    void twoBytes() {
      assertEquals(2, VarInt.size(128));
      assertEquals(2, VarInt.size(16383));
    }

    @Test
    @DisplayName("should return 3 for values 16384–2097151")
    void threeBytes() {
      assertEquals(3, VarInt.size(16384));
      assertEquals(3, VarInt.size(2097151));
    }

    @Test
    @DisplayName("should return 4 for values 2097152–268435455")
    void fourBytes() {
      assertEquals(4, VarInt.size(2097152));
      assertEquals(4, VarInt.size(268435455));
    }

    @Test
    @DisplayName("should return 5 for values >= 268435456 and all negatives")
    void fiveBytes() {
      assertEquals(5, VarInt.size(268435456));
      assertEquals(5, VarInt.size(Integer.MAX_VALUE));
      assertEquals(5, VarInt.size(-1));
      assertEquals(5, VarInt.size(Integer.MIN_VALUE));
    }
  }

  // ---------------------------------------------------------------------------
  // encode21Bit
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("encode21Bit")
  class Encode21Bit {

    @Test
    @DisplayName("should encode zero as 3-byte VarInt")
    void zero() {
      int encoded = VarInt.encode21Bit(0);
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeMedium(encoded);
        buf.readerIndex(0);
        assertEquals(0, VarInt.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should encode max 21-bit value")
    void maxValue() {
      int encoded = VarInt.encode21Bit(VarInt.MAX_21_BIT);
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeMedium(encoded);
        buf.readerIndex(0);
        assertEquals(VarInt.MAX_21_BIT, VarInt.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should encode mid-range values decodable as VarInt")
    void midRange() {
      int[] testValues = {1, 127, 128, 16384, 100000, 1000000};
      for (int value : testValues) {
        int encoded = VarInt.encode21Bit(value);
        ByteBuf buf = Unpooled.buffer();
        try {
          buf.writeMedium(encoded);
          buf.readerIndex(0);
          assertEquals(value, VarInt.read(buf));
        } finally {
          buf.release();
        }
      }
    }

    @Test
    @DisplayName("should reject negative values")
    void negativeValue() {
      assertThrows(IllegalArgumentException.class, () -> VarInt.encode21Bit(-1));
    }

    @Test
    @DisplayName("should reject values exceeding 21 bits")
    void tooLarge() {
      assertThrows(IllegalArgumentException.class, () -> VarInt.encode21Bit(VarInt.MAX_21_BIT + 1));
    }
  }

  // ---------------------------------------------------------------------------
  // Skip
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("skip")
  class Skip {

    @Test
    @DisplayName("should advance reader index past a 1-byte VarInt")
    void oneByte() {
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeByte(0x01).writeByte(0xAA); // VarInt(1), then sentinel
        VarInt.skip(buf);
        assertEquals(1, buf.readerIndex());
        assertEquals((byte) 0xAA, buf.readByte()); // sentinel intact
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should advance reader index past a 5-byte VarInt")
    void fiveBytes() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, -1); // 5 bytes
        buf.writeByte(0x42); // sentinel
        VarInt.skip(buf);
        assertEquals(5, buf.readerIndex());
        assertEquals((byte) 0x42, buf.readByte());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject VarInts exceeding 5 bytes")
    void tooManyBytes() {
      ByteBuf buf = Unpooled.buffer();
      try {
        for (int i = 0; i < 6; i++) {
          buf.writeByte(0x80);
        }
        assertThrows(DecoderException.class, () -> VarInt.skip(buf));
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
    @DisplayName("should preserve value through write-then-read cycle")
    void writeRead() {
      int[] testValues = {
        0,
        1,
        127,
        128,
        255,
        300,
        16383,
        16384,
        2097151,
        2097152,
        268435455,
        268435456,
        Integer.MAX_VALUE,
        -1,
        -128,
        Integer.MIN_VALUE
      };

      for (int value : testValues) {
        ByteBuf buf = Unpooled.buffer();
        try {
          VarInt.write(buf, value);
          assertEquals(VarInt.size(value), buf.readableBytes());
          assertEquals(value, VarInt.read(buf));
          assertEquals(0, buf.readableBytes());
        } finally {
          buf.release();
        }
      }
    }

    @Test
    @DisplayName("should handle consecutive VarInts in the same buffer")
    void consecutive() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, 0);
        VarInt.write(buf, 300);
        VarInt.write(buf, -1);
        VarInt.write(buf, Integer.MAX_VALUE);

        assertEquals(0, VarInt.read(buf));
        assertEquals(300, VarInt.read(buf));
        assertEquals(-1, VarInt.read(buf));
        assertEquals(Integer.MAX_VALUE, VarInt.read(buf));
        assertEquals(0, buf.readableBytes());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Every encoded length, through both read paths
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("every encoded length")
  class EveryLength {

    /** Bytes already read before the VarInt, continuation bits set. */
    private static final byte[] PREFIX = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    /** Both sides of every 7-bit group boundary, the extremes, and alternating bit patterns. */
    static IntStream values() {
      IntStream boundaries =
          IntStream.range(0, 32).flatMap(bit -> IntStream.of(1 << bit, (1 << bit) - 1));
      return IntStream.concat(boundaries, IntStream.of(-1, 0x5555_5555, 0xAAAA_AAAA, 0x0F0F_0F0F))
          .distinct();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    @DisplayName("should write the canonical encoding, as long as size() says")
    void writesCanonicalEncoding(int value) {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarInt.write(buf, value);

        assertArrayEquals(leb128(value), ByteBufUtil.getBytes(buf));
        assertEquals(VarInt.size(value), buf.readableBytes());
      } finally {
        buf.release();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    @DisplayName("should read and skip exactly the encoding, whether more bytes follow or not")
    void readsAndSkips(int value) {
      assertReadsAndSkips(value, leb128(value));
    }

    @Test
    @DisplayName("should read all-zero and all-one groups, overlong too, and reject their prefixes")
    void readsEveryGroupPattern() {
      for (int length = 1; length <= VarInt.MAX_BYTES; length++) {
        for (int ones = 0; ones < 1 << length; ones++) {
          byte[] encoded = new byte[length];
          int value = 0;
          for (int group = 0; group < length; group++) {
            int bits = ((ones >>> group) & 1) == 0 ? 0 : 0x7F;
            encoded[group] = (byte) (group < length - 1 ? bits | 0x80 : bits);
            value |= bits << (7 * group);
          }
          assertReadsAndSkips(value, encoded);
          assertRejectsTruncations(encoded);
        }
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    @DisplayName("should reject every truncation without reading past the writer index")
    void rejectsTruncations(int value) {
      assertRejectsTruncations(leb128(value));
    }

    /**
     * Asserts that {@code encoded} reads as {@code value} and that both reading and skipping it
     * consume exactly its bytes: when it ends the readable bytes, and when continuation bytes
     * follow it (enough for the fast path; reading one of them would change the result).
     */
    private static void assertReadsAndSkips(int value, byte[] encoded) {
      byte[] trailer = new byte[VarInt.MAX_BYTES];
      Arrays.fill(trailer, (byte) 0x80);
      for (byte[] following : List.of(new byte[0], trailer)) {
        ByteBuf buf = buffer(encoded, encoded.length, following);
        String description = HexFormat.of().formatHex(encoded) + " + " + following.length;
        try {
          assertEquals(value, VarInt.read(buf), description);
          assertEquals(PREFIX.length + encoded.length, buf.readerIndex(), description);

          buf.readerIndex(PREFIX.length);
          VarInt.skip(buf);
          assertEquals(PREFIX.length + encoded.length, buf.readerIndex(), description);
        } finally {
          buf.release();
        }
      }
    }

    /**
     * Asserts that reading or skipping any proper prefix of {@code encoded} fails, leaving the
     * reader index alone, although the missing bytes sit right after the writer index.
     */
    private static void assertRejectsTruncations(byte[] encoded) {
      for (int length = 0; length < encoded.length; length++) {
        ByteBuf buf = buffer(encoded, length);
        String description = HexFormat.of().formatHex(encoded) + " cut to " + length;
        try {
          assertThrows(DecoderException.class, () -> VarInt.read(buf), description);
          assertEquals(PREFIX.length, buf.readerIndex(), description);
          assertThrows(DecoderException.class, () -> VarInt.skip(buf), description);
          assertEquals(PREFIX.length, buf.readerIndex(), description);
        } finally {
          buf.release();
        }
      }
    }

    /**
     * A buffer whose readable bytes are the first {@code readable} bytes of {@code encoded}, then
     * {@code trailer}; the rest of {@code encoded} follows past the writer index.
     */
    private static ByteBuf buffer(byte[] encoded, int readable, byte... trailer) {
      ByteBuf buf = Unpooled.buffer().writeBytes(PREFIX);
      buf.writeBytes(encoded, 0, readable).writeBytes(trailer);
      int writerIndex = buf.writerIndex();
      buf.writeBytes(encoded, readable, encoded.length - readable);
      buf.writerIndex(writerIndex);
      buf.readerIndex(PREFIX.length);
      return buf;
    }

    /** Reference LEB128 encoder: seven bits per byte, least significant group first. */
    private static byte[] leb128(int value) {
      ByteArrayOutputStream out = new ByteArrayOutputStream(VarInt.MAX_BYTES);
      int rest = value;
      while ((rest & ~0x7F) != 0) {
        out.write((rest & 0x7F) | 0x80);
        rest >>>= 7;
      }
      out.write(rest);
      return out.toByteArray();
    }
  }
}
