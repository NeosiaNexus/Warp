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
package dev.warp.protocol.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.compress.JavaCompressor;

import java.util.Random;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CompressionDecoder")
class CompressionDecoderTest {

  private static final int THRESHOLD = 256;

  // ---------------------------------------------------------------------------
  // Uncompressed passthrough (Data Length = 0)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("uncompressed passthrough")
  class UncompressedPassthrough {

    @Test
    @DisplayName("should pass through uncompressed frames (Data Length = 0)")
    void passthroughZeroCopy() {
      EmbeddedChannel ch = decoderChannel();
      byte[] payload = {0x01, 0x02, 0x03};

      ByteBuf frame = Unpooled.buffer();
      VarInt.write(frame, 0); // Data Length = 0
      frame.writeBytes(payload);

      assertTrue(ch.writeInbound(frame));

      ByteBuf out = ch.readInbound();
      assertNotNull(out);
      assertContentEquals(payload, out);
      assertNull(ch.readInbound());
      ch.finish();
    }

    @Test
    @DisplayName("should accept large uncompressed frames above threshold (vanilla behavior)")
    void largeUncompressedAboveThreshold() {
      EmbeddedChannel ch = decoderChannel();
      byte[] payload = new byte[500]; // above threshold but Data Length = 0 → allowed

      ByteBuf frame = Unpooled.buffer();
      VarInt.write(frame, 0);
      frame.writeBytes(payload);

      assertTrue(ch.writeInbound(frame));

      ByteBuf out = ch.readInbound();
      assertNotNull(out);
      assertEquals(500, out.readableBytes());
      out.release();
      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Compressed decode
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("compressed decode")
  class CompressedDecode {

    @Test
    @DisplayName("should decompress valid compressed frame")
    void validCompressedFrame() throws Exception {
      EmbeddedChannel ch = decoderChannel();
      byte[] original = new byte[500];
      new Random(42).nextBytes(original);

      ByteBuf frame = compressedFrame(original);

      assertTrue(ch.writeInbound(frame));

      ByteBuf out = ch.readInbound();
      assertNotNull(out);
      assertContentEquals(original, out);
      ch.finish();
    }

    @Test
    @DisplayName("should decompress repetitive data correctly")
    void repetitiveData() throws Exception {
      EmbeddedChannel ch = decoderChannel();
      byte[] original = new byte[1000];
      for (int i = 0; i < original.length; i++) {
        original[i] = (byte) (i % 11);
      }

      ByteBuf frame = compressedFrame(original);

      assertTrue(ch.writeInbound(frame));

      ByteBuf out = ch.readInbound();
      assertNotNull(out);
      assertContentEquals(original, out);
      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Error handling
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("error handling")
  class ErrorHandling {

    @Test
    @DisplayName("should reject negative data length")
    void negativeDataLength() {
      EmbeddedChannel ch = decoderChannel();
      ByteBuf frame = Unpooled.buffer();
      VarInt.write(frame, -1); // negative data length
      frame.writeBytes(new byte[10]);

      assertThrows(DecoderException.class, () -> ch.writeInbound(frame));
      ch.finish();
    }

    @Test
    @DisplayName("should reject data length below compression threshold")
    void belowThreshold() {
      EmbeddedChannel ch = decoderChannel();
      ByteBuf frame = Unpooled.buffer();
      VarInt.write(frame, 100); // below threshold of 256, but > 0 → compressed-below-threshold
      frame.writeBytes(new byte[50]);

      assertThrows(DecoderException.class, () -> ch.writeInbound(frame));
      ch.finish();
    }

    @Test
    @DisplayName("should reject claimed size exceeding maximum")
    void exceedsMaxUncompressedSize() {
      int maxSize = 1024;
      EmbeddedChannel ch =
          new EmbeddedChannel(
              new CompressionDecoder(
                  THRESHOLD, maxSize, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));

      ByteBuf frame = Unpooled.buffer();
      VarInt.write(frame, maxSize + 1); // exceeds custom max
      frame.writeBytes(new byte[50]);

      assertThrows(DecoderException.class, () -> ch.writeInbound(frame));
      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Compression ratio validation (Warp-exclusive — Velocity #1742)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("compression ratio validation")
  class CompressionRatioValidation {

    @Test
    @DisplayName("should reject packets with suspiciously high compression ratio")
    void suspiciousRatio() throws Exception {
      JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
      CompressionDecoder decoder = new CompressionDecoder(THRESHOLD, compressor);
      decoder.setMaxCompressionRatio(10); // strict ratio for testing
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Create a small compressed payload but lie about the decompressed size.
      // Compress 300 bytes of zeros (compresses to ~15 bytes), then claim 5000 bytes.
      byte[] smallData = new byte[300];
      ByteBuf source = Unpooled.wrappedBuffer(smallData);
      ByteBuf compressed = Unpooled.buffer();
      try (JavaCompressor tempComp = new JavaCompressor(Deflater.DEFAULT_COMPRESSION)) {
        tempComp.deflate(source, compressed);
      }
      source.release();

      // Craft a frame claiming 5000 bytes decompressed.
      // With ~15 bytes compressed, ratio = 5000/15 ≈ 333, well above max ratio 10.
      ByteBuf frame = Unpooled.buffer();
      VarInt.write(frame, 5000);
      frame.writeBytes(compressed);
      compressed.release();

      assertThrows(DecoderException.class, () -> ch.writeInbound(frame));
      ch.finish();
    }

    @Test
    @DisplayName("should accept packets with normal compression ratio")
    void normalRatio() throws Exception {
      JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
      CompressionDecoder decoder = new CompressionDecoder(THRESHOLD, compressor);
      decoder.setMaxCompressionRatio(1024); // default
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // A legitimate compressed frame with honest data length.
      byte[] original = new byte[500];
      new Random(42).nextBytes(original);
      ByteBuf frame = compressedFrame(original);

      assertTrue(ch.writeInbound(frame));
      ByteBuf out = ch.readInbound();
      assertNotNull(out);
      assertEquals(500, out.readableBytes());
      out.release();
      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Decompression rate limiting (Warp-exclusive — Velocity #1742)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("decompression rate limiting")
  class DecompressionRateLimiting {

    @Test
    @DisplayName("should reject when cumulative decompression exceeds rate limit")
    void rateExceeded() throws Exception {
      JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
      CompressionDecoder decoder = new CompressionDecoder(THRESHOLD, compressor);
      decoder.setMaxDecompressionRate(1000); // 1 KB/sec — intentionally low for testing
      // Disable ratio check to isolate rate test
      decoder.setMaxCompressionRatio(0);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // First packet: 600 bytes decompressed — within budget.
      byte[] data1 = new byte[600];
      new Random(1).nextBytes(data1);
      ByteBuf frame1 = compressedFrame(data1);
      assertTrue(ch.writeInbound(frame1));
      ch.<ByteBuf>readInbound().release();

      // Second packet: 600 bytes decompressed — total 1200 > 1000 → rejected.
      byte[] data2 = new byte[600];
      new Random(2).nextBytes(data2);
      ByteBuf frame2 = compressedFrame(data2);
      assertThrows(DecoderException.class, () -> ch.writeInbound(frame2));
      ch.finish();
    }

    @Test
    @DisplayName("should allow traffic within the rate limit")
    void withinRate() throws Exception {
      JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
      CompressionDecoder decoder = new CompressionDecoder(THRESHOLD, compressor);
      decoder.setMaxDecompressionRate(100_000); // 100 KB/sec — plenty for two small packets
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      byte[] data1 = new byte[500];
      new Random(10).nextBytes(data1);
      assertTrue(ch.writeInbound(compressedFrame(data1)));
      ch.<ByteBuf>readInbound().release();

      byte[] data2 = new byte[500];
      new Random(20).nextBytes(data2);
      assertTrue(ch.writeInbound(compressedFrame(data2)));
      ch.<ByteBuf>readInbound().release();

      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Threshold update
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("threshold update")
  class ThresholdUpdate {

    @Test
    @DisplayName("should respect updated threshold")
    void updateThreshold() throws Exception {
      JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
      CompressionDecoder decoder = new CompressionDecoder(THRESHOLD, compressor);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Data at size 200 with original threshold 256: data length 200 < 256 → rejected
      ByteBuf frame1 = Unpooled.buffer();
      VarInt.write(frame1, 200);
      frame1.writeBytes(new byte[50]);
      assertThrows(DecoderException.class, () -> ch.writeInbound(frame1));

      // Lower threshold to 100 — now data length 200 >= 100 is valid
      decoder.setThreshold(100);
      byte[] original = new byte[200];
      new Random(99).nextBytes(original);
      ByteBuf frame2 = compressedFrame(original);

      assertTrue(ch.writeInbound(frame2));

      ByteBuf out = ch.readInbound();
      assertNotNull(out);
      assertEquals(200, out.readableBytes());
      out.release();
      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static EmbeddedChannel decoderChannel() {
    return new EmbeddedChannel(
        new CompressionDecoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));
  }

  private static ByteBuf compressedFrame(byte[] original) throws Exception {
    ByteBuf source = Unpooled.wrappedBuffer(original);
    ByteBuf compressed = Unpooled.buffer();
    try (JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION)) {
      compressor.deflate(source, compressed);
    }
    source.release();

    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, original.length); // Data Length = uncompressed size
    frame.writeBytes(compressed);
    compressed.release();
    return frame;
  }

  private static void assertContentEquals(byte[] expected, ByteBuf actual) {
    assertNotNull(actual);
    byte[] bytes = new byte[actual.readableBytes()];
    actual.readBytes(bytes);
    assertArrayEquals(expected, bytes);
    actual.release();
  }
}
