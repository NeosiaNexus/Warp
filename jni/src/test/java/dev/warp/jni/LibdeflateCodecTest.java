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
package dev.warp.jni;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("LibdeflateCodec")
class LibdeflateCodecTest {

  private static final long SEED = 0x5741_5250L;

  @BeforeEach
  void requireNative() {
    NativeAssumptions.requireNativeCompression();
  }

  // ---------------------------------------------------------------------------
  // Round trips
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("round trips")
  class RoundTrips {

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("dev.warp.jni.LibdeflateCodecTest#seeds")
    @DisplayName("should restore random packets of every size and entropy through libdeflate")
    void nativeToNative(long seed) throws DataFormatException {
      // given
      byte[] packet = randomPacket(new SplittableRandom(seed));
      LibdeflateCodec codec = LibdeflateCodec.create(new SplittableRandom(seed).nextInt(13));

      // when
      byte[] compressed = deflate(codec, packet);
      byte[] restored = inflate(codec, compressed, packet.length);

      // then
      assertArrayEquals(packet, restored);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("dev.warp.jni.LibdeflateCodecTest#seeds")
    @DisplayName("should write streams java.util.zip reads back exactly")
    void nativeToJava(long seed) throws DataFormatException {
      // given
      byte[] packet = randomPacket(new SplittableRandom(seed));

      // when
      byte[] compressed = deflate(LibdeflateCodec.create(6), packet);

      // then
      assertArrayEquals(packet, javaInflate(compressed, packet.length));
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("dev.warp.jni.LibdeflateCodecTest#seeds")
    @DisplayName("should read back exactly what java.util.zip writes, at every level")
    void javaToNative(long seed) throws DataFormatException {
      // given
      SplittableRandom random = new SplittableRandom(seed);
      byte[] packet = randomPacket(random);
      byte[] compressed = javaDeflate(packet, random.nextInt(10));

      // when
      byte[] restored = inflate(LibdeflateCodec.create(6), compressed, packet.length);

      // then
      assertArrayEquals(packet, restored);
    }

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    @DisplayName("should produce valid zlib streams at every level libdeflate offers")
    void everyLevel(int level) throws DataFormatException {
      // given
      byte[] packet = randomPacket(new SplittableRandom(SEED + level));

      // when
      byte[] compressed = deflate(LibdeflateCodec.create(level), packet);

      // then
      assertArrayEquals(packet, javaInflate(compressed, packet.length));
    }

    @Test
    @DisplayName("should map level -1 to zlib's default, 6")
    void defaultLevel() {
      assertEquals(LibdeflateCodec.DEFAULT_LEVEL, LibdeflateCodec.create(-1).level());
    }

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {-2, 13, Integer.MIN_VALUE, Integer.MAX_VALUE})
    @DisplayName("should refuse levels libdeflate does not offer")
    void invalidLevel(int level) {
      assertThrows(IllegalArgumentException.class, () -> LibdeflateCodec.create(level));
    }

    @Test
    @DisplayName("should compress and restore an empty packet")
    void empty() throws DataFormatException {
      LibdeflateCodec codec = LibdeflateCodec.create(6);
      byte[] compressed = deflate(codec, new byte[0]);
      assertArrayEquals(new byte[0], inflate(codec, compressed, 0));
    }

    @Test
    @DisplayName("should never produce more than its announced bound, even for random bytes")
    void bound() {
      // given
      LibdeflateCodec codec = LibdeflateCodec.create(9);
      byte[] incompressible = new byte[300_000];
      new SplittableRandom(SEED).nextBytes(incompressible);

      // when
      byte[] compressed = deflate(codec, incompressible);

      // then
      assertTrue(compressed.length <= codec.deflateBound(incompressible.length));
    }
  }

  // ---------------------------------------------------------------------------
  // Untrusted input
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("untrusted input")
  class UntrustedInput {

    private final LibdeflateCodec codec = LibdeflateCodec.create(6);
    private final byte[] packet = randomPacket(new SplittableRandom(SEED));
    private final byte[] compressed = deflate(LibdeflateCodec.create(6), packet);

    @Test
    @DisplayName("should reject a stream that inflates to more than the declared size")
    void declaredTooSmall() {
      // given
      ByteBuf destination = Unpooled.directBuffer();

      // when
      DataFormatException e =
          assertThrows(
              DataFormatException.class,
              () -> codec.inflate(direct(compressed), destination, packet.length - 1));

      // then
      assertTrue(String.valueOf(e.getMessage()).contains("more bytes than claimed"));
      assertEquals(0, destination.writerIndex(), "nothing reported as written");
      destination.release();
    }

    @Test
    @DisplayName("should reject a stream that inflates to less than the declared size")
    void declaredTooLarge() {
      // given
      ByteBuf destination = Unpooled.directBuffer();

      // when
      DataFormatException e =
          assertThrows(
              DataFormatException.class,
              () -> codec.inflate(direct(compressed), destination, packet.length + 1));

      // then
      assertTrue(String.valueOf(e.getMessage()).contains("fewer bytes than claimed"));
      assertEquals(0, destination.writerIndex());
      destination.release();
    }

    @Test
    @DisplayName("should write no further than the declared size, whatever the stream holds")
    void boundedOutput() {
      // given: a stream of 1 MiB of zeros declared as 1 KiB, into a buffer with guard bytes after
      byte[] bomb = javaDeflate(new byte[1 << 20], 9);
      ByteBuf destination = Unpooled.directBuffer(1024 + 64);
      byte[] guard = new byte[64];
      Arrays.fill(guard, (byte) 0x5A);
      destination.setBytes(1024, guard);

      // when
      assertThrows(DataFormatException.class, () -> codec.inflate(direct(bomb), destination, 1024));

      // then
      byte[] after = new byte[64];
      destination.getBytes(1024, after);
      assertArrayEquals(guard, after, "bytes past the declared size untouched");
      destination.release();
    }

    @Test
    @DisplayName("should reject a corrupt checksum")
    void corruptChecksum() {
      byte[] corrupt = compressed.clone();
      corrupt[corrupt.length - 1] ^= 0x01;
      assertRejected(corrupt, packet.length);
    }

    @Test
    @DisplayName("should reject a truncated stream at every cut")
    void truncated() {
      for (int cut = 0; cut < compressed.length; cut++) {
        assertRejected(Arrays.copyOf(compressed, cut), packet.length);
      }
    }

    @Test
    @DisplayName("should reject garbage, even of a plausible size, without crashing")
    void garbage() {
      SplittableRandom random = new SplittableRandom(SEED);
      for (int i = 0; i < 2_000; i++) {
        byte[] noise = new byte[random.nextInt(2, 2048)];
        random.nextBytes(noise);
        noise[0] = 0x78; // a valid zlib header gets past the first check
        noise[1] = (byte) 0x9C;
        assertRejected(noise, random.nextInt(1, 65536));
      }
    }

    @Test
    @DisplayName("should agree with java.util.zip on randomly corrupted streams, never crash")
    void corruptedLikeJava() throws DataFormatException {
      SplittableRandom random = new SplittableRandom(SEED);
      int accepted = 0;
      for (int i = 0; i < 3_000; i++) {
        // given: a valid stream with one to four bytes changed
        byte[] original = randomPacket(random);
        byte[] stream = javaDeflate(original, random.nextInt(1, 10));
        for (int flips = random.nextInt(1, 5); flips > 0; flips--) {
          stream[random.nextInt(stream.length)] ^= (byte) random.nextInt(1, 256);
        }

        // when
        byte[] expected = javaInflateOrNull(stream, original.length);
        byte[] actual = inflateOrNull(codec, stream, original.length);

        // then
        assertArrayEquals(expected, actual, "corruption " + i);
        accepted += actual == null ? 0 : 1;
      }
      assertTrue(accepted < 300, "almost every corruption is caught by the checksum: " + accepted);
    }

    @Test
    @DisplayName("should ignore bytes after the end of the stream, as java.util.zip does")
    void trailingBytes() throws DataFormatException {
      byte[] withTrailer = Arrays.copyOf(compressed, compressed.length + 7);
      assertArrayEquals(packet, inflate(codec, withTrailer, packet.length));
      assertArrayEquals(packet, javaInflate(withTrailer, packet.length));
    }

    @Test
    @DisplayName("should reject a stream that never ends, as Warp's java.util.zip path does")
    void unterminated() {
      // given: what a streaming compressor sends after a sync flush, without finishing
      Deflater deflater = new Deflater(6);
      deflater.setInput(packet);
      byte[] buffer = new byte[packet.length * 2 + 64];
      int length = deflater.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH);
      deflater.end();
      byte[] stream = Arrays.copyOf(buffer, length);

      // then
      assertRejected(stream, packet.length);
    }

    private void assertRejected(byte[] stream, int declared) {
      ByteBuf source = direct(stream);
      ByteBuf destination = Unpooled.directBuffer();
      try {
        assertThrows(DataFormatException.class, () -> codec.inflate(source, destination, declared));
        assertEquals(0, destination.writerIndex(), "nothing reported as written");
        assertEquals(0, source.readerIndex(), "source untouched");
      } finally {
        source.release();
        destination.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Buffers
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("buffers")
  class Buffers {

    private final LibdeflateCodec codec = LibdeflateCodec.create(6);
    private final byte[] packet = randomPacket(new SplittableRandom(SEED + 1));

    @ParameterizedTest(name = "{0} source, {1} destination")
    @MethodSource("dev.warp.jni.LibdeflateCodecTest#bufferKinds")
    @DisplayName("should work in place or through a copy, leaving the source's indices alone")
    void anyKind(String sourceKind, String destinationKind) throws DataFormatException {
      // given
      ByteBuf source = buffer(sourceKind, packet);
      ByteBuf compressed = buffer(destinationKind, new byte[0]);
      int readerIndex = source.readerIndex();

      // when
      codec.deflate(source, compressed);
      ByteBuf restored = buffer(destinationKind, new byte[0]);
      codec.inflate(compressed, restored, packet.length);

      // then
      assertEquals(readerIndex, source.readerIndex());
      assertEquals(packet.length, restored.readableBytes());
      assertArrayEquals(packet, ByteBufUtil.getBytes(restored));
      source.release();
      compressed.release();
      restored.release();
    }

    @Test
    @DisplayName("should append after what the destination already holds")
    void appends() throws DataFormatException {
      // given
      ByteBuf compressed = PooledByteBufAllocator.DEFAULT.directBuffer().writeBytes(new byte[3]);
      ByteBuf source = direct(packet);

      // when
      codec.deflate(source, compressed);
      ByteBuf restored = PooledByteBufAllocator.DEFAULT.directBuffer().writeByte(42);
      codec.inflate(compressed.skipBytes(3), restored, packet.length);

      // then
      assertEquals(42, restored.readByte());
      assertArrayEquals(packet, ByteBufUtil.getBytes(restored));
      source.release();
      compressed.release();
      restored.release();
    }

    @Test
    @DisplayName("should read the source from its reader index")
    void readerIndex() throws DataFormatException {
      // given
      ByteBuf source = direct(packet);
      source.readerIndex(10);
      ByteBuf compressed = Unpooled.directBuffer();

      // when
      codec.deflate(source, compressed);

      // then
      assertArrayEquals(
          Arrays.copyOfRange(packet, 10, packet.length),
          inflate(codec, ByteBufUtil.getBytes(compressed), packet.length - 10));
      source.release();
      compressed.release();
    }

    private ByteBuf buffer(String kind, byte[] bytes) {
      return switch (kind) {
        case "heap" -> Unpooled.buffer(bytes.length + 1).writeBytes(bytes);
        case "composite" -> {
          CompositeByteBuf composite = Unpooled.compositeBuffer();
          int half = bytes.length / 2;
          composite.addComponent(true, Unpooled.directBuffer().writeBytes(bytes, 0, half));
          composite.addComponent(
              true, Unpooled.buffer().writeBytes(bytes, half, bytes.length - half));
          yield composite;
        }
        default -> PooledByteBufAllocator.DEFAULT.directBuffer(bytes.length + 1).writeBytes(bytes);
      };
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  static Stream<Long> seeds() {
    return IntStream.range(0, 64).mapToObj(i -> SEED * 31 + i);
  }

  static Stream<Arguments> bufferKinds() {
    return Stream.of(
        Arguments.of("pooled direct", "pooled direct"),
        Arguments.of("heap", "heap"),
        Arguments.of("heap", "pooled direct"),
        Arguments.of("pooled direct", "heap"),
        Arguments.of("composite", "composite"));
  }

  /**
   * A packet of 0 to 256 KiB, from random bytes to long runs, shaped like Minecraft traffic: small
   * packets are the most common, chunks the largest.
   */
  static byte[] randomPacket(SplittableRandom random) {
    int size =
        switch (random.nextInt(4)) {
          case 0 -> random.nextInt(0, 64);
          case 1 -> random.nextInt(64, 4096);
          case 2 -> random.nextInt(4096, 65536);
          default -> random.nextInt(65536, 262144);
        };
    byte[] packet = new byte[size];
    int alphabet = 1 << random.nextInt(1, 9); // 2 to 256 distinct byte values
    int run = random.nextInt(1, 64);
    for (int i = 0; i < size; ) {
      byte value = (byte) random.nextInt(alphabet);
      int length = Math.min(size - i, random.nextInt(1, run + 1));
      Arrays.fill(packet, i, i + length, value);
      i += length;
    }
    return packet;
  }

  static ByteBuf direct(byte[] bytes) {
    return Unpooled.directBuffer(Math.max(1, bytes.length)).writeBytes(bytes);
  }

  static byte[] deflate(LibdeflateCodec codec, byte[] packet) {
    ByteBuf source = direct(packet);
    ByteBuf destination = Unpooled.directBuffer();
    try {
      codec.deflate(source, destination);
      return ByteBufUtil.getBytes(destination);
    } finally {
      source.release();
      destination.release();
    }
  }

  static byte[] inflate(LibdeflateCodec codec, byte[] stream, int size) throws DataFormatException {
    ByteBuf source = direct(stream);
    ByteBuf destination = Unpooled.directBuffer();
    try {
      codec.inflate(source, destination, size);
      return ByteBufUtil.getBytes(destination);
    } finally {
      source.release();
      destination.release();
    }
  }

  static byte @Nullable [] inflateOrNull(LibdeflateCodec codec, byte[] stream, int size) {
    try {
      return inflate(codec, stream, size);
    } catch (DataFormatException e) {
      return null;
    }
  }

  static byte[] javaDeflate(byte[] packet, int level) {
    Deflater deflater = new Deflater(level);
    try {
      deflater.setInput(packet);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      while (!deflater.finished()) {
        out.write(chunk, 0, deflater.deflate(chunk));
      }
      return out.toByteArray();
    } finally {
      deflater.end();
    }
  }

  /** Inflates as Warp's java.util.zip path does: exactly {@code size} bytes, to the stream end. */
  static byte[] javaInflate(byte[] stream, int size) throws DataFormatException {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(stream);
      byte[] out = new byte[size];
      int produced = size == 0 ? 0 : inflater.inflate(out);
      if (size == 0) {
        inflater.inflate(new byte[1]);
      }
      if (produced != size || !inflater.finished()) {
        throw new DataFormatException("inflated " + produced + " of " + size + " bytes");
      }
      return out;
    } finally {
      inflater.end();
    }
  }

  static byte @Nullable [] javaInflateOrNull(byte[] stream, int size) {
    try {
      return javaInflate(stream, size);
    } catch (DataFormatException e) {
      return null;
    }
  }
}
