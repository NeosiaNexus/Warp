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
import java.util.HexFormat;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
  // Hand-made streams: every rule of RFC 1950 and 1951, at its edge
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("hand-made streams")
  class HandMadeStreams {

    private static final int ID = 0x2A;
    private static final int END_OF_BLOCK = 256;

    @Test
    @DisplayName("should read an id coded with 15-bit codes, the longest DEFLATE allows")
    void longestCodes() {
      // A complete code: end-of-block on 1 bit, then one symbol on each length up to 13, the
      // id's last byte on 14 bits and its first two bytes on 15.
      int[] literals = new int[257];
      literals[END_OF_BLOCK] = 1;
      for (int length = 2; length <= 13; length++) {
        literals[0x10 + length] = length;
      }
      literals[0x03] = 14;
      literals[0x81] = 15;
      literals[0x82] = 15;
      byte[] stream =
          new DeflateWriter()
              .dynamic(true, literals, new int[1], 0x81, 0x82, 0x03, END_OF_BLOCK)
              .toByteArray();

      assertDecodes(0x01 | 0x02 << 7 | 0x03 << 14, stream);
    }

    @Test
    @DisplayName("should read an id after a block whose only code is end-of-block, on one bit")
    void loneLiteralCode() {
      int[] literals = new int[257];
      literals[END_OF_BLOCK] = 1;
      byte[] stream =
          new DeflateWriter()
              .dynamic(false, literals, new int[1], END_OF_BLOCK)
              .fixed(true, ID, END_OF_BLOCK)
              .toByteArray();

      assertDecodes(ID, stream);
    }

    @Test
    @DisplayName("should read an id from a block whose distance code is a lone one-bit code")
    void loneDistanceCode() {
      byte[] stream =
          new DeflateWriter()
              .dynamic(true, literals(ID, END_OF_BLOCK), new int[] {1}, ID, END_OF_BLOCK)
              .toByteArray();

      assertDecodes(ID, stream);
    }

    @ParameterizedTest(name = "id {0}, split after {1} byte(s), level {2}")
    @MethodSource("flushedIds")
    @DisplayName("should read an id that a flush splits across blocks")
    void idSplitByFlush(int id, int split, int level) {
      byte[] packet = packet(id, new byte[100]);

      assertDecodes(id, ZlibStreams.flushedAfter(packet, split, level));
    }

    static Stream<Arguments> flushedIds() {
      return IntStream.rangeClosed(0, 9)
          .boxed()
          .flatMap(
              level ->
                  Stream.of(
                      Arguments.of(300, 1, level),
                      Arguments.of((1 << 21) - 1, 1, level),
                      Arguments.of((1 << 21) - 1, 2, level)));
    }

    @Test
    @DisplayName("should read a three-byte id from a stored block")
    void threeByteIdStored() {
      int id = (1 << 21) - 1;

      assertDecodes(id, ZlibStreams.storedPrefix(packet(id, new byte[50]), 3));
    }

    @Test
    @DisplayName("should read an id whose last bit is the last bit of the buffer")
    void idEndsTheBuffer() {
      byte[] stream = new DeflateWriter().stored(false, (byte) ID).toByteArray();

      assertDecodes(ID, stream);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedByZlib")
    @DisplayName("should decline what zlib rejects, even when an id could be decoded")
    void declinesWhatZlibRejects(byte[] stream) {
      assertEquals(OptionalInt.empty(), inflatedVarInt(stream), "zlib must reject it");
      assertEquals(DeflatePeek.UNKNOWN, peek(stream));
    }

    static Stream<Named<byte[]>> rejectedByZlib() {
      int[] noDistance = new int[1];
      int[] complete = literals(ID, END_OF_BLOCK);
      int[] tooManyLiterals = Arrays.copyOf(complete, 287);
      int[] endOfBlockOnly = new int[257];
      endOfBlockOnly[END_OF_BLOCK] = 1;
      return Stream.of(
          Named.of(
              "compression method 7",
              new DeflateWriter(0x77, 0x09).fixed(true, ID, END_OF_BLOCK).toByteArray()),
          Named.of(
              "preset dictionary whose id bytes form a block",
              new DeflateWriter(0x78, 0x20).fixed(true, ID, END_OF_BLOCK).toByteArray()),
          Named.of(
              "window above 32 KiB",
              new DeflateWriter(0x88, 0x1C).fixed(true, ID, END_OF_BLOCK).toByteArray()),
          Named.of(
              "over-subscribed literal/length code",
              dynamic(literals(ID, END_OF_BLOCK, 0x01), noDistance)),
          Named.of(
              "incomplete literal/length code",
              dynamic(lengths(257, 2, ID, END_OF_BLOCK), noDistance)),
          Named.of("over-subscribed distance code", dynamic(complete, new int[] {1, 1, 1})),
          Named.of("incomplete distance code", dynamic(complete, new int[] {2, 2})),
          Named.of(
              "no end-of-block code",
              new DeflateWriter().dynamic(true, literals(ID, 0x01), noDistance, ID).toByteArray()),
          Named.of("287 literal/length codes", dynamic(tooManyLiterals, noDistance)),
          Named.of("31 distance codes", dynamic(complete, new int[31])),
          Named.of(
              "unused code of a lone one-bit code",
              new DeflateWriter()
                  .dynamic(true, endOfBlockOnly, noDistance)
                  .bits(1, 1)
                  .toByteArray()),
          Named.of(
              "data after the final block",
              new DeflateWriter()
                  .fixed(true, 0x80, END_OF_BLOCK)
                  .fixed(true, 0x05, END_OF_BLOCK)
                  .toByteArray()));
    }

    @Test
    @DisplayName("should decline an invalid code-length code, even right after a valid one")
    void invalidCodeLengthCode() {
      // The same block twice, except that the second announces an over-subscribed code-length
      // code: decoding it with the first block's code would yield the id.
      int[] literals = literals(ID, END_OF_BLOCK);
      assertDecodes(ID, dynamic(literals, new int[1]));

      int[] overSubscribed = new int[19];
      Arrays.fill(overSubscribed, 1);
      DeflateWriter invalid = new DeflateWriter().dynamicHeader(true, 257, 1, overSubscribed);
      for (int length : literals) {
        invalid.code(length, 4);
      }
      byte[] stream = invalid.code(0, 4).code(0, 1).code(1, 1).toByteArray();

      assertEquals(OptionalInt.empty(), inflatedVarInt(stream), "zlib must reject it");
      assertEquals(DeflatePeek.UNKNOWN, peek(stream));
    }

    /** Asserts that zlib inflates {@code id} from {@code stream}, and that peeking reads it too. */
    private void assertDecodes(int id, byte[] stream) {
      assertEquals(OptionalInt.of(id), inflatedVarInt(stream), "zlib must inflate the id");
      assertEquals(id, peek(stream));
    }

    /** A final dynamic block coding the id then end-of-block with the given code lengths. */
    private static byte[] dynamic(int[] literals, int[] distances) {
      return new DeflateWriter().dynamic(true, literals, distances, ID, END_OF_BLOCK).toByteArray();
    }

    /** 257 literal/length code lengths giving the symbols one bit each, nothing else a code. */
    private static int[] literals(int... symbols) {
      return lengths(257, 1, symbols);
    }

    private static int[] lengths(int count, int length, int... symbols) {
      int[] lengths = new int[count];
      for (int symbol : symbols) {
        lengths[symbol] = length;
      }
      return lengths;
    }
  }

  // ---------------------------------------------------------------------------
  // Hostile input: never throw, never lie about what was decoded
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("malformed input")
  class MalformedInput {

    @Test
    @DisplayName("should never throw on random bytes, nor claim an id zlib would not inflate")
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
        assertNeverLies(junk);
      }
    }

    @Test
    @DisplayName("should never claim an id zlib would not inflate, whatever the block contents")
    void randomBlockContents() {
      SplittableRandom rng = new SplittableRandom(4);
      // BFINAL set, then each BTYPE: stored, fixed Huffman, dynamic Huffman and reserved.
      int[] blockHeaders = {0b001, 0b011, 0b101, 0b111};
      for (int i = 0; i < 10_000; i++) {
        byte[] stream = new byte[rng.nextInt(3, 160)];
        rng.nextBytes(stream);
        stream[0] = 0x78;
        stream[1] = (byte) 0x9C;
        stream[2] = (byte) ((stream[2] & ~0b111) | blockHeaders[i & 3]);
        assertNeverLies(stream);
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
    @DisplayName(
        "should never throw on bit-flipped streams, nor claim an id zlib would not inflate")
    void bitFlips() {
      SplittableRandom rng = new SplittableRandom(3);
      for (int i = 0; i < 5_000; i++) {
        byte[] stream =
            ZlibStreams.zlib(packet(rng.nextInt(128), payload(rng, rng.nextInt(0, 3000))), 6, 0);
        int bit = rng.nextInt(Math.min(stream.length, 200) * 8);
        stream[bit >>> 3] ^= (byte) (1 << (bit & 7));
        assertNeverLies(stream);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private int peek(byte[] stream) {
    return peek.peekVarInt(Unpooled.wrappedBuffer(stream));
  }

  /**
   * Asserts the contract on any input: the result is {@link DeflatePeek#UNKNOWN}, or exactly the
   * VarInt zlib inflates from the stream.
   */
  private void assertNeverLies(byte[] stream) {
    int result = peek(stream);
    if (result != DeflatePeek.UNKNOWN) {
      assertEquals(
          OptionalInt.of(result),
          inflatedVarInt(stream),
          () -> "peeked " + result + " from " + HexFormat.of().formatHex(stream));
    }
  }

  /**
   * Returns the VarInt formed by the first bytes zlib inflates from {@code stream}, including bytes
   * it inflates before finding an error further on (a full inflate of the frame fails later, which
   * is the caller's business); empty if zlib produces too few bytes or the VarInt is wider than
   * three bytes.
   */
  private static OptionalInt inflatedVarInt(byte[] stream) {
    byte[] bytes = new byte[3];
    int produced = 0;
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(stream);
      int n;
      do {
        n = inflater.inflate(bytes, produced, bytes.length - produced);
        produced += n;
      } while (n > 0 && produced < bytes.length);
    } catch (DataFormatException e) {
      // zlib found an error further on: the bytes it wrote before still count.
      produced = (int) inflater.getBytesWritten();
    } finally {
      inflater.end();
    }
    int value = 0;
    for (int i = 0; i < produced; i++) {
      value |= (bytes[i] & 0x7F) << (7 * i);
      if (bytes[i] >= 0) {
        return OptionalInt.of(value);
      }
    }
    return OptionalInt.empty();
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
