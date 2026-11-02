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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.warp.jni.LibdeflateCodec;
import dev.warp.jni.Natives;
import dev.warp.protocol.fuzz.FuzzSeeds;
import dev.warp.protocol.fuzz.Wire;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.DataFormatException;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fuzzes Warp's native decoder, libdeflate through {@link LibdeflateCodec}, against the reference
 * of {@link Wire#decompressed}: {@code java.util.zip} inflating exactly the declared size, to the
 * end of the stream. The proxy swaps one for the other depending on the machine, so both must
 * accept the same frames, with the same bytes, and reject the same frames, never writing past the
 * declared size, whatever the input.
 *
 * <p>Skips its inputs where native compression is unavailable: the native codec's own tests decide
 * whether that is allowed.
 */
@DisplayName("Native libdeflate decompression fuzzing")
class LibdeflateInflateFuzzTest {

  /** Largest declared size inflated: above any test input's, small enough for any heap. */
  private static final int MAX_SIZE = 1 << 20;

  /**
   * How the frame is built: {@code RAW} takes the input as the frame body ({@code [Data
   * Length][zlib]}), and {@code 1 + f} compresses the input as a packet, with the {@link
   * Wire#compressedBody} framing {@code f}.
   */
  private static final int RAW = 0;

  private static final @Nullable LibdeflateCodec CODEC =
      Natives.isNativeCompressionAvailable() ? LibdeflateCodec.create(6) : null;

  @FuzzTest
  @DisplayName("should inflate exactly what java.util.zip inflates and reject the rest")
  void inflate(FuzzedDataProvider data) {
    boolean direct = data.consumeBoolean();
    int framing = data.consumeInt(RAW, 1 + Wire.TRUNCATED);
    int at = framing == RAW ? 0 : data.consumeInt(0, 255);
    byte[] input = data.consumeRemainingAsBytes();
    LibdeflateCodec codec = CODEC;
    if (codec == null) {
      return;
    }
    byte[] body = framing == RAW ? input : Wire.compressedBody(input, 0, true, framing - 1, at);
    Wire.VarNum declared = Wire.varNum(body, 0, 5);
    if (declared == null || declared.value() <= 0 || declared.value() > MAX_SIZE) {
      return; // not a compressed frame: FrameDecompressor never inflates it
    }
    int size = (int) declared.value();
    byte[] expected = Wire.decompressed(body, 0, false, MAX_SIZE);
    byte[] stream = Arrays.copyOfRange(body, declared.length(), body.length);

    ByteBuf source =
        direct
            ? Unpooled.directBuffer(Math.max(1, stream.length)).writeBytes(stream)
            : Unpooled.wrappedBuffer(stream);
    ByteBuf destination = direct ? Unpooled.directBuffer() : Unpooled.buffer();
    try {
      codec.inflate(source, destination, size);
      assertNotNull(expected, "libdeflate accepted a frame java.util.zip rejects");
      assertArrayEquals(expected, ByteBufUtil.getBytes(destination));
    } catch (DataFormatException rejected) {
      assertNull(expected, "libdeflate rejected a frame java.util.zip accepts");
      assertEquals(0, destination.writerIndex(), "nothing reported as written");
    } finally {
      assertEquals(0, source.readerIndex(), "source untouched");
      source.release();
      destination.release();
    }
  }

  @Test
  @DisplayName("should keep its checked-in seeds up to date")
  void seeds() throws IOException {
    byte[] packet = Wire.packet(new byte[] {0x22}, 300);
    byte[] chunk = Wire.packet(new byte[] {0x27}, 64 * 1024);
    byte[] corrupt = HexFormat.of().parseHex("0a789cffffffff00000000");

    // Choices: direct, framing, then where to damage a stream, unless raw.
    new FuzzSeeds(LibdeflateInflateFuzzTest.class, "inflate")
        .add("truthful", packet, 0, 1 + Wire.TRUTHFUL, 0)
        .add("truthful-direct", packet, 1, 1 + Wire.TRUTHFUL, 0)
        .add("truthful-chunk-direct", chunk, 1, 1 + Wire.TRUTHFUL, 0)
        .add("declared-one-more", packet, 1, 1 + Wire.ONE_MORE, 0)
        .add("declared-one-less", packet, 1, 1 + Wire.ONE_LESS, 0)
        .add("declared-beyond-deflate", packet, 1, 1 + Wire.BEYOND_DEFLATE, 0)
        .add("corrupt-stream", packet, 1, 1 + Wire.CORRUPT, 7)
        .add("truncated-stream", packet, 0, 1 + Wire.TRUNCATED, 10)
        .add("raw-corrupt-stream", corrupt, 1, RAW)
        .verify();
  }
}
