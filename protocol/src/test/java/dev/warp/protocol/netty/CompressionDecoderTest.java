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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.compress.TrackingCompressor;

import java.util.Random;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CompressionDecoder")
class CompressionDecoderTest {

  private static final int THRESHOLD = 256;

  @Nested
  @DisplayName("uncompressed frames (Data Length = 0)")
  class Uncompressed {

    @Test
    @DisplayName("should strip the length prefix and Data Length")
    void stripsHeaders() {
      EmbeddedChannel ch = decoderChannel();
      byte[] packet = {0x01, 0x02, 0x03};

      assertTrue(ch.writeInbound(Frames.uncompressed(packet)));

      assertArrayEquals(packet, Frames.drain(ch.readInbound()));
      assertNull(ch.readInbound());
      ch.finish();
    }

    @Test
    @DisplayName("should accept uncompressed frames above the threshold, as vanilla does")
    void acceptsLargeUncompressed() {
      EmbeddedChannel ch = decoderChannel();
      byte[] packet = new byte[500];

      assertTrue(ch.writeInbound(Frames.uncompressed(packet)));

      assertArrayEquals(packet, Frames.drain(ch.readInbound()));
      ch.finish();
    }
  }

  @Nested
  @DisplayName("compressed frames")
  class Compressed {

    @Test
    @DisplayName("should inflate random data")
    void inflatesRandomData() {
      EmbeddedChannel ch = decoderChannel();
      byte[] packet = new byte[500];
      new Random(42).nextBytes(packet);

      assertTrue(ch.writeInbound(Frames.compressed(packet, Deflater.DEFAULT_COMPRESSION)));

      assertArrayEquals(packet, Frames.drain(ch.readInbound()));
      ch.finish();
    }

    @Test
    @DisplayName("should inflate highly repetitive data")
    void inflatesRepetitiveData() {
      EmbeddedChannel ch = decoderChannel();
      byte[] packet = new byte[1000];
      for (int i = 0; i < packet.length; i++) {
        packet[i] = (byte) (i % 11);
      }

      assertTrue(ch.writeInbound(Frames.compressed(packet, Deflater.BEST_COMPRESSION)));

      assertArrayEquals(packet, Frames.drain(ch.readInbound()));
      ch.finish();
    }

    @Test
    @DisplayName("should reject compressed frames below the threshold")
    void rejectsBelowThreshold() {
      EmbeddedChannel ch = decoderChannel();
      byte[] packet = new byte[100];

      assertThrows(
          DecoderException.class,
          () -> ch.writeInbound(Frames.compressed(packet, Deflater.DEFAULT_COMPRESSION)));
      ch.finish();
    }

    @Test
    @DisplayName("should apply the decompressor's size cap")
    void appliesSizeCap() {
      FrameDecompressor capped =
          new FrameDecompressor(
              THRESHOLD, true, 1024, new JavaCompressor(Deflater.DEFAULT_COMPRESSION));
      EmbeddedChannel ch = new EmbeddedChannel(new CompressionDecoder(capped));
      byte[] packet = new byte[1025];

      assertThrows(
          DecoderException.class,
          () -> ch.writeInbound(Frames.compressed(packet, Deflater.DEFAULT_COMPRESSION)));
      ch.finish();
    }
  }

  @Nested
  @DisplayName("decompression budget")
  class DecompressionBudget {

    @Test
    @DisplayName("should reject frames once the per-second budget is spent")
    void rejectsOverBudget() {
      CompressionDecoder decoder =
          new CompressionDecoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION));
      decoder.decompressor().setMaxDecompressionRate(1000);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      assertTrue(ch.writeInbound(Frames.compressed(new byte[600], 6)));
      ch.<ByteBuf>readInbound().release();

      assertThrows(
          DecoderException.class, () -> ch.writeInbound(Frames.compressed(new byte[600], 6)));
      ch.finish();
    }

    @Test
    @DisplayName("should allow traffic within the budget")
    void allowsWithinBudget() {
      CompressionDecoder decoder =
          new CompressionDecoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION));
      decoder.decompressor().setMaxDecompressionRate(100_000);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      assertTrue(ch.writeInbound(Frames.compressed(new byte[500], 6)));
      ch.<ByteBuf>readInbound().release();
      assertTrue(ch.writeInbound(Frames.compressed(new byte[500], 6)));
      ch.<ByteBuf>readInbound().release();
      ch.finish();
    }
  }

  @Nested
  @DisplayName("lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("should expose the decompressor it was given")
    void exposesDecompressor() {
      FrameDecompressor decompressor =
          new FrameDecompressor(
              THRESHOLD, true, 1024, new JavaCompressor(Deflater.DEFAULT_COMPRESSION));

      assertSame(decompressor, new CompressionDecoder(decompressor).decompressor());
    }

    @Test
    @DisplayName("should close its decompressor when removed from the pipeline")
    void closesDecompressorOnRemoval() {
      TrackingCompressor compressor = new TrackingCompressor();
      CompressionDecoder decoder = new CompressionDecoder(THRESHOLD, compressor);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      ch.pipeline().remove(decoder);

      assertEquals(1, compressor.closes());
      ch.finish();
    }
  }

  private static EmbeddedChannel decoderChannel() {
    return new EmbeddedChannel(
        new CompressionDecoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));
  }
}
