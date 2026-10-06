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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.fuzz.FuzzSeeds;
import dev.warp.protocol.fuzz.HeapAllocations;
import dev.warp.protocol.fuzz.Wire;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fuzzes {@link DeflatePeek} against a full inflate by the JDK's zlib: on raw bytes, which reach
 * every malformed shape, and on streams zlib compressed itself, which peeking must always read.
 */
@DisplayName("DeflatePeek fuzzing")
class DeflatePeekFuzzTest {

  /** Bytes on each side of the stream: a peek that strays into them reads them. */
  private static final int GUARD = 16;

  /** Flushing at this choice means not flushing: the stream is a single block, as vanilla's. */
  private static final int NO_FLUSH = 255;

  private static final byte[] DICTIONARY = "minecraft:".getBytes(StandardCharsets.US_ASCII);

  /** How zlib compresses the input. Choice 0 takes the input as the stream itself. */
  private static final Encoding[] ENCODINGS = {
    new Encoding(0, Deflater.DEFAULT_STRATEGY, false),
    new Encoding(1, Deflater.DEFAULT_STRATEGY, false),
    new Encoding(6, Deflater.DEFAULT_STRATEGY, false),
    new Encoding(9, Deflater.DEFAULT_STRATEGY, false),
    new Encoding(6, Deflater.FILTERED, false),
    new Encoding(6, Deflater.HUFFMAN_ONLY, false),
    new Encoding(6, Deflater.DEFAULT_STRATEGY, true),
  };

  /** One instance for every stream, as a connection reuses its own. */
  private static final DeflatePeek PEEK = new DeflatePeek();

  @FuzzTest
  @DisplayName(
      "should peek what a full inflate yields, or give up, reading nothing past the stream")
  void peek(FuzzedDataProvider data) {
    int encoding = data.consumeInt(0, ENCODINGS.length);
    int flushAt = data.consumeInt(0, NO_FLUSH);
    byte[] input = data.consumeRemainingAsBytes();
    byte[] zlib = encoding == 0 ? input : ENCODINGS[encoding - 1].compress(input, flushAt);

    ByteBuf buf = guarded(zlib, 0x00);
    int peeked = PEEK.peekVarInt(buf);
    assertEquals(GUARD, buf.readerIndex(), "reader index moved");
    assertEquals(GUARD + zlib.length, buf.writerIndex(), "writer index moved");
    assertEquals(
        peeked,
        new DeflatePeek().peekVarInt(guarded(zlib, 0xFF)),
        "depends on the bytes around the stream, or on the previous stream");
    assertTrue(peeked == DeflatePeek.UNKNOWN || (peeked >= 0 && peeked < 1 << 21), "range");
    HeapAllocations.assertAtMost(0, () -> PEEK.peekVarInt(buf));

    byte @Nullable [] start = inflatedStart(zlib);
    if (start == null) {
      assertEquals(0, encoding, "zlib cannot inflate its own stream");
      return;
    }
    Wire.VarNum id = Wire.varNum(start, 0, 3);
    int expected = id == null ? DeflatePeek.UNKNOWN : (int) id.value();
    boolean presetDictionary = (zlib[1] & 0x20) != 0;
    if (presetDictionary) {
      assertEquals(DeflatePeek.UNKNOWN, peeked, "peeked past a preset dictionary");
    } else if (encoding != 0) {
      assertEquals(expected, peeked, "gave up on a stream zlib produced");
    } else if (peeked != DeflatePeek.UNKNOWN) {
      assertEquals(expected, peeked, "peeked another id than inflating yields");
    }
  }

  @Test
  @DisplayName("should keep its checked-in seeds up to date")
  void seeds() throws IOException {
    byte[] packet = packet(new byte[] {0x28}, 200);
    // Choices: encoding (0 for the input itself), then where to flush.
    FuzzSeeds seeds = new FuzzSeeds(DeflatePeekFuzzTest.class, "peek");
    String[] names = {
      "stored", "level-1", "level-6", "level-9", "filtered", "huffman-only", "preset-dictionary"
    };
    for (int encoding = 1; encoding <= ENCODINGS.length; encoding++) {
      seeds.add(names[encoding - 1], packet, encoding, NO_FLUSH);
    }
    byte[] twoByteId = packet(new byte[] {(byte) 0x80, 0x01}, 200);
    seeds
        .add("level-6-flushed-first", packet, 3, 0)
        .add("level-6-id-split-across-blocks", twoByteId, 3, 1)
        .add("three-byte-id", packet(new byte[] {(byte) 0xff, (byte) 0xff, 0x7f}, 50), 3, NO_FLUSH)
        .add(
            "four-byte-id",
            packet(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, 1}, 50),
            3,
            NO_FLUSH)
        .add("raw-empty-stored-blocks", ZlibStreams.withEmptyStoredBlocks(packet, 7), 0, NO_FLUSH)
        .add("raw-stored-prefix", ZlibStreams.storedPrefix(twoByteId, 2), 0, NO_FLUSH)
        .add("raw-maximal-dynamic-block", ZlibStreams.maximalDynamicBlock(0), 0, NO_FLUSH)
        .add("raw-overrunning-dynamic-block", ZlibStreams.maximalDynamicBlock(1), 0, NO_FLUSH)
        .verify();
  }

  /**
   * The first bytes of what {@code zlib} inflates to, or {@code null} if it is not a valid stream.
   */
  private static byte @Nullable [] inflatedStart(byte[] zlib) {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(zlib);
      byte[] chunk = new byte[64 * 1024];
      byte[] start = null;
      while (!inflater.finished()) {
        int inflated = inflater.inflate(chunk);
        if (start == null && inflated > 0) {
          start = Arrays.copyOf(chunk, Math.min(inflated, 3));
        }
        if (inflated == 0 && !inflater.finished()) {
          if (!inflater.needsDictionary()) {
            return null; // truncated: it needs input that will never come
          }
          inflater.setDictionary(DICTIONARY);
        }
      }
      return start == null ? new byte[0] : start;
    } catch (DataFormatException | IllegalArgumentException malformed) {
      return null; // IllegalArgumentException: a preset dictionary other than ours
    } finally {
      inflater.end();
    }
  }

  private static ByteBuf guarded(byte[] bytes, int guard) {
    byte[] array = new byte[GUARD + bytes.length + GUARD];
    Arrays.fill(array, (byte) guard);
    System.arraycopy(bytes, 0, array, GUARD, bytes.length);
    return Unpooled.wrappedBuffer(array).setIndex(GUARD, GUARD + bytes.length);
  }

  /** A packet: its id bytes, then a compressible body of {@code size} bytes. */
  private static byte[] packet(byte[] id, int size) {
    byte[] packet = Arrays.copyOf(id, id.length + size);
    for (int i = id.length; i < packet.length; i++) {
      packet[i] = (byte) (i % 13 * 7);
    }
    return packet;
  }

  /** A way of compressing with the JDK's zlib, possibly flushing a block early. */
  private record Encoding(int level, int strategy, boolean presetDictionary) {

    byte[] compress(byte[] data, int flushAt) {
      Deflater deflater = new Deflater(level);
      try {
        deflater.setStrategy(strategy);
        if (presetDictionary) {
          deflater.setDictionary(DICTIONARY);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int split = flushAt == NO_FLUSH ? 0 : Math.min(flushAt, data.length);
        if (flushAt != NO_FLUSH) {
          // A sync flush ends the block, then adds an empty stored one.
          deflater.setInput(data, 0, split);
          int written;
          do {
            written = deflater.deflate(chunk, 0, chunk.length, Deflater.SYNC_FLUSH);
            out.write(chunk, 0, written);
          } while (written == chunk.length);
        }
        deflater.setInput(data, split, data.length - split);
        deflater.finish();
        while (!deflater.finished()) {
          out.write(chunk, 0, deflater.deflate(chunk));
        }
        return out.toByteArray();
      } finally {
        deflater.end();
      }
    }
  }
}
