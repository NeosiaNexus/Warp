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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
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

    @Test
    @DisplayName("should not grow a destination that already has room for the output")
    void keepsDestinationWithRoom() throws DataFormatException {
      // given: a destination of exactly the bound, which cannot grow
      byte[] original = new byte[100];
      try (JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION)) {
        int bound = compressor.deflateBound(original.length);
        ByteBuf source = Unpooled.wrappedBuffer(original);
        ByteBuf compressed = Unpooled.buffer(bound, bound);
        ByteBuf decompressed = Unpooled.buffer();
        try {
          // when
          compressor.deflate(source, compressed);

          // then: nothing asked it to grow, and the output is intact
          assertEquals(bound, compressed.capacity());
          compressor.inflate(compressed, decompressed, original.length);
          assertArrayEquals(original, ByteBufUtil.getBytes(decompressed));
        } finally {
          source.release();
          compressed.release();
          decompressed.release();
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
    @DisplayName("should release each native stream exactly once, however often it is closed")
    void releasesStreamsOnce() throws DataFormatException {
      // given: a compressor whose streams count how often they are ended
      AtomicInteger deflaterEnds = new AtomicInteger();
      AtomicInteger inflaterEnds = new AtomicInteger();
      JavaCompressor compressor =
          new JavaCompressor(
              () ->
                  new Deflater(Deflater.DEFAULT_COMPRESSION) {
                    @Override
                    public void end() {
                      deflaterEnds.incrementAndGet();
                      super.end();
                    }
                  },
              () ->
                  new Inflater() {
                    @Override
                    public void end() {
                      inflaterEnds.incrementAndGet();
                      super.end();
                    }
                  });
      byte[] original = new byte[] {1, 2, 3, 4, 5};
      ByteBuf source = Unpooled.wrappedBuffer(original);
      ByteBuf compressed = Unpooled.buffer();
      ByteBuf decompressed = Unpooled.buffer();
      try {
        compressor.deflate(source, compressed);
        compressor.inflate(compressed, decompressed, original.length);
        assertEquals(0, deflaterEnds.get());
        assertEquals(0, inflaterEnds.get());

        // when
        compressor.close();
        compressor.close();

        // then
        assertEquals(1, deflaterEnds.get());
        assertEquals(1, inflaterEnds.get());
      } finally {
        source.release();
        compressed.release();
        decompressed.release();
      }
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
  // Bound
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("deflate bound")
  class Bound {

    @Test
    @DisplayName("should be zlib's deflateBound for its default window and memory settings")
    void zlibFormula() {
      try (JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION)) {
        assertEquals(13, compressor.deflateBound(0));
        assertEquals(4096 + 1 + 13, compressor.deflateBound(4096));
        assertEquals(16384 + 4 + 1 + 13, compressor.deflateBound(16384));
        assertEquals((1 << 25) + 8192 + 2048 + 1 + 13, compressor.deflateBound(1 << 25));
      }
    }

    @Test
    @DisplayName("should hold for incompressible data at every level")
    void holdsForRandomData() throws DataFormatException {
      Random random = new Random(42);
      for (int level = 0; level <= 9; level++) {
        for (int size : new int[] {1, 255, 4096, 70_000}) {
          // given
          byte[] noise = new byte[size];
          random.nextBytes(noise);
          ByteBuf compressed = Unpooled.buffer();

          // when
          try (JavaCompressor compressor = new JavaCompressor(level)) {
            compressor.deflate(Unpooled.wrappedBuffer(noise), compressed);

            // then
            assertTrue(
                compressed.readableBytes() <= compressor.deflateBound(size),
                "level " + level + ", " + size + " bytes");
          } finally {
            compressed.release();
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Buffers of several components
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("composite buffers")
  class CompositeBuffers {

    @Test
    @DisplayName("should deflate from and into buffers of several components")
    void deflatesThroughComposites() throws DataFormatException {
      byte[] original = repetitive(10_000);
      CompositeByteBuf source = composite(original);
      CompositeByteBuf compressed = emptyComposite();
      ByteBuf decompressed = Unpooled.buffer();
      try (JavaCompressor compressor = new JavaCompressor(6)) {
        compressor.deflate(source, compressed);

        compressor.inflate(compressed, decompressed, original.length);
        assertArrayEquals(original, ByteBufUtil.getBytes(decompressed));
      } finally {
        source.release();
        compressed.release();
        decompressed.release();
      }
    }

    @Test
    @DisplayName("should inflate from and into buffers of several components")
    void inflatesThroughComposites() throws DataFormatException {
      byte[] original = repetitive(10_000);
      ByteBuf compressed = Unpooled.buffer();
      try (JavaCompressor compressor = new JavaCompressor(6)) {
        compressor.deflate(Unpooled.wrappedBuffer(original), compressed);
        CompositeByteBuf source = composite(ByteBufUtil.getBytes(compressed));
        CompositeByteBuf decompressed = emptyComposite();
        try {
          compressor.inflate(source, decompressed, original.length);
          assertArrayEquals(original, ByteBufUtil.getBytes(decompressed));
        } finally {
          source.release();
          decompressed.release();
        }
      } finally {
        compressed.release();
      }
    }

    @Test
    @DisplayName("should reject oversized data when inflating into a composite buffer")
    void rejectsOversizedIntoComposite() throws DataFormatException {
      byte[] original = repetitive(10_000);
      ByteBuf compressed = Unpooled.buffer();
      CompositeByteBuf decompressed = emptyComposite();
      try (JavaCompressor compressor = new JavaCompressor(6)) {
        compressor.deflate(Unpooled.wrappedBuffer(original), compressed);
        assertThrows(
            DataFormatException.class,
            () -> compressor.inflate(compressed, decompressed, original.length - 1));
      } finally {
        compressed.release();
        decompressed.release();
      }
    }

    private static byte[] repetitive(int size) {
      byte[] data = new byte[size];
      for (int i = 0; i < size; i++) {
        data[i] = (byte) ('a' + i % 7);
      }
      return data;
    }

    /** Two heap components holding {@code bytes}, so that no single NIO buffer covers them. */
    private static CompositeByteBuf composite(byte[] bytes) {
      int half = bytes.length / 2;
      CompositeByteBuf composite = Unpooled.compositeBuffer();
      composite.addComponent(true, Unpooled.copiedBuffer(bytes, 0, half));
      composite.addComponent(true, Unpooled.copiedBuffer(bytes, half, bytes.length - half));
      assertEquals(2, composite.nioBufferCount());
      return composite;
    }

    /** Two empty components, so that the first write grows the buffer by a third. */
    private static CompositeByteBuf emptyComposite() {
      CompositeByteBuf composite = Unpooled.compositeBuffer();
      composite.addComponent(true, Unpooled.buffer(0, 0));
      composite.addComponent(true, Unpooled.buffer(0, 0));
      return composite;
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
