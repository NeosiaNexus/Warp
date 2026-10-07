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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.fuzz.FuzzSeeds;
import dev.warp.protocol.fuzz.InboundRecorder;
import dev.warp.protocol.fuzz.Rejections;
import dev.warp.protocol.fuzz.TrackingAllocator;
import dev.warp.protocol.fuzz.Wire;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.DataFormatException;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fuzzes {@link CompressionDecoder}, behind a {@link FrameDecoder}, against the reference of {@link
 * Wire#decompressed}: a declared size within the limits, which the stream inflates to exactly.
 */
@DisplayName("CompressionDecoder fuzzing")
class CompressionDecoderFuzzTest {

  /** Caps on the declared size: below most packets, above most, and vanilla's. */
  private static final int[] MAX_SIZES = {
    64, 1024, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE
  };

  /**
   * How the stream is built: {@code RAW} takes the input as the stream, and {@code 1 + f} takes it
   * as the packets of an uncompressed stream, which it frames for a compressed connection with the
   * {@link Wire#compressedBody} framing {@code f}.
   */
  private static final int RAW = 0;

  @FuzzTest
  @DisplayName("should inflate exactly the frames Warp accepts, reject the rest, release all")
  void decode(FuzzedDataProvider data) {
    boolean direct = data.consumeBoolean();
    int threshold = data.consumeInt(0, 255);
    boolean validateThreshold = data.consumeBoolean();
    int maxSize = data.pickValue(MAX_SIZES);
    int framing = data.consumeInt(RAW, 1 + Wire.TRUNCATED);
    boolean compressAll = data.consumeBoolean();
    int at = framing == RAW ? 0 : data.consumeInt(0, 255);
    byte[] input = data.consumeRemainingAsBytes();
    byte[] stream = framing == RAW ? input : frames(input, threshold, compressAll, framing - 1, at);

    TrackingAllocator alloc = new TrackingAllocator();
    InboundRecorder recorder = new InboundRecorder();
    FrameDecompressor decompressor =
        new FrameDecompressor(threshold, validateThreshold, maxSize, new JavaCompressor(6));
    EmbeddedChannel channel =
        new EmbeddedChannel(new FrameDecoder(), new CompressionDecoder(decompressor), recorder);
    channel.config().setAllocator(alloc);
    ByteBuf in = direct ? alloc.directBuffer(stream.length) : alloc.heapBuffer(stream.length);
    channel.writeInbound(in.writeBytes(stream));
    channel.finishAndReleaseAll();

    Wire.Framing frames = Wire.frames(stream);
    List<byte[]> expected = new ArrayList<>();
    boolean rejected = frames.malformed();
    for (byte[] body : frames.payloads()) {
      byte[] packet = Wire.decompressed(body, threshold, validateThreshold, maxSize);
      if (packet == null) {
        rejected = true;
        break;
      }
      expected.add(packet);
    }
    List<Object> packets = recorder.messages();
    assertEquals(expected.size(), packets.size(), "packet count");
    for (int i = 0; i < packets.size(); i++) {
      assertArrayEquals(expected.get(i), ByteBufUtil.getBytes((ByteBuf) packets.get(i)));
    }
    assertEquals(rejected ? 1 : 0, recorder.failures().size(), "failure count");
    recorder
        .failures()
        .forEach(failure -> Rejections.assertRejection(failure, DataFormatException.class));

    recorder.release();
    alloc.assertAllReleased();
    // A declared size is allocated only once it is plausible: within the cap, and within what
    // DEFLATE can expand the received bytes to.
    long plausible = Math.min(maxSize, 1032L * stream.length + 258);
    assertTrue(
        alloc.largestCapacity() <= Math.max(64 + 2L * stream.length, plausible),
        () -> "allocated " + alloc.largestCapacity() + " bytes for " + stream.length);
  }

  @Test
  @DisplayName("should keep its checked-in seeds up to date")
  void seeds() throws IOException {
    ByteArrayOutputStream packets = new ByteArrayOutputStream();
    packets.writeBytes(Wire.frame(Wire.packet(new byte[] {0x01}, 4)));
    packets.writeBytes(Wire.frame(Wire.packet(new byte[] {0x22}, 100)));
    packets.writeBytes(Wire.frame(Wire.packet(new byte[] {0x7f}, 300)));
    byte[] stream = packets.toByteArray();
    byte[] corrupt = HexFormat.of().parseHex("0a789cffffffff00000000");

    // Choices: direct, threshold, validate the threshold, cap, framing, compress all, then where
    // to damage a stream, unless raw.
    new FuzzSeeds(CompressionDecoderFuzzTest.class, "decode")
        .add("truthful", stream, 0, 64, 1, 2, 1 + Wire.TRUTHFUL, 0, 0)
        .add("truthful-direct-all-compressed", stream, 1, 64, 0, 2, 1 + Wire.TRUTHFUL, 1, 0)
        .add("compressed-below-threshold", stream, 0, 64, 1, 2, 1 + Wire.TRUTHFUL, 1, 0)
        .add("above-cap", stream, 0, 64, 1, 0, 1 + Wire.TRUTHFUL, 0, 0)
        .add("declared-one-more", stream, 0, 64, 1, 2, 1 + Wire.ONE_MORE, 0, 0)
        .add("declared-one-less", stream, 0, 64, 1, 2, 1 + Wire.ONE_LESS, 0, 0)
        .add("declared-beyond-deflate", stream, 0, 64, 1, 2, 1 + Wire.BEYOND_DEFLATE, 0, 0)
        .add("corrupt-stream", stream, 0, 64, 1, 2, 1 + Wire.CORRUPT, 0, 7)
        .add("truncated-stream", stream, 0, 64, 1, 2, 1 + Wire.TRUNCATED, 0, 200)
        .add("raw-corrupt-stream", Wire.frame(corrupt), 0, 0, 1, 2, RAW, 0)
        .verify();
  }

  // ---------------------------------------------------------------------------
  // Input
  // ---------------------------------------------------------------------------

  /** Frames the packets of an uncompressed stream for a compressed connection. */
  private static byte[] frames(
      byte[] input, int threshold, boolean compressAll, int framing, int at) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] packet : Wire.frames(input).payloads()) {
      out.writeBytes(Wire.frame(Wire.compressedBody(packet, threshold, compressAll, framing, at)));
    }
    return out.toByteArray();
  }
}
