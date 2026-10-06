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
import java.util.stream.LongStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("VarLong codec")
class VarLongTest {

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("read")
  class Read {

    @Test
    @DisplayName("should decode single-byte values (0–127)")
    void singleByte() {
      assertReads(0L, 0x00);
      assertReads(1L, 0x01);
      assertReads(127L, 0x7F);
    }

    @Test
    @DisplayName("should decode two-byte values (128–16383)")
    void twoByte() {
      assertReads(128L, 0x80, 0x01);
      assertReads(255L, 0xFF, 0x01);
      assertReads(16383L, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should decode three-byte values")
    void threeByte() {
      assertReads(16384L, 0x80, 0x80, 0x01);
      assertReads(2097151L, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should decode four-byte values")
    void fourByte() {
      assertReads(2097152L, 0x80, 0x80, 0x80, 0x01); // 2^21
      assertReads(268435455L, 0xFF, 0xFF, 0xFF, 0x7F); // 2^28 - 1
    }

    @Test
    @DisplayName("should decode five-byte values spanning int range")
    void fiveByte() {
      assertReads((long) Integer.MAX_VALUE, 0xFF, 0xFF, 0xFF, 0xFF, 0x07);
      assertReads(2147483648L, 0x80, 0x80, 0x80, 0x80, 0x08);
    }

    @Test
    @DisplayName("should decode six-byte values")
    void sixByte() {
      // 2^35 (lower bound)
      assertReads(34359738368L, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01);
      // 2^42 - 1 (upper bound)
      assertReads(4398046511103L, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should decode seven-byte values")
    void sevenByte() {
      // 2^42 (lower bound)
      assertReads(4398046511104L, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01);
      // 2^49 - 1 (upper bound)
      assertReads(562949953421311L, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should decode eight-byte values")
    void eightByte() {
      // 2^49 (lower bound)
      assertReads(562949953421312L, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01);
      // 2^56 - 1 (upper bound)
      assertReads(72057594037927935L, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should decode nine-byte values (Long.MAX_VALUE)")
    void nineByte() {
      assertReads(Long.MAX_VALUE, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should decode ten-byte values (negative longs)")
    void tenByte() {
      assertReads(-1L, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01);
      assertReads(Long.MIN_VALUE, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01);
    }

    @Test
    @DisplayName("should reject VarLongs exceeding 10 bytes")
    void tooManyBytes() {
      ByteBuf buf = Unpooled.buffer();
      try {
        // 10 continuation bytes — no terminating byte
        for (int i = 0; i < 10; i++) {
          buf.writeByte(0x80);
        }
        assertThrows(DecoderException.class, () -> VarLong.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should advance reader index by exact VarLong length")
    void advancesReaderIndex() {
      ByteBuf buf = Unpooled.buffer();
      try {
        // 2-byte VarLong followed by 1-byte VarLong
        buf.writeByte(0x80).writeByte(0x01); // 128
        buf.writeByte(0x05); // 5

        assertEquals(128L, VarLong.read(buf));
        assertEquals(2, buf.readerIndex());

        assertEquals(5L, VarLong.read(buf));
        assertEquals(3, buf.readerIndex());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should decode correctly via safe path (limited readable bytes)")
    void safePath() {
      // These buffers have < 10 readable bytes, forcing the safe path
      assertReads(128L, 0x80, 0x01);
      assertReads(0L, 0x00);
      assertReads(16384L, 0x80, 0x80, 0x01);
    }

    @Test
    @DisplayName("should throw on truncated VarLong via safe path")
    void truncated() {
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeByte(0x80);
        assertThrows(DecoderException.class, () -> VarLong.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should throw on empty buffer")
    void emptyBuffer() {
      ByteBuf buf = Unpooled.buffer(0);
      try {
        assertThrows(DecoderException.class, () -> VarLong.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should not advance reader index on error")
    void readerIndexUnchangedOnError() {
      ByteBuf buf = Unpooled.buffer();
      try {
        for (int i = 0; i < 10; i++) {
          buf.writeByte(0x80);
        }
        int before = buf.readerIndex();
        assertThrows(DecoderException.class, () -> VarLong.read(buf));
        assertEquals(before, buf.readerIndex());
      } finally {
        buf.release();
      }
    }

    private void assertReads(long expected, int... bytes) {
      ByteBuf buf = Unpooled.buffer(bytes.length);
      try {
        for (int b : bytes) {
          buf.writeByte(b);
        }
        int startIndex = buf.readerIndex();
        assertEquals(expected, VarLong.read(buf));
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
      assertWrites(0L, 0x00);
      assertWrites(1L, 0x01);
      assertWrites(127L, 0x7F);
    }

    @Test
    @DisplayName("should encode two-byte values")
    void twoByte() {
      assertWrites(128L, 0x80, 0x01);
      assertWrites(255L, 0xFF, 0x01);
      assertWrites(16383L, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should encode three-byte values")
    void threeByte() {
      assertWrites(16384L, 0x80, 0x80, 0x01);
      assertWrites(2097151L, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should encode four-byte values")
    void fourByte() {
      assertWrites(2097152L, 0x80, 0x80, 0x80, 0x01);
      assertWrites(268435455L, 0xFF, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should encode five-byte values spanning int range")
    void fiveByte() {
      assertWrites((long) Integer.MAX_VALUE, 0xFF, 0xFF, 0xFF, 0xFF, 0x07);
      assertWrites(2147483648L, 0x80, 0x80, 0x80, 0x80, 0x08);
    }

    @Test
    @DisplayName("should encode six-byte values")
    void sixByte() {
      assertWrites(34359738368L, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01); // 2^35
      assertWrites(4398046511103L, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F); // 2^42 - 1
    }

    @Test
    @DisplayName("should encode seven-byte values")
    void sevenByte() {
      assertWrites(4398046511104L, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01); // 2^42
      assertWrites(562949953421311L, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F); // 2^49 - 1
    }

    @Test
    @DisplayName("should encode eight-byte values")
    void eightByte() {
      assertWrites(562949953421312L, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01); // 2^49
      assertWrites(72057594037927935L, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F); // 2^56 - 1
    }

    @Test
    @DisplayName("should encode nine-byte values (Long.MAX_VALUE)")
    void nineByte() {
      assertWrites(Long.MAX_VALUE, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("should encode ten-byte values (negative longs)")
    void tenByte() {
      assertWrites(-1L, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01);
      assertWrites(Long.MIN_VALUE, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01);
    }

    private void assertWrites(long value, int... expectedBytes) {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarLong.write(buf, value);
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
      assertEquals(1, VarLong.size(0L));
      assertEquals(1, VarLong.size(1L));
      assertEquals(1, VarLong.size(127L));
    }

    @Test
    @DisplayName("should return 2 for values 128–16383")
    void twoBytes() {
      assertEquals(2, VarLong.size(128L));
      assertEquals(2, VarLong.size(16383L));
    }

    @Test
    @DisplayName("should return 3 for values 16384–2097151")
    void threeBytes() {
      assertEquals(3, VarLong.size(16384L));
      assertEquals(3, VarLong.size(2097151L));
    }

    @Test
    @DisplayName("should return 4 for values 2097152–268435455")
    void fourBytes() {
      assertEquals(4, VarLong.size(2097152L));
      assertEquals(4, VarLong.size(268435455L));
    }

    @Test
    @DisplayName("should return 5 for values spanning int range")
    void fiveBytes() {
      assertEquals(5, VarLong.size((long) Integer.MAX_VALUE));
      assertEquals(5, VarLong.size(2147483648L));
    }

    @Test
    @DisplayName("should return 6 for values up to 2^42-1")
    void sixBytes() {
      assertEquals(6, VarLong.size(34359738368L)); // 2^35
      assertEquals(6, VarLong.size(4398046511103L)); // 2^42 - 1
    }

    @Test
    @DisplayName("should return 7 for values up to 2^49-1")
    void sevenBytes() {
      assertEquals(7, VarLong.size(4398046511104L)); // 2^42
      assertEquals(7, VarLong.size(562949953421311L)); // 2^49 - 1
    }

    @Test
    @DisplayName("should return 8 for values up to 2^56-1")
    void eightBytes() {
      assertEquals(8, VarLong.size(562949953421312L)); // 2^49
      assertEquals(8, VarLong.size(72057594037927935L)); // 2^56 - 1
    }

    @Test
    @DisplayName("should return 9 for Long.MAX_VALUE")
    void nineBytes() {
      assertEquals(9, VarLong.size(72057594037927936L)); // 2^56
      assertEquals(9, VarLong.size(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("should return 10 for negative values and Long.MIN_VALUE")
    void tenBytes() {
      assertEquals(10, VarLong.size(-1L));
      assertEquals(10, VarLong.size(Long.MIN_VALUE));
    }
  }

  // ---------------------------------------------------------------------------
  // Skip
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("skip")
  class Skip {

    @Test
    @DisplayName("should advance reader index past a 1-byte VarLong")
    void oneByte() {
      ByteBuf buf = Unpooled.buffer();
      try {
        buf.writeByte(0x01).writeByte(0xAA);
        VarLong.skip(buf);
        assertEquals(1, buf.readerIndex());
        assertEquals((byte) 0xAA, buf.readByte());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should advance reader index past a 10-byte VarLong")
    void tenBytes() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarLong.write(buf, -1L); // 10 bytes
        buf.writeByte(0x42); // sentinel
        VarLong.skip(buf);
        assertEquals(10, buf.readerIndex());
        assertEquals((byte) 0x42, buf.readByte());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject VarLongs exceeding 10 bytes")
    void tooManyBytes() {
      ByteBuf buf = Unpooled.buffer();
      try {
        for (int i = 0; i < 11; i++) {
          buf.writeByte(0x80);
        }
        assertThrows(DecoderException.class, () -> VarLong.skip(buf));
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
      long[] testValues = {
        0L,
        1L,
        127L,
        128L,
        255L,
        16383L,
        16384L,
        2097151L,
        2097152L,
        268435455L,
        (long) Integer.MAX_VALUE,
        2147483648L,
        34359738367L, // 2^35 - 1 (6 bytes)
        4398046511103L, // 2^42 - 1 (7 bytes)
        562949953421311L, // 2^49 - 1 (8 bytes)
        Long.MAX_VALUE,
        -1L,
        -128L,
        Long.MIN_VALUE
      };

      for (long value : testValues) {
        ByteBuf buf = Unpooled.buffer();
        try {
          VarLong.write(buf, value);
          assertEquals(VarLong.size(value), buf.readableBytes());
          assertEquals(value, VarLong.read(buf));
          assertEquals(0, buf.readableBytes());
        } finally {
          buf.release();
        }
      }
    }

    @Test
    @DisplayName("should handle consecutive VarLongs in the same buffer")
    void consecutive() {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarLong.write(buf, 0L);
        VarLong.write(buf, 300L);
        VarLong.write(buf, -1L);
        VarLong.write(buf, Long.MAX_VALUE);

        assertEquals(0L, VarLong.read(buf));
        assertEquals(300L, VarLong.read(buf));
        assertEquals(-1L, VarLong.read(buf));
        assertEquals(Long.MAX_VALUE, VarLong.read(buf));
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

    /** Bytes already read before the VarLong, continuation bits set. */
    private static final byte[] PREFIX = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    /** Both sides of every 7-bit group boundary, the extremes, and alternating bit patterns. */
    static LongStream values() {
      LongStream boundaries =
          LongStream.range(0, 64).flatMap(bit -> LongStream.of(1L << bit, (1L << bit) - 1));
      return LongStream.concat(
              boundaries,
              LongStream.of(
                  -1, 0x5555_5555_5555_5555L, 0xAAAA_AAAA_AAAA_AAAAL, 0x0F0F_0F0F_0F0F_0F0FL))
          .distinct();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    @DisplayName("should write the canonical encoding, as long as size() says")
    void writesCanonicalEncoding(long value) {
      ByteBuf buf = Unpooled.buffer();
      try {
        VarLong.write(buf, value);

        assertArrayEquals(leb128(value), ByteBufUtil.getBytes(buf));
        assertEquals(VarLong.size(value), buf.readableBytes());
      } finally {
        buf.release();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    @DisplayName("should read and skip exactly the encoding, whether more bytes follow or not")
    void readsAndSkips(long value) {
      assertReadsAndSkips(value, leb128(value));
    }

    @Test
    @DisplayName("should read all-zero and all-one groups, overlong too, and reject their prefixes")
    void readsEveryGroupPattern() {
      for (int length = 1; length <= VarLong.MAX_BYTES; length++) {
        for (int ones = 0; ones < 1 << length; ones++) {
          byte[] encoded = new byte[length];
          long value = 0;
          for (int group = 0; group < length; group++) {
            int bits = ((ones >>> group) & 1) == 0 ? 0 : 0x7F;
            encoded[group] = (byte) (group < length - 1 ? bits | 0x80 : bits);
            value |= (long) bits << (7 * group);
          }
          assertReadsAndSkips(value, encoded);
          assertRejectsTruncations(encoded);
        }
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    @DisplayName("should reject every truncation without reading past the writer index")
    void rejectsTruncations(long value) {
      assertRejectsTruncations(leb128(value));
    }

    /**
     * Asserts that {@code encoded} reads as {@code value} and that both reading and skipping it
     * consume exactly its bytes: when it ends the readable bytes, and when continuation bytes
     * follow it (enough for the fast path; reading one of them would change the result).
     */
    private static void assertReadsAndSkips(long value, byte[] encoded) {
      byte[] trailer = new byte[VarLong.MAX_BYTES];
      Arrays.fill(trailer, (byte) 0x80);
      for (byte[] following : List.of(new byte[0], trailer)) {
        ByteBuf buf = buffer(encoded, encoded.length, following);
        String description = HexFormat.of().formatHex(encoded) + " + " + following.length;
        try {
          assertEquals(value, VarLong.read(buf), description);
          assertEquals(PREFIX.length + encoded.length, buf.readerIndex(), description);

          buf.readerIndex(PREFIX.length);
          VarLong.skip(buf);
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
          assertThrows(DecoderException.class, () -> VarLong.read(buf), description);
          assertEquals(PREFIX.length, buf.readerIndex(), description);
          assertThrows(DecoderException.class, () -> VarLong.skip(buf), description);
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
    private static byte[] leb128(long value) {
      ByteArrayOutputStream out = new ByteArrayOutputStream(VarLong.MAX_BYTES);
      long rest = value;
      while ((rest & ~0x7FL) != 0) {
        out.write((int) (rest & 0x7F) | 0x80);
        rest >>>= 7;
      }
      out.write((int) rest);
      return out.toByteArray();
    }
  }
}
