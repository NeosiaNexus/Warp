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

import dev.warp.protocol.codec.VarInt;

import java.util.Random;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FrameDecompressor")
class FrameDecompressorTest {

  private static final int THRESHOLD = 256;
  private static final ByteBufAllocator ALLOC = ByteBufAllocator.DEFAULT;

  @Nested
  @DisplayName("header check")
  class HeaderCheck {

    @Test
    @DisplayName("should reject a negative data length")
    void rejectsNegative() {
      assertThrows(DecoderException.class, () -> validating().checkHeader(-1, 10));
    }

    @Test
    @DisplayName("should reject below-threshold frames when validating, like a vanilla server")
    void rejectsBelowThresholdWhenValidating() {
      assertThrows(DecoderException.class, () -> validating().checkHeader(THRESHOLD - 1, 50));
    }

    @Test
    @DisplayName("should accept below-threshold frames when not validating, like a 1.17.1+ client")
    void acceptsBelowThresholdOtherwise() {
      assertDoesNotThrow(() -> lenient().checkHeader(THRESHOLD - 1, 50));
    }

    @Test
    @DisplayName("should accept declared sizes up to DEFLATE's 1032:1 bound")
    void acceptsUpToDeflateBound() {
      int compressed = 100;
      assertDoesNotThrow(
          () -> lenient().checkHeader(compressed * FrameDecompressor.MAX_DEFLATE_RATIO + 258, 100));
    }

    @Test
    @DisplayName("should reject declared sizes DEFLATE cannot produce from the compressed size")
    void rejectsImpossibleRatio() {
      int compressed = 100;
      assertThrows(
          DecoderException.class,
          () ->
              lenient()
                  .checkHeader(compressed * FrameDecompressor.MAX_DEFLATE_RATIO + 259, compressed));
    }

    @Test
    @DisplayName("should accept the most compressible real payloads (all-zero, level 9)")
    void acceptsMostCompressiblePayload() {
      byte[] zeros = new byte[1 << 20];
      byte[] zlib = zlib(zeros, Deflater.BEST_COMPRESSION);

      assertDoesNotThrow(() -> lenient().checkHeader(zeros.length, zlib.length));
    }
  }

  @Nested
  @DisplayName("inflate")
  class Inflate {

    @Test
    @DisplayName("should inflate to the exact original bytes")
    void inflatesExactly() {
      byte[] packet = randomBytes(5000, 1);
      ByteBuf payload = Unpooled.wrappedBuffer(zlib(packet, 6));

      ByteBuf inflated = lenient().inflate(ALLOC, payload, packet.length);

      assertArrayEquals(packet, drain(inflated));
      assertEquals(0, payload.readerIndex());
    }

    @Test
    @DisplayName("should reject a stream shorter than its declared size")
    void rejectsShortStream() {
      byte[] packet = randomBytes(500, 2);
      ByteBuf payload = Unpooled.wrappedBuffer(zlib(packet, 6));

      assertThrows(
          DecoderException.class, () -> lenient().inflate(ALLOC, payload, packet.length + 1));
    }

    @Test
    @DisplayName("should reject a stream longer than its declared size")
    void rejectsLongStream() {
      byte[] packet = randomBytes(500, 3);
      ByteBuf payload = Unpooled.wrappedBuffer(zlib(packet, 6));

      assertThrows(
          DecoderException.class, () -> lenient().inflate(ALLOC, payload, packet.length - 1));
    }

    @Test
    @DisplayName("should refuse declared sizes above the cap before allocating")
    void refusesAboveCap() {
      FrameDecompressor capped =
          new FrameDecompressor(THRESHOLD, false, 1024, new JavaCompressor(6));
      ByteBuf payload = Unpooled.wrappedBuffer(zlib(new byte[2048], 6));

      assertThrows(DecoderException.class, () -> capped.inflate(ALLOC, payload, 2048));
    }

    @Test
    @DisplayName("should enforce the per-second decompression budget")
    void enforcesBudget() {
      FrameDecompressor decompressor = lenient();
      decompressor.setMaxDecompressionRate(1000);
      byte[] packet = new byte[600];

      decompressor.inflate(ALLOC, Unpooled.wrappedBuffer(zlib(packet, 6)), 600).release();
      assertThrows(
          DecoderException.class,
          () -> decompressor.inflate(ALLOC, Unpooled.wrappedBuffer(zlib(packet, 6)), 600));
    }
  }

  @Nested
  @DisplayName("packet extraction")
  class PacketExtraction {

    @Test
    @DisplayName("should slice uncompressed bodies without copying")
    void slicesUncompressed() {
      ByteBuf body = Unpooled.buffer().writeByte(0).writeBytes(new byte[] {5, 6, 7});

      ByteBuf packet = lenient().packetOf(ALLOC, body);

      assertArrayEquals(new byte[] {5, 6, 7}, drain(packet));
      assertEquals(0, body.readerIndex());
    }

    @Test
    @DisplayName("should inflate compressed bodies")
    void inflatesCompressed() {
      byte[] packet = randomBytes(700, 4);
      ByteBuf body = Unpooled.buffer();
      VarInt.write(body, packet.length);
      body.writeBytes(zlib(packet, 6));

      assertArrayEquals(packet, drain(lenient().packetOf(ALLOC, body)));
    }

    @Test
    @DisplayName("should peek the packet id of a compressed payload")
    void peeksPacketId() {
      byte[] packet = randomBytes(700, 5);
      packet[0] = 0x27;

      assertEquals(0x27, lenient().peekPacketId(Unpooled.wrappedBuffer(zlib(packet, 6))));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static FrameDecompressor validating() {
    return new FrameDecompressor(
        THRESHOLD, true, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, new JavaCompressor(6));
  }

  private static FrameDecompressor lenient() {
    return new FrameDecompressor(
        THRESHOLD, false, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, new JavaCompressor(6));
  }

  private static byte[] zlib(byte[] data, int level) {
    return ZlibStreams.zlib(data, level, Deflater.DEFAULT_STRATEGY);
  }

  private static byte[] randomBytes(int length, long seed) {
    byte[] bytes = new byte[length];
    new Random(seed).nextBytes(bytes);
    return bytes;
  }

  private static byte[] drain(ByteBuf buf) {
    try {
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }
}
