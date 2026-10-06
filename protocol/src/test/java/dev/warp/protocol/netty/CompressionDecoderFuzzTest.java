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
import dev.warp.protocol.compress.ZlibStreams;
import dev.warp.protocol.fuzz.FuzzSeeds;
import dev.warp.protocol.fuzz.InboundRecorder;
import dev.warp.protocol.fuzz.Rejections;
import dev.warp.protocol.fuzz.TrackingAllocator;
import dev.warp.protocol.fuzz.Wire;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fuzzes {@link CompressionDecoder}, behind a {@link FrameDecoder}, against a reference of what a
 * vanilla server accepts: a declared size within its limits, which inflating then yields exactly.
 */
@DisplayName("CompressionDecoder fuzzing")
class CompressionDecoderFuzzTest {

  /** Caps on the declared size: below most packets, above most, and vanilla's. */
  private static final int[] MAX_SIZES = {
    64, 1024, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE
  };

  /**
   * How the stream is built. {@link #RAW} takes the input as the stream; the others take it as the
   * packets of an uncompressed stream, which they frame for a compressed connection, declaring the
   * size of each compressed packet truthfully or not.
   */
  private static final int RAW = 0;

  private static final int TRUTHFUL = 1;
  private static final int ONE_MORE = 2;
  private static final int ONE_LESS = 3;
  private static final int BEYOND_DEFLATE = 4;

  @FuzzTest
  @DisplayName("should inflate exactly the frames vanilla accepts, reject the rest, release all")
  void decode(FuzzedDataProvider data) {
    boolean direct = data.consumeBoolean();
    int threshold = data.consumeInt(0, 255);
    boolean validateThreshold = data.consumeBoolean();
    int maxSize = data.pickValue(MAX_SIZES);
    int framing = data.consumeInt(RAW, BEYOND_DEFLATE);
    boolean compressAll = data.consumeBoolean();
    byte[] input = data.consumeRemainingAsBytes();
    byte[] stream = framing == RAW ? input : frames(input, framing, threshold, compressAll);

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
      byte[] packet = accepted(body, threshold, validateThreshold, maxSize);
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
        alloc.largestRequest() <= Math.max(64 + 2L * stream.length, plausible),
        () -> "allocated " + alloc.largestRequest() + " bytes for " + stream.length);
  }

  @Test
  @DisplayName("should keep its checked-in seeds up to date")
  void seeds() throws IOException {
    ByteArrayOutputStream packets = new ByteArrayOutputStream();
    packets.writeBytes(Wire.frame(packet(0x01, 4)));
    packets.writeBytes(Wire.frame(packet(0x22, 100)));
    packets.writeBytes(Wire.frame(packet(0x7f, 300)));
    byte[] stream = packets.toByteArray();
    byte[] corrupt = HexFormat.of().parseHex("0a789cffffffff00000000");

    // Choices: direct, threshold, validate the threshold, cap, framing, compress all.
    new FuzzSeeds(CompressionDecoderFuzzTest.class, "decode")
        .add("truthful", stream, 0, 64, 1, 2, TRUTHFUL, 0)
        .add("truthful-direct-all-compressed", stream, 1, 64, 0, 2, TRUTHFUL, 1)
        .add("compressed-below-threshold", stream, 0, 64, 1, 2, TRUTHFUL, 1)
        .add("above-cap", stream, 0, 64, 1, 0, TRUTHFUL, 0)
        .add("declared-one-more", stream, 0, 64, 1, 2, ONE_MORE, 0)
        .add("declared-one-less", stream, 0, 64, 1, 2, ONE_LESS, 0)
        .add("declared-beyond-deflate", stream, 0, 64, 1, 2, BEYOND_DEFLATE, 0)
        .add("raw-corrupt-stream", Wire.frame(corrupt), 0, 0, 1, 2, RAW, 0)
        .verify();
  }

  // ---------------------------------------------------------------------------
  // Reference
  // ---------------------------------------------------------------------------

  /**
   * What a vanilla server makes of a frame body, {@code [Data Length][payload]}: the packet it
   * carries, or {@code null} if it rejects it. Unlike Warp, it inflates whatever size is declared
   * below its cap: a size DEFLATE cannot reach must fail here too.
   */
  private static byte @Nullable [] accepted(
      byte[] body, int threshold, boolean validateThreshold, int maxSize) {
    Wire.VarNum dataLength = Wire.varNum(body, 0, 5);
    if (dataLength == null) {
      return null;
    }
    byte[] payload = Arrays.copyOfRange(body, dataLength.length(), body.length);
    int declared = (int) dataLength.value();
    if (declared == 0) {
      return payload;
    }
    if (declared < 0 || (validateThreshold && declared < threshold) || declared > maxSize) {
      return null;
    }
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(payload);
      byte[] packet = new byte[declared + 1]; // room to find out it is longer
      int size = 0;
      while (!inflater.finished() && size < packet.length) {
        int inflated = inflater.inflate(packet, size, packet.length - size);
        if (inflated == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
          return null;
        }
        size += inflated;
      }
      return inflater.finished() && size == declared ? Arrays.copyOf(packet, size) : null;
    } catch (DataFormatException malformed) {
      return null;
    } finally {
      inflater.end();
    }
  }

  // ---------------------------------------------------------------------------
  // Input
  // ---------------------------------------------------------------------------

  /** Frames the packets of an uncompressed stream for a compressed connection. */
  private static byte[] frames(byte[] input, int framing, int threshold, boolean compressAll) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] packet : Wire.frames(input).payloads()) {
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      if (packet.length < threshold && !compressAll) {
        body.write(0);
        body.writeBytes(packet);
      } else {
        byte[] zlib = ZlibStreams.zlib(packet, 6, Deflater.DEFAULT_STRATEGY);
        int declared =
            switch (framing) {
              case ONE_MORE -> packet.length + 1;
              case ONE_LESS -> packet.length - 1;
              case BEYOND_DEFLATE -> zlib.length * 1033 + 259;
              default -> packet.length;
            };
        body.writeBytes(Wire.varInt(declared));
        body.writeBytes(zlib);
      }
      out.writeBytes(Wire.frame(body.toByteArray()));
    }
    return out.toByteArray();
  }

  /** A packet: a one-byte id, then a compressible body of {@code size} bytes. */
  private static byte[] packet(int id, int size) {
    byte[] packet = new byte[1 + size];
    packet[0] = (byte) id;
    for (int i = 1; i < packet.length; i++) {
      packet[i] = (byte) (i % 11 * 5);
    }
    return packet;
  }
}
