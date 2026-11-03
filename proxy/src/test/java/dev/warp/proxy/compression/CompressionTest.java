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
package dev.warp.proxy.compression;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.warp.jni.Natives;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.compress.PacketCompressor;

import java.util.Random;
import java.util.zip.DataFormatException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Compression")
class CompressionTest {

  private static final byte[] PACKET = packet();

  @Nested
  @DisplayName("without native libdeflate")
  class Fallback {

    @Test
    @DisplayName("should fall back to java.util.zip")
    void javaCompressor() throws DataFormatException {
      // when
      PacketCompressor compressor = Compression.create(6, false);

      // then
      assertInstanceOf(JavaCompressor.class, compressor);
      assertArrayEquals(PACKET, roundTrip(compressor, compressor));
      compressor.close();
    }
  }

  @Nested
  @DisplayName("with native libdeflate")
  class Native {

    @Test
    @DisplayName("should compress with libdeflate when it passed its self-test")
    void libdeflate() throws DataFormatException {
      // given
      requireNative();

      // when
      PacketCompressor compressor = Compression.create(-1);

      // then
      assertInstanceOf(LibdeflateCompressor.class, compressor);
      assertArrayEquals(PACKET, roundTrip(compressor, compressor));
    }

    @Test
    @DisplayName("should exchange streams with java.util.zip both ways")
    void interoperates() throws DataFormatException {
      // given
      requireNative();
      PacketCompressor nativeCompressor = Compression.create(6, true);
      PacketCompressor javaCompressor = Compression.create(6, false);

      // then
      assertArrayEquals(PACKET, roundTrip(nativeCompressor, javaCompressor));
      assertArrayEquals(PACKET, roundTrip(javaCompressor, nativeCompressor));
      javaCompressor.close();
    }

    @Test
    @DisplayName("should reject a wrong declared size like java.util.zip")
    void wrongSize() throws DataFormatException {
      // given
      requireNative();
      PacketCompressor compressor = Compression.create(6, true);
      ByteBuf compressed = deflate(compressor);

      // then
      for (int declared : new int[] {PACKET.length - 1, PACKET.length + 1}) {
        ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer();
        assertThrows(
            DataFormatException.class, () -> compressor.inflate(compressed, out, declared));
        out.release();
      }
      compressed.release();
    }

    @Test
    @DisplayName("should keep working after close, which frees nothing shared")
    void closeFreesNothing() throws DataFormatException {
      // given: a handler removed early, as a plugin or a server switch may do
      requireNative();
      PacketCompressor closed = Compression.create(6, true);
      closed.close();

      // then: the event loop's contexts are intact for everyone else
      PacketCompressor other = Compression.create(6, true);
      assertArrayEquals(PACKET, roundTrip(other, other));
    }

    @Test
    @DisplayName("should announce a bound its output never exceeds")
    void bound() throws DataFormatException {
      requireNative();
      PacketCompressor compressor = Compression.create(9, true);
      ByteBuf compressed = deflate(compressor);
      assertTrue(compressed.readableBytes() <= compressor.deflateBound(PACKET.length));
      compressed.release();
    }

    private static void requireNative() {
      if (Boolean.getBoolean("warp.natives.required")) {
        assertTrue(Natives.isNativeCompressionAvailable(), Natives.compression().detail());
      }
      assumeTrue(Natives.isNativeCompressionAvailable(), Natives.compression().detail());
    }
  }

  private static byte[] packet() {
    byte[] packet = new byte[20_000];
    Random random = new Random(42);
    for (int i = 0; i < packet.length; i++) {
      packet[i] = (byte) (i % 7 == 0 ? random.nextInt(256) : i / 100);
    }
    return packet;
  }

  private static ByteBuf deflate(PacketCompressor compressor) throws DataFormatException {
    ByteBuf source = PooledByteBufAllocator.DEFAULT.directBuffer().writeBytes(PACKET);
    ByteBuf compressed = PooledByteBufAllocator.DEFAULT.directBuffer();
    compressor.deflate(source, compressed);
    source.release();
    return compressed;
  }

  private static byte[] roundTrip(PacketCompressor deflater, PacketCompressor inflater)
      throws DataFormatException {
    ByteBuf compressed = deflate(deflater);
    ByteBuf restored = PooledByteBufAllocator.DEFAULT.directBuffer();
    try {
      inflater.inflate(compressed, restored, PACKET.length);
      return ByteBufUtil.getBytes(restored);
    } finally {
      compressed.release();
      restored.release();
    }
  }
}
