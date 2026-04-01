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
package dev.warp.protocol.compress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JavaCompressor")
class JavaCompressorTest {

  // ---------------------------------------------------------------------------
  // Roundtrip (deflate → inflate)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("roundtrip")
  class Roundtrip {

    @Test
    @DisplayName("should roundtrip small data")
    void smallData() throws DataFormatException {
      byte[] original = {0x01, 0x02, 0x03, 0x04, 0x05};
      assertRoundtrip(original);
    }

    @Test
    @DisplayName("should roundtrip large repetitive data (compresses well)")
    void largeRepetitiveData() throws DataFormatException {
      byte[] original = new byte[10_000];
      for (int i = 0; i < original.length; i++) {
        original[i] = (byte) (i % 7);
      }
      assertRoundtrip(original);
    }

    @Test
    @DisplayName("should roundtrip random data (poor compression ratio)")
    void randomData() throws DataFormatException {
      byte[] original = new byte[4096];
      new Random(42).nextBytes(original);
      assertRoundtrip(original);
    }

    @Test
    @DisplayName("should roundtrip single byte")
    void singleByte() throws DataFormatException {
      assertRoundtrip(new byte[] {0x42});
    }

    @Test
    @DisplayName("should roundtrip at different compression levels")
    void compressionLevels() throws DataFormatException {
      byte[] original = new byte[2048];
      for (int i = 0; i < original.length; i++) {
        original[i] = (byte) (i % 13);
      }

      for (int level = 1; level <= 9; level++) {
        try (JavaCompressor compressor = new JavaCompressor(level)) {
          ByteBuf source = Unpooled.wrappedBuffer(original);
          ByteBuf compressed = Unpooled.buffer();
          ByteBuf decompressed = Unpooled.buffer();
          try {
            compressor.deflate(source, compressed);
            compressor.inflate(compressed, decompressed, original.length);

            byte[] result = new byte[decompressed.readableBytes()];
            decompressed.readBytes(result);
            assertArrayEquals(original, result, "Failed at compression level " + level);
          } finally {
            source.release();
            compressed.release();
            decompressed.release();
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Deflate
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("deflate")
  class Deflate {

    @Test
    @DisplayName("should produce smaller output for repetitive data")
    void compressesRepetitiveData() throws DataFormatException {
      byte[] original = new byte[1000];
      // All zeros — maximally compressible
      try (JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION)) {
        ByteBuf source = Unpooled.wrappedBuffer(original);
        ByteBuf compressed = Unpooled.buffer();
        try {
          compressor.deflate(source, compressed);
          assertTrue(
              compressed.readableBytes() < original.length,
              "Compressed size " + compressed.readableBytes() + " should be < " + original.length);
        } finally {
          source.release();
          compressed.release();
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Inflate validation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("inflate validation")
  class InflateValidation {

    @Test
    @DisplayName("should throw when decompressed data exceeds claimed size")
    void exceedsClaimedSize() throws DataFormatException {
      byte[] original = new byte[500];
      try (JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION)) {
        ByteBuf source = Unpooled.wrappedBuffer(original);
        ByteBuf compressed = Unpooled.buffer();
        compressor.deflate(source, compressed);
        source.release();

        // Try to inflate with a smaller claimed size
        ByteBuf decompressed = Unpooled.buffer();
        try {
          assertThrows(
              DataFormatException.class, () -> compressor.inflate(compressed, decompressed, 100));
        } finally {
          compressed.release();
          decompressed.release();
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("should allow multiple close calls (idempotent)")
    void closeIdempotent() {
      JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
      assertDoesNotThrow(compressor::close);
      assertDoesNotThrow(compressor::close);
      assertDoesNotThrow(compressor::close);
    }

    @Test
    @DisplayName("should reject operations after close")
    void useAfterClose() {
      JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
      compressor.close();

      ByteBuf source = Unpooled.wrappedBuffer(new byte[] {0x01});
      ByteBuf dest = Unpooled.buffer();
      try {
        assertThrows(IllegalStateException.class, () -> compressor.deflate(source, dest));
        assertThrows(IllegalStateException.class, () -> compressor.inflate(source, dest, 1));
      } finally {
        source.release();
        dest.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void assertRoundtrip(byte[] original) throws DataFormatException {
    try (JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION)) {
      ByteBuf source = Unpooled.wrappedBuffer(original);
      ByteBuf compressed = Unpooled.buffer();
      ByteBuf decompressed = Unpooled.buffer();
      try {
        compressor.deflate(source, compressed);
        compressor.inflate(compressed, decompressed, original.length);

        assertEquals(original.length, decompressed.readableBytes());
        byte[] result = new byte[decompressed.readableBytes()];
        decompressed.readBytes(result);
        assertArrayEquals(original, result);
      } finally {
        source.release();
        compressed.release();
        decompressed.release();
      }
    }
  }
}
