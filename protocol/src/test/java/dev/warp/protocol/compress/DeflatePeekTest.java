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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DeflatePeek")
class DeflatePeekTest {

  private static final int[] STRATEGIES = {
    Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY
  };

  private final DeflatePeek peek = new DeflatePeek();

  // ---------------------------------------------------------------------------
  // Valid streams: the result must be exact
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("valid streams")
  class ValidStreams {

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
    @DisplayName("should read every one-byte id at every level and strategy")
    void everyOneByteId(int level) {
      SplittableRandom rng = new SplittableRandom(level);
      for (int strategy : STRATEGIES) {
        for (int id = 0; id < 128; id++) {
          byte[] packet = packet(id, payload(rng, rng.nextInt(0, 4000)));
          assertEquals(id, peek(ZlibStreams.zlib(packet, level, strategy)), "id " + id);
        }
      }
    }

    @ParameterizedTest(name = "id {0}")
    @ValueSource(ints = {128, 255, 300, 16_383, 16_384, 1 << 20, (1 << 21) - 1})
    @DisplayName("should read multi-byte VarInt ids")
    void multiByteIds(int id) {
      SplittableRandom rng = new SplittableRandom(id);
      for (int level = 0; level <= 9; level++) {
        byte[] packet = packet(id, payload(rng, rng.nextInt(0, 3000)));
        assertEquals(id, peek(ZlibStreams.zlib(packet, level, Deflater.DEFAULT_STRATEGY)));
      }
    }

    @Test
    @DisplayName("should match a full inflate on thousands of random packets")
    void matchesFullInflate() {
      SplittableRandom rng = new SplittableRandom(0x5741_5250L);
      for (int i = 0; i < 5_000; i++) {
        int id = rng.nextInt(10) == 0 ? rng.nextInt(1 << 21) : rng.nextInt(128);
        int size =
            switch (rng.nextInt(20)) {
              case 0 -> rng.nextInt(16_384, 131_072);
              case 1, 2, 3, 4, 5 -> rng.nextInt(1024, 16_384);
              default -> rng.nextInt(0, 1024);
            };
        byte[] packet = packet(id, payload(rng, size));
        int level = rng.nextInt(-1, 10);
        int strategy = STRATEGIES[rng.nextInt(STRATEGIES.length)];

        assertEquals(id, peek(ZlibStreams.zlib(packet, level, strategy)), "iteration " + i);
      }
    }

    @Test
    @DisplayName("should read an id stored in an uncompressed block ahead of the payload")
    void storedIdPrefix() throws DataFormatException {
      // The framing a cooperating backend can emit to make peeking free: [stored block: id][rest].
      byte[] packet = packet(0x2A, payload(new SplittableRandom(7), 2000));
      byte[] stream = ZlibStreams.storedPrefix(packet, 1);

      assertArrayEquals(packet, ZlibStreams.inflate(stream, packet.length));
      assertEquals(0x2A, peek(stream));
    }

    @Test
    @DisplayName("should skip a few empty stored blocks before the data")
    void skipsEmptyBlocks() throws DataFormatException {
      byte[] packet = packet(0x33, payload(new SplittableRandom(8), 500));
      byte[] stream = ZlibStreams.withEmptyStoredBlocks(packet, DeflatePeek.MAX_BLOCKS - 1);

      assertArrayEquals(packet, ZlibStreams.inflate(stream, packet.length));
      assertEquals(0x33, peek(stream));
    }

    @Test
    @DisplayName("should read a block declaring every code length, the last run ending on the last")
    void maximalCodeLengths() throws DataFormatException {
      byte[] stream = ZlibStreams.maximalDynamicBlock(0);

      assertArrayEquals(
          new byte[] {ZlibStreams.MAXIMAL_DYNAMIC_BLOCK_DATA}, ZlibStreams.inflate(stream, 1));
      assertEquals(ZlibStreams.MAXIMAL_DYNAMIC_BLOCK_DATA, peek(stream));
    }

    @Test
    @DisplayName("should only read the readable bytes of a larger buffer")
    void respectsBufferBounds() {
      byte[] stream = ZlibStreams.zlib(packet(0x11, new byte[300]), 6, Deflater.DEFAULT_STRATEGY);
      ByteBuf buf = Unpooled.buffer();
      buf.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
      buf.writeBytes(stream);
      buf.writeBytes(new byte[] {(byte) 0xEE, (byte) 0xEE});
      buf.readerIndex(3);
      buf.writerIndex(3 + stream.length);

      assertEquals(0x11, peek.peekVarInt(buf));
      assertEquals(3, buf.readerIndex());
      assertEquals(3 + stream.length, buf.writerIndex());
    }

    @Test
    @DisplayName("should work on direct buffers")
    void directBuffers() {
      byte[] stream = ZlibStreams.zlib(packet(0x7F, new byte[5000]), 6, Deflater.DEFAULT_STRATEGY);
      ByteBuf direct = Unpooled.directBuffer().writeBytes(stream);
      try {
        assertEquals(0x7F, peek.peekVarInt(direct));
      } finally {
        direct.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Streams that cannot or must not be peeked
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("declined streams")
  class DeclinedStreams {

    @Test
    @DisplayName("should decline a stream needing more than MAX_BLOCKS empty blocks")
    void declinesTooManyEmptyBlocks() {
      byte[] packet = packet(0x33, new byte[100]);
      assertEquals(
          DeflatePeek.UNKNOWN,
          peek(ZlibStreams.withEmptyStoredBlocks(packet, DeflatePeek.MAX_BLOCKS)));
    }

    @Test
    @DisplayName("should decline a stream with a preset dictionary")
    void declinesPresetDictionary() {
      // CMF 0x78, FLG 0x20: FDICT set, (0x7820 % 31) == 0; then a dictionary id and a block.
      assertEquals(
          DeflatePeek.UNKNOWN, peek(new byte[] {0x78, 0x20, 0, 0, 0, 1, 0x63, 0x00, 0x00}));
    }

    @Test
    @DisplayName("should decline a header with a bad check value")
    void declinesBadHeaderCheck() {
      byte[] stream = ZlibStreams.zlib(packet(1, new byte[300]), 6, Deflater.DEFAULT_STRATEGY);
      stream[1] ^= 0x01;
      assertEquals(DeflatePeek.UNKNOWN, peek(stream));
    }

    @Test
    @DisplayName("should decline a compression method other than deflate")
    void declinesOtherMethod() {
      assertEquals(DeflatePeek.UNKNOWN, peek(new byte[] {0x77, 0x01, 0x00, 0x00}));
    }

    @Test
    @DisplayName("should decline an empty buffer")
    void declinesEmpty() {
      assertEquals(DeflatePeek.UNKNOWN, peek(new byte[0]));
    }
  }

  // ---------------------------------------------------------------------------
  // Hostile input: never throw, never lie about what was decoded
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("malformed input")
  class MalformedInput {

    @Test
    @DisplayName("should never throw on random bytes")
    void randomBytes() {
      SplittableRandom rng = new SplittableRandom(1);
      for (int i = 0; i < 20_000; i++) {
        byte[] junk = new byte[rng.nextInt(0, 96)];
        rng.nextBytes(junk);
        if (junk.length >= 2 && rng.nextBoolean()) {
          // Valid zlib header half of the time, so the block decoder is exercised too.
          junk[0] = 0x78;
          junk[1] = (byte) 0x9C;
        }
        int result = peek(junk);
        assertTrue(result == DeflatePeek.UNKNOWN || result >= 0);
      }
    }

    @Test
    @DisplayName("should only return correct ids from truncated streams")
    void truncatedStreams() {
      SplittableRandom rng = new SplittableRandom(2);
      for (int i = 0; i < 300; i++) {
        int id = rng.nextInt(1 << 14);
        byte[] stream =
            ZlibStreams.zlib(packet(id, payload(rng, rng.nextInt(0, 2000))), rng.nextInt(0, 10), 0);
        for (int length = 0; length <= Math.min(stream.length, 400); length++) {
          int result = peek(Arrays.copyOf(stream, length));
          assertTrue(result == DeflatePeek.UNKNOWN || result == id, "prefix " + length);
        }
      }
    }

    @ParameterizedTest(name = "{0} past the last")
    @ValueSource(ints = {1, 2, 79})
    @DisplayName("should decline a code-length run going past the declared code lengths")
    void codeLengthRunOverrun(int overrun) {
      byte[] stream = ZlibStreams.maximalDynamicBlock(overrun);

      assertThrows(DataFormatException.class, () -> ZlibStreams.inflate(stream, 1));
      assertEquals(DeflatePeek.UNKNOWN, peek(stream));
    }

    @Test
    @DisplayName("should never throw on bit-flipped streams")
    void bitFlips() {
      SplittableRandom rng = new SplittableRandom(3);
      for (int i = 0; i < 5_000; i++) {
        byte[] stream =
            ZlibStreams.zlib(packet(rng.nextInt(128), payload(rng, rng.nextInt(0, 3000))), 6, 0);
        int bit = rng.nextInt(Math.min(stream.length, 200) * 8);
        stream[bit >>> 3] ^= (byte) (1 << (bit & 7));
        int result = peek(stream);
        assertTrue(result == DeflatePeek.UNKNOWN || result >= 0);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private int peek(byte[] stream) {
    return peek.peekVarInt(Unpooled.wrappedBuffer(stream));
  }

  /** {@code [VarInt id][payload]}. */
  private static byte[] packet(int id, byte[] payload) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 3);
    int value = id;
    while ((value & ~0x7F) != 0) {
      out.write((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    out.write(value);
    out.writeBytes(payload);
    return out.toByteArray();
  }

  /** Payloads with the shapes of real traffic: random, zero-filled, repetitive or text-like. */
  private static byte[] payload(SplittableRandom rng, int size) {
    byte[] bytes = new byte[size];
    switch (rng.nextInt(4)) {
      case 0 -> rng.nextBytes(bytes);
      case 1 -> {
        // zero-filled
      }
      case 2 -> {
        for (int i = 0; i < size; i++) {
          bytes[i] = (byte) (i % 7 == 0 ? rng.nextInt(256) : i % 13);
        }
      }
      default -> {
        String alphabet = "minecraft:stone grass_block player textures {\"text\":";
        for (int i = 0; i < size; i++) {
          bytes[i] = (byte) alphabet.charAt(rng.nextInt(alphabet.length()));
        }
      }
    }
    return bytes;
  }
}
