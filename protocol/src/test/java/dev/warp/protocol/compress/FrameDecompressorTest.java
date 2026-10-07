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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.codec.VarInt;

import java.util.Random;
import java.util.zip.DataFormatException;
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
      assertThrows(DecoderException.class, () -> lenient().checkHeader(-1, 10));
    }

    @Test
    @DisplayName("should reject below-threshold frames when validating, like a vanilla server")
    void rejectsBelowThresholdWhenValidating() {
      assertThrows(DecoderException.class, () -> validating().checkHeader(THRESHOLD - 1, 50));
    }

    @Test
    @DisplayName("should accept frames declaring exactly the threshold when validating")
    void acceptsThresholdWhenValidating() {
      assertDoesNotThrow(() -> validating().checkHeader(THRESHOLD, 50));
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

      DecoderException e =
          assertThrows(
              DecoderException.class, () -> lenient().inflate(ALLOC, payload, packet.length + 1));
      assertEquals("Decompressed size does not match claimed data length", e.getMessage());
    }

    @Test
    @DisplayName("should reject a stream longer than its declared size")
    void rejectsLongStream() {
      byte[] packet = randomBytes(500, 3);
      ByteBuf payload = Unpooled.wrappedBuffer(zlib(packet, 6));

      DecoderException e =
          assertThrows(
              DecoderException.class, () -> lenient().inflate(ALLOC, payload, packet.length - 1));
      assertInstanceOf(DataFormatException.class, e.getCause());
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
    @DisplayName("should accept declared sizes exactly at the cap")
    void acceptsCap() {
      FrameDecompressor capped =
          new FrameDecompressor(THRESHOLD, false, 1024, new JavaCompressor(6));
      ByteBuf payload = Unpooled.wrappedBuffer(zlib(new byte[1024], 6));

      assertArrayEquals(new byte[1024], drain(capped.inflate(ALLOC, payload, 1024)));
    }
  }

  @Nested
  @DisplayName("decompression budget")
  class DecompressionBudget {

    private static final long SECOND = 1_000_000_000L;

    /** The time the decompressor reads, in nanoseconds: it only moves when a test moves it. */
    private long now = 42 * SECOND;

    @Test
    @DisplayName("should spend the whole budget before refusing a single byte")
    void spendsWholeBudget() {
      FrameDecompressor decompressor = budgeted(1000);

      inflateZeros(decompressor, 600);
      inflateZeros(decompressor, 400);

      assertThrows(DecoderException.class, () -> inflateZeros(decompressor, 1));
    }

    @Test
    @DisplayName("should refuse a frame larger than what is left, without spending it")
    void refusesOverBudget() {
      FrameDecompressor decompressor = budgeted(1000);
      inflateZeros(decompressor, 600);

      assertThrows(DecoderException.class, () -> inflateZeros(decompressor, 600));
      inflateZeros(decompressor, 400);
    }

    @Test
    @DisplayName("should count a frame that fails to inflate against the budget")
    void countsFailedInflation() {
      FrameDecompressor decompressor = budgeted(1000);
      ByteBuf shortStream = Unpooled.wrappedBuffer(zlib(new byte[500], 6));

      assertThrows(DecoderException.class, () -> decompressor.inflate(ALLOC, shortStream, 600));

      assertThrows(DecoderException.class, () -> inflateZeros(decompressor, 401));
      inflateZeros(decompressor, 400);
    }

    @Test
    @DisplayName("should renew the budget once a full second has passed, and not before")
    void renewsAfterOneSecond() {
      FrameDecompressor decompressor = budgeted(1000);
      inflateZeros(decompressor, 1000);

      now += SECOND;
      assertThrows(DecoderException.class, () -> inflateZeros(decompressor, 1));

      now += 1;
      inflateZeros(decompressor, 1000);
      assertThrows(DecoderException.class, () -> inflateZeros(decompressor, 1));
    }

    @Test
    @DisplayName("should time the first window from the decompressor's creation")
    void firstWindowFromCreation() {
      FrameDecompressor decompressor = budgeted(1000);

      now += SECOND;
      inflateZeros(decompressor, 1000);
      now += 1;
      inflateZeros(decompressor, 1000);
    }

    @Test
    @DisplayName("should not limit the rate when the budget is zero")
    void zeroBudgetDisablesLimit() {
      FrameDecompressor decompressor = budgeted(0);

      for (int i = 0; i < 3; i++) {
        inflateZeros(decompressor, 600);
      }
    }

    private FrameDecompressor budgeted(long bytesPerSecond) {
      FrameDecompressor decompressor =
          new FrameDecompressor(
              THRESHOLD,
              false,
              FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
              new JavaCompressor(6),
              () -> now);
      decompressor.setMaxDecompressionRate(bytesPerSecond);
      return decompressor;
    }

    private static void inflateZeros(FrameDecompressor decompressor, int length) {
      ByteBuf payload = Unpooled.wrappedBuffer(zlib(new byte[length], 6));
      assertArrayEquals(new byte[length], drain(decompressor.inflate(ALLOC, payload, length)));
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

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("should expose the threshold it validates against")
    void exposesThreshold() {
      assertEquals(THRESHOLD, validating().threshold());
    }

    @Test
    @DisplayName("should close the compressor it owns")
    void closesCompressor() {
      TrackingCompressor compressor = new TrackingCompressor();
      FrameDecompressor decompressor =
          new FrameDecompressor(
              THRESHOLD, true, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, compressor);

      decompressor.close();

      assertEquals(1, compressor.closes());
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
