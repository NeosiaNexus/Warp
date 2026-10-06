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

/**
 * Assembles zlib streams (RFC 1950) block by block and bit by bit (RFC 1951), including shapes no
 * encoder emits: malformed codes, codes at DEFLATE's limits, data after the final block. The
 * Adler-32 trailer is left out: streams end with their last block.
 */
public final class DeflateWriter {

  /** Order in which code-length code lengths are transmitted (RFC 1951 §3.2.7). */
  private static final int[] CODE_LENGTH_ORDER = {
    16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
  };

  /** A complete code-length code: symbols 0 to 15 (no runs), four bits each. */
  private static final int[] FOUR_BIT_CODE_LENGTH_CODE = new int[19];

  /** The fixed literal/length code lengths (RFC 1951 §3.2.6). */
  private static final int[] FIXED_LENGTHS = new int[288];

  static {
    Arrays.fill(FOUR_BIT_CODE_LENGTH_CODE, 0, 16, 4);
    for (int symbol = 0; symbol < FIXED_LENGTHS.length; symbol++) {
      FIXED_LENGTHS[symbol] = symbol < 144 ? 8 : symbol < 256 ? 9 : symbol < 280 ? 7 : 8;
    }
  }

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private int bitBuffer;
  private int bitCount;

  /** Starts a stream with the usual zlib header: deflate, 32 KiB window, no dictionary. */
  public DeflateWriter() {
    this(0x78, 0x9C);
  }

  /**
   * Starts a stream with the given zlib header bytes.
   *
   * @param cmf the compression method and info byte
   * @param flg the flags byte
   */
  public DeflateWriter(int cmf, int flg) {
    out.write(cmf);
    out.write(flg);
  }

  /**
   * Appends a stored block.
   *
   * @param last whether the block is the final one
   * @param data the bytes it stores
   * @return this writer
   */
  public DeflateWriter stored(boolean last, byte... data) {
    blockHeader(last, 0);
    align();
    bits(data.length, 16);
    bits(~data.length, 16);
    out.writeBytes(data);
    return this;
  }

  /**
   * Appends a fixed-Huffman block coding the given literal/length symbols (without extra bits).
   *
   * @param last whether the block is the final one
   * @param symbols the symbols, end-of-block (256) included where wanted
   * @return this writer
   */
  public DeflateWriter fixed(boolean last, int... symbols) {
    blockHeader(last, 1);
    return symbols(FIXED_LENGTHS, symbols);
  }

  /**
   * Appends a dynamic-Huffman block sending the given code lengths, which need not form valid
   * codes, then the given literal/length symbols coded with them.
   *
   * @param last whether the block is the final one
   * @param literalLengths the literal/length code lengths (257 to 288 of them)
   * @param distanceLengths the distance code lengths (1 to 32 of them)
   * @param symbols the symbols, end-of-block (256) included where wanted
   * @return this writer
   */
  public DeflateWriter dynamic(
      boolean last, int[] literalLengths, int[] distanceLengths, int... symbols) {
    dynamicHeader(last, literalLengths.length, distanceLengths.length, FOUR_BIT_CODE_LENGTH_CODE);
    for (int length : literalLengths) {
      code(length, 4);
    }
    for (int length : distanceLengths) {
      code(length, 4);
    }
    return symbols(literalLengths, symbols);
  }

  /**
   * Appends the start of a dynamic-Huffman block, up to its code-length code: the code lengths
   * themselves are then written with {@link #code} and {@link #bits}.
   *
   * @param last whether the block is the final one
   * @param literalCodes the number of literal/length code lengths announced (HLIT + 257)
   * @param distanceCodes the number of distance code lengths announced (HDIST + 1)
   * @param codeLengthCode the 19 code-length code lengths, indexed by symbol
   * @return this writer
   */
  public DeflateWriter dynamicHeader(
      boolean last, int literalCodes, int distanceCodes, int[] codeLengthCode) {
    blockHeader(last, 2);
    bits(literalCodes - 257, 5);
    bits(distanceCodes - 1, 5);
    bits(CODE_LENGTH_ORDER.length - 4, 4);
    for (int symbol : CODE_LENGTH_ORDER) {
      bits(codeLengthCode[symbol], 3);
    }
    return this;
  }

  /**
   * Appends a Huffman code, most significant bit first.
   *
   * @param code the code
   * @param length its length in bits
   * @return this writer
   */
  public DeflateWriter code(int code, int length) {
    for (int bit = length - 1; bit >= 0; bit--) {
      bits(code >>> bit, 1);
    }
    return this;
  }

  /**
   * Appends the low bits of a value, least significant bit first.
   *
   * @param value the value
   * @param count how many of its bits
   * @return this writer
   */
  public DeflateWriter bits(int value, int count) {
    for (int i = 0; i < count; i++) {
      bitBuffer |= ((value >>> i) & 1) << bitCount;
      if (++bitCount == 8) {
        out.write(bitBuffer);
        bitBuffer = 0;
        bitCount = 0;
      }
    }
    return this;
  }

  /**
   * Returns the stream written so far, its last byte padded with zero bits.
   *
   * @return the zlib stream
   */
  public byte[] toByteArray() {
    align();
    return out.toByteArray();
  }

  /** The canonical Huffman codes for the given code lengths, 0 for unused (RFC 1951 §3.2.2). */
  private static int[] canonicalCodes(int[] lengths) {
    int[] count = new int[16];
    for (int length : lengths) {
      count[length]++;
    }
    count[0] = 0;
    int[] next = new int[16];
    for (int length = 1, code = 0; length < 16; length++) {
      code = (code + count[length - 1]) << 1;
      next[length] = code;
    }
    int[] codes = new int[lengths.length];
    for (int symbol = 0; symbol < lengths.length; symbol++) {
      if (lengths[symbol] != 0) {
        codes[symbol] = next[lengths[symbol]]++;
      }
    }
    return codes;
  }

  private DeflateWriter symbols(int[] lengths, int[] symbols) {
    int[] codes = canonicalCodes(lengths);
    for (int symbol : symbols) {
      code(codes[symbol], lengths[symbol]);
    }
    return this;
  }

  private void blockHeader(boolean last, int type) {
    bits(last ? 1 : 0, 1);
    bits(type, 2);
  }

  private void align() {
    if (bitCount > 0) {
      bits(0, 8 - bitCount);
    }
  }
}
