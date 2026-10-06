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

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Builds zlib streams for tests, including hand-assembled shapes no encoder emits by default. */
public final class ZlibStreams {

  private ZlibStreams() {}

  /**
   * Compresses {@code data} into a zlib stream with the JDK's zlib.
   *
   * @param data the bytes to compress
   * @param level the zlib level
   * @param strategy the zlib strategy
   * @return the zlib stream
   */
  public static byte[] zlib(byte[] data, int level, int strategy) {
    Deflater deflater = new Deflater(level);
    deflater.setStrategy(strategy);
    try {
      deflater.setInput(data);
      deflater.finish();
      return drain(deflater, data.length);
    } finally {
      deflater.end();
    }
  }

  /**
   * Builds a zlib stream that starts with {@code count} empty stored blocks.
   *
   * @param data the bytes the stream decodes to
   * @param count the number of empty stored blocks before the data
   * @return the zlib stream
   */
  public static byte[] withEmptyStoredBlocks(byte[] data, int count) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x78);
    out.write(0x9C);
    for (int i = 0; i < count; i++) {
      // BFINAL=0, BTYPE=00, padding to the byte boundary, LEN=0, NLEN=0xFFFF.
      out.writeBytes(new byte[] {0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF});
    }
    out.writeBytes(rawDeflate(data));
    writeAdler(out, data);
    return out.toByteArray();
  }

  /**
   * Builds a zlib stream whose first {@code prefix} bytes sit in a leading stored block — the
   * framing a cooperating backend can emit to make the packet id readable without decoding.
   *
   * @param data the bytes the stream decodes to
   * @param prefix the number of leading bytes stored uncompressed (below 256)
   * @return the zlib stream
   */
  public static byte[] storedPrefix(byte[] data, int prefix) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x78);
    out.write(0x9C);
    out.writeBytes(new byte[] {0x00, (byte) prefix, 0x00, (byte) ~prefix, (byte) 0xFF});
    out.write(data, 0, prefix);
    out.writeBytes(rawDeflate(Arrays.copyOfRange(data, prefix, data.length)));
    writeAdler(out, data);
    return out.toByteArray();
  }

  /** The byte that {@link #maximalDynamicBlock(int)} decodes to. */
  public static final byte MAXIMAL_DYNAMIC_BLOCK_DATA = 0x2A;

  /** The longest run of zero code lengths a single code-length symbol 18 can encode. */
  private static final int MAX_ZERO_RUN = 138;

  /**
   * Builds a zlib stream of one dynamic-Huffman block declaring the most code lengths DEFLATE
   * allows, 286 literal/length and 30 distance, that decodes to {@link
   * #MAXIMAL_DYNAMIC_BLOCK_DATA}. Its code lengths end with a run of zeros (code-length symbol 18)
   * that crosses from the literal/length into the distance code lengths and ends {@code overrun}
   * entries past the last declared one: 0 gives a valid stream, anything more a malformed one.
   *
   * @param overrun how far the last run goes past the declared code lengths, from 0 to 79
   * @return the zlib stream
   */
  public static byte[] maximalDynamicBlock(int overrun) {
    int lastRun = 29 + 30 + overrun; // literal/length codes 257 to 285, then the 30 distance codes
    if (overrun < 0 || lastRun > MAX_ZERO_RUN) {
      throw new IllegalArgumentException("overrun must be in [0, 79]: " + overrun);
    }
    BitWriter bits = new BitWriter();
    bits.write(1, 1); // BFINAL
    bits.write(2, 2); // BTYPE: dynamic Huffman
    bits.write(286 - 257, 5); // HLIT
    bits.write(30 - 1, 5); // HDIST
    bits.write(18 - 4, 4); // HCLEN: the first 18 code-length code lengths of the order below
    // Code-length code: symbols 18 (third in transmission order) and 1 (eighteenth), one bit each,
    // so symbol 1 is code 0 and symbol 18 is code 1.
    for (int position = 0; position < 18; position++) {
      bits.write(position == 2 || position == 17 ? 1 : 0, 3);
    }
    // Literal/length code: the data byte and end-of-block, one bit each; no distance code.
    zeroRun(bits, MAXIMAL_DYNAMIC_BLOCK_DATA);
    bits.write(0, 1); // length 1 for the data byte
    zeroRun(bits, MAX_ZERO_RUN);
    zeroRun(bits, 255 - MAXIMAL_DYNAMIC_BLOCK_DATA - MAX_ZERO_RUN);
    bits.write(0, 1); // length 1 for end-of-block (256)
    zeroRun(bits, lastRun);
    // Data: the byte (code 0), then end-of-block (code 1).
    bits.write(0, 1);
    bits.write(1, 1);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0x78);
    out.write(0x9C);
    out.writeBytes(bits.toByteArray());
    writeAdler(out, new byte[] {MAXIMAL_DYNAMIC_BLOCK_DATA});
    return out.toByteArray();
  }

  /**
   * Inflates a zlib stream that must decode to exactly {@code size} bytes.
   *
   * @param stream the zlib stream
   * @param size the expected size
   * @return the inflated bytes
   * @throws DataFormatException if the stream is invalid
   */
  public static byte[] inflate(byte[] stream, int size) throws DataFormatException {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(stream);
      byte[] out = new byte[size];
      int produced = inflater.inflate(out);
      if (produced != size || !inflater.finished()) {
        throw new DataFormatException("expected " + size + " bytes, got " + produced);
      }
      return out;
    } finally {
      inflater.end();
    }
  }

  private static byte[] rawDeflate(byte[] data) {
    Deflater deflater = new Deflater(6, true);
    try {
      deflater.setInput(data);
      deflater.finish();
      return drain(deflater, data.length);
    } finally {
      deflater.end();
    }
  }

  private static byte[] drain(Deflater deflater, int inputLength) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(inputLength / 2 + 64);
    byte[] chunk = new byte[8192];
    while (!deflater.finished()) {
      out.write(chunk, 0, deflater.deflate(chunk));
    }
    return out.toByteArray();
  }

  private static void writeAdler(ByteArrayOutputStream out, byte[] data) {
    Adler32 adler = new Adler32();
    adler.update(data);
    long value = adler.getValue();
    out.write((int) (value >>> 24));
    out.write((int) (value >>> 16));
    out.write((int) (value >>> 8));
    out.write((int) value);
  }

  /** Writes code-length symbol 18 (code 1 in {@link #maximalDynamicBlock}): 11 to 138 zeros. */
  private static void zeroRun(BitWriter bits, int count) {
    bits.write(1, 1);
    bits.write(count - 11, 7);
  }

  /** DEFLATE bit packing (RFC 1951 §3.1.1): least-significant bit first. */
  private static final class BitWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int buffer;
    private int count;

    void write(int value, int bits) {
      for (int i = 0; i < bits; i++) {
        buffer |= ((value >>> i) & 1) << count;
        if (++count == 8) {
          out.write(buffer);
          buffer = 0;
          count = 0;
        }
      }
    }

    byte[] toByteArray() {
      if (count > 0) {
        out.write(buffer);
        buffer = 0;
        count = 0;
      }
      return out.toByteArray();
    }
  }
}
