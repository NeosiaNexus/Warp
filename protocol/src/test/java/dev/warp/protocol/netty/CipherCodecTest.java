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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.PreferHeapByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("CipherEncoder and CipherDecoder")
class CipherCodecTest {

  /**
   * Frame sizes, one call each and one after the other: empty, around the 16-byte AES block, around
   * the handlers' chunk, several chunks, then random sizes.
   */
  private static final int[] SIZES = sizes();

  private final SecretKey key = new SecretKeySpec(randomBytes(16, 16), "AES");
  private final byte[] plaintext = randomBytes(Arrays.stream(SIZES).sum(), 1);

  @Nested
  @DisplayName("encryption")
  class Encryption {

    @ParameterizedTest(name = "{0} frames, {1} output")
    @MethodSource("dev.warp.protocol.netty.CipherCodecTest#layouts")
    @DisplayName("should encrypt the stream as AES/CFB8 keyed and seeded by the shared secret")
    void encryptsLikeTheProtocol(Layout frames, Memory output) throws GeneralSecurityException {
      EmbeddedChannel ch = channel(new CipherEncoder(key), output);

      for (byte[] message : cut(plaintext)) {
        ch.writeOutbound(frames.wrap(message));
      }

      assertArrayEquals(reference(Cipher.ENCRYPT_MODE, plaintext), drainOutbound(ch));
      assertFalse(ch.finish());
    }

    @ParameterizedTest(name = "{0} frame")
    @EnumSource(Layout.class)
    @DisplayName("should leave the frame untouched, as it may go to other players too")
    void leavesTheFrameUntouched(Layout frames) throws GeneralSecurityException {
      EmbeddedChannel ch = channel(new CipherEncoder(key), Memory.DIRECT);
      ByteBuf frame = frames.wrap(plaintext);
      int start = frame.readerIndex();

      ch.writeOutbound(frame.retain());

      try {
        assertArrayEquals(plaintext, ByteBufUtil.getBytes(frame, start, plaintext.length));
      } finally {
        frame.release();
      }
      assertArrayEquals(reference(Cipher.ENCRYPT_MODE, plaintext), drainOutbound(ch));
      assertFalse(ch.finish());
    }
  }

  @Nested
  @DisplayName("decryption")
  class Decryption {

    @ParameterizedTest(name = "{0} segments, {1} output")
    @MethodSource("dev.warp.protocol.netty.CipherCodecTest#layouts")
    @DisplayName("should decrypt the stream whatever the segment sizes and buffers")
    void decryptsLikeTheProtocol(Layout segments, Memory output) throws GeneralSecurityException {
      byte[] ciphertext = reference(Cipher.ENCRYPT_MODE, plaintext);
      EmbeddedChannel ch = channel(new CipherDecoder(key), output);

      for (byte[] segment : cut(ciphertext)) {
        ch.writeInbound(segments.wrap(segment));
      }

      assertArrayEquals(plaintext, drainInbound(ch));
      assertFalse(ch.finish());
    }

    @ParameterizedTest(name = "{0} segment")
    @EnumSource(
        value = Layout.class,
        names = {"READ_ONLY_HEAP", "READ_ONLY_DIRECT"},
        mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("should decrypt in place and pass the same buffer on")
    void decryptsInPlace(Layout segments) throws GeneralSecurityException {
      EmbeddedChannel ch = channel(new CipherDecoder(key), Memory.DIRECT);
      ByteBuf segment = segments.wrap(reference(Cipher.ENCRYPT_MODE, plaintext));

      ch.writeInbound(segment);

      ByteBuf passed = ch.readInbound();
      assertSame(segment, passed);
      assertArrayEquals(plaintext, Frames.drain(passed));
      assertFalse(ch.finish());
    }

    @ParameterizedTest(name = "{0} segment")
    @EnumSource(
        value = Layout.class,
        names = {"READ_ONLY_HEAP", "READ_ONLY_DIRECT"})
    @DisplayName("should decrypt a read-only segment into a new buffer, leaving the segment as is")
    void copiesReadOnlySegments(Layout segments) throws GeneralSecurityException {
      byte[] ciphertext = reference(Cipher.ENCRYPT_MODE, plaintext);
      EmbeddedChannel ch = channel(new CipherDecoder(key), Memory.DIRECT);
      ByteBuf segment = segments.wrap(ciphertext);
      int start = segment.readerIndex();

      ch.writeInbound(segment.retain());

      ByteBuf passed = ch.readInbound();
      try {
        assertNotSame(segment, passed);
        assertArrayEquals(ciphertext, ByteBufUtil.getBytes(segment, start, ciphertext.length));
      } finally {
        segment.release();
      }
      assertArrayEquals(plaintext, Frames.drain(passed));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should pass nothing on for an empty segment")
    void emptySegment() throws GeneralSecurityException {
      EmbeddedChannel ch = new EmbeddedChannel(new CipherDecoder(key));

      ch.writeInbound(Unpooled.EMPTY_BUFFER);

      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }
  }

  @Test
  @DisplayName("should read back through a decoder what an encoder with the same secret wrote")
  void roundTrip() throws GeneralSecurityException {
    EmbeddedChannel encoder = channel(new CipherEncoder(key), Memory.DIRECT);
    EmbeddedChannel decoder = channel(new CipherDecoder(key), Memory.DIRECT);
    Layout[] layouts = Layout.values();
    byte[][] messages = cut(plaintext);

    for (int i = 0; i < messages.length; i++) {
      encoder.writeOutbound(layouts[i % layouts.length].wrap(messages[i]));
    }
    for (ByteBuf frame; (frame = encoder.readOutbound()) != null; ) {
      decoder.writeInbound(frame);
    }

    assertArrayEquals(plaintext, drainInbound(decoder));
    assertFalse(encoder.finish());
    assertFalse(decoder.finish());
  }

  // ---------------------------------------------------------------------------
  // Buffers
  // ---------------------------------------------------------------------------

  /** The kinds of buffer a frame or a read can arrive in. */
  enum Layout {
    HEAP {
      @Override
      ByteBuf wrap(byte[] bytes) {
        return Unpooled.wrappedBuffer(bytes);
      }
    },
    /** A view into a larger array, with a reader index past its start. */
    HEAP_WITH_OFFSETS {
      @Override
      ByteBuf wrap(byte[] bytes) {
        byte[] padded = new byte[bytes.length + 24];
        System.arraycopy(bytes, 0, padded, 16, bytes.length);
        return Unpooled.wrappedBuffer(padded, 8, bytes.length + 16)
            .readerIndex(8)
            .writerIndex(8 + bytes.length);
      }
    },
    /** Pooled heap buffers share one large array, which the output can come from too. */
    POOLED_HEAP {
      @Override
      ByteBuf wrap(byte[] bytes) {
        return PooledByteBufAllocator.DEFAULT.heapBuffer(bytes.length).writeBytes(bytes);
      }
    },
    DIRECT {
      @Override
      ByteBuf wrap(byte[] bytes) {
        return Unpooled.directBuffer(bytes.length).writeBytes(bytes);
      }
    },
    POOLED_DIRECT_WITH_OFFSET {
      @Override
      ByteBuf wrap(byte[] bytes) {
        return PooledByteBufAllocator.DEFAULT
            .directBuffer(bytes.length + 8)
            .writeZero(8)
            .writeBytes(bytes)
            .skipBytes(8);
      }
    },
    /** A heap half and a direct half. */
    COMPOSITE {
      @Override
      ByteBuf wrap(byte[] bytes) {
        int half = bytes.length / 2;
        return Unpooled.wrappedBuffer(
            Unpooled.wrappedBuffer(bytes, 0, half),
            Unpooled.directBuffer(bytes.length - half)
                .writeBytes(bytes, half, bytes.length - half));
      }
    },
    READ_ONLY_HEAP {
      @Override
      ByteBuf wrap(byte[] bytes) {
        return Unpooled.wrappedBuffer(bytes).asReadOnly();
      }
    },
    READ_ONLY_DIRECT {
      @Override
      ByteBuf wrap(byte[] bytes) {
        return Unpooled.directBuffer(bytes.length).writeBytes(bytes).asReadOnly();
      }
    };

    /** A buffer of this kind holding a copy of {@code bytes}. */
    abstract ByteBuf wrap(byte[] bytes);
  }

  /** Where the channel's allocator puts the buffers the handlers allocate. */
  enum Memory {
    DIRECT,
    HEAP;

    ByteBufAllocator allocator() {
      return this == DIRECT
          ? PooledByteBufAllocator.DEFAULT
          : new PreferHeapByteBufAllocator(PooledByteBufAllocator.DEFAULT);
    }
  }

  /** Every buffer layout against every output memory. */
  static Stream<Arguments> layouts() {
    return Arrays.stream(Layout.values())
        .flatMap(layout -> Arrays.stream(Memory.values()).map(m -> Arguments.of(layout, m)));
  }

  private static EmbeddedChannel channel(ChannelHandler handler, Memory memory) {
    EmbeddedChannel ch = new EmbeddedChannel(handler);
    ch.config().setAllocator(memory.allocator());
    return ch;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static int[] sizes() {
    int chunk = AesCfb8.CHUNK_SIZE;
    IntStream fixed = IntStream.of(0, 1, 15, 16, 17, 300, 0, 4096, chunk - 1, chunk, chunk + 1);
    IntStream large = IntStream.of(3 * chunk + 5, 100_000);
    IntStream random = new SplittableRandom(7).ints(16, 0, 2 * chunk);
    return IntStream.concat(IntStream.concat(fixed, large), random).toArray();
  }

  /** {@code stream} cut into {@link #SIZES}. */
  private static byte[][] cut(byte[] stream) {
    byte[][] pieces = new byte[SIZES.length][];
    int offset = 0;
    for (int i = 0; i < SIZES.length; i++) {
      pieces[i] = Arrays.copyOfRange(stream, offset, offset + SIZES[i]);
      offset += SIZES[i];
    }
    return pieces;
  }

  /** The JDK's AES/CFB8 over the whole stream, with the secret as IV as the protocol requires. */
  private byte[] reference(int mode, byte[] input) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
    cipher.init(mode, key, new IvParameterSpec(key.getEncoded()));
    return cipher.update(input);
  }

  /** Everything the channel wrote, in one array. */
  private static byte[] drainOutbound(EmbeddedChannel ch) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (ByteBuf buf; (buf = ch.readOutbound()) != null; ) {
      out.writeBytes(Frames.drain(buf));
    }
    return out.toByteArray();
  }

  /** Everything the channel read, in one array. */
  private static byte[] drainInbound(EmbeddedChannel ch) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (ByteBuf buf; (buf = ch.readInbound()) != null; ) {
      out.writeBytes(Frames.drain(buf));
    }
    return out.toByteArray();
  }

  private static byte[] randomBytes(int length, long seed) {
    byte[] bytes = new byte[length];
    new SplittableRandom(seed).nextBytes(bytes);
    return bytes;
  }
}
