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

import java.util.Arrays;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Reads the VarInt at the start of a zlib stream — the packet id of a compressed Minecraft frame —
 * without inflating the stream.
 *
 * <p>A compressed frame hides its packet id inside the zlib stream, so a proxy that only needs the
 * id to route the frame would otherwise inflate the whole payload. This class decodes just enough
 * DEFLATE to produce the first one to three bytes: the zlib header, the first block header and, for
 * dynamic-Huffman blocks, the code-length and literal/length codes. It then decodes literals until
 * the VarInt is complete. Distance codes, the window and the Adler-32 trailer are never touched,
 * and no decode tables are built: symbols are resolved canonically, as in Mark Adler's {@code
 * puff.c}.
 *
 * <h3>Contract</h3>
 *
 * <ul>
 *   <li>For a valid zlib stream, the result is exactly what a full inflate would yield, or {@link
 *       #UNKNOWN}.
 *   <li>{@link #UNKNOWN} means "inflate instead": the stream is malformed, uses a preset
 *       dictionary, ends too early, starts with a back-reference (only possible from the second
 *       byte on) or needs more empty blocks than {@value #MAX_BLOCKS} to reach data. It is never an
 *       error.
 *   <li>Malformed input never throws and never reads outside {@code [readerIndex, writerIndex)}.
 *   <li>The work is bounded by the dynamic block header (at most a few hundred bytes of input).
 *   <li>Zero allocation; the source buffer's indices are not modified.
 * </ul>
 *
 * <p>Instances hold scratch tables and are <b>not</b> thread-safe: use one per event loop or per
 * connection.
 */
public final class DeflatePeek {

  /** Returned when the stream cannot be peeked; the caller must inflate the frame instead. */
  public static final int UNKNOWN = -1;

  /** Maximum number of DEFLATE blocks examined before giving up (bounds empty stored blocks). */
  static final int MAX_BLOCKS = 8;

  /** Packet ids are VarInts of at most three bytes (21 bits) in every protocol version. */
  private static final int MAX_VARINT_BYTES = 3;

  private static final int MAX_BITS = 15;
  private static final int MAX_LITERAL_CODES = 286;
  private static final int MAX_DISTANCE_CODES = 30;
  private static final int CODE_LENGTH_CODES = 19;
  private static final int END_OF_BLOCK = 256;

  /** Internal sentinel: the block ended before the VarInt did; continue with the next block. */
  private static final int CONTINUE = -2;

  /** Order in which code-length code lengths are transmitted (RFC 1951 §3.2.7). */
  private static final byte[] CODE_LENGTH_ORDER = {
    16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
  };

  /** Canonical counts and sorted symbols of the fixed literal/length code (RFC 1951 §3.2.6). */
  private static final short[] FIXED_COUNT = new short[MAX_BITS + 1];

  private static final short[] FIXED_SYMBOL = new short[288];

  static {
    short[] lengths = new short[288];
    for (int symbol = 0; symbol < 288; symbol++) {
      lengths[symbol] = (short) (symbol < 144 ? 8 : symbol < 256 ? 9 : symbol < 280 ? 7 : 8);
    }
    if (construct(lengths, 0, 288, FIXED_COUNT, FIXED_SYMBOL, new short[MAX_BITS + 1]) != 0) {
      throw new AssertionError("fixed literal/length code must be complete");
    }
  }

  // ---------------------------------------------------------------------------
  // Scratch state — reused across calls
  // ---------------------------------------------------------------------------

  private final short[] lengths = new short[MAX_LITERAL_CODES + MAX_DISTANCE_CODES];
  private final short[] codeLengthCount = new short[MAX_BITS + 1];
  private final short[] codeLengthSymbol = new short[CODE_LENGTH_CODES];
  private final short[] literalCount = new short[MAX_BITS + 1];
  private final short[] literalSymbol = new short[MAX_LITERAL_CODES];
  private final short[] offsets = new short[MAX_BITS + 1];

  private ByteBuf in;
  private int position;
  private int limit;
  private long bitBuffer;
  private int bitCount;
  private boolean overrun;

  private int varIntValue;
  private int varIntBytes;

  /** Creates a peeker with its own scratch tables. */
  public DeflatePeek() {
    in = Unpooled.EMPTY_BUFFER;
  }

  /**
   * Returns the VarInt encoded by the first bytes of the zlib stream in {@code zlib}'s readable
   * bytes.
   *
   * @param zlib a buffer whose readable bytes are a zlib stream (RFC 1950); indices untouched
   * @return the VarInt value (the packet id of a Minecraft frame), or {@link #UNKNOWN}
   */
  public int peekVarInt(ByteBuf zlib) {
    in = zlib;
    position = zlib.readerIndex();
    limit = zlib.writerIndex();
    bitBuffer = 0;
    bitCount = 0;
    overrun = false;
    varIntValue = 0;
    varIntBytes = 0;
    int result = peekStream();
    in = Unpooled.EMPTY_BUFFER; // do not retain a reference to the caller's buffer
    return result;
  }

  private int peekStream() {
    // zlib header: CM = 8 (deflate), window ≤ 32 KiB, check bits, no preset dictionary.
    int cmf = bits(8);
    int flg = bits(8);
    if (overrun || (cmf & 0x0F) != 8 || (cmf >>> 4) > 7 || ((cmf << 8) | flg) % 31 != 0) {
      return UNKNOWN;
    }
    if ((flg & 0x20) != 0) {
      return UNKNOWN;
    }
    for (int block = 0; block < MAX_BLOCKS; block++) {
      boolean last = bits(1) == 1;
      int type = bits(2);
      if (overrun) {
        return UNKNOWN;
      }
      int result =
          switch (type) {
            case 0 -> stored();
            case 1 -> literals(FIXED_COUNT, FIXED_SYMBOL);
            case 2 -> dynamic();
            default -> UNKNOWN;
          };
      if (result != CONTINUE) {
        return result;
      }
      if (last) {
        return UNKNOWN; // stream ended before the VarInt did
      }
    }
    return UNKNOWN;
  }

  // ---------------------------------------------------------------------------
  // Blocks
  // ---------------------------------------------------------------------------

  private int stored() {
    // Discard the bits up to the next byte boundary, then LEN and its one's complement.
    dropBits(bitCount & 7);
    int length = bits(16);
    int complement = bits(16);
    if (overrun || length != (~complement & 0xFFFF)) {
      return UNKNOWN;
    }
    for (int i = 0; i < length; i++) {
      int value = bits(8);
      if (overrun) {
        return UNKNOWN;
      }
      int result = accept(value);
      if (result != CONTINUE) {
        return result;
      }
    }
    return CONTINUE;
  }

  private int dynamic() {
    int literalCodes = bits(5) + 257;
    int distanceCodes = bits(5) + 1;
    int codeLengthCodes = bits(4) + 4;
    if (overrun || literalCodes > MAX_LITERAL_CODES || distanceCodes > MAX_DISTANCE_CODES) {
      return UNKNOWN;
    }

    // Code-length code: must be complete.
    for (int i = 0; i < CODE_LENGTH_CODES; i++) {
      lengths[CODE_LENGTH_ORDER[i]] = (short) (i < codeLengthCodes ? bits(3) : 0);
    }
    if (overrun
        || construct(lengths, 0, CODE_LENGTH_CODES, codeLengthCount, codeLengthSymbol, offsets)
            != 0) {
      return UNKNOWN;
    }

    // Literal/length and distance code lengths, run-length encoded with the code-length code.
    int total = literalCodes + distanceCodes;
    int index = 0;
    while (index < total) {
      int symbol = decode(codeLengthCount, codeLengthSymbol);
      if (symbol < 0 || overrun) {
        return UNKNOWN;
      }
      if (symbol < 16) {
        lengths[index++] = (short) symbol;
        continue;
      }
      int repeat;
      short length = 0;
      if (symbol == 16) {
        if (index == 0) {
          return UNKNOWN;
        }
        length = lengths[index - 1];
        repeat = 3 + bits(2);
      } else if (symbol == 17) {
        repeat = 3 + bits(3);
      } else {
        repeat = 11 + bits(7);
      }
      if (overrun || index + repeat > total) {
        return UNKNOWN;
      }
      while (repeat-- > 0) {
        lengths[index++] = length;
      }
    }
    if (lengths[END_OF_BLOCK] == 0) {
      return UNKNOWN;
    }

    // Literal/length code. An incomplete code is only legal as a single one-bit code.
    int left = construct(lengths, 0, literalCodes, literalCount, literalSymbol, offsets);
    if (left < 0 || (left > 0 && literalCodes != literalCount[0] + literalCount[1])) {
      return UNKNOWN;
    }
    return literals(literalCount, literalSymbol);
  }

  /** Decodes literals until the VarInt completes, the block ends, or a back-reference appears. */
  private int literals(short[] count, short[] symbols) {
    while (true) {
      int symbol = decode(count, symbols);
      if (symbol < 0 || overrun) {
        return UNKNOWN;
      }
      if (symbol < END_OF_BLOCK) {
        int result = accept(symbol);
        if (result != CONTINUE) {
          return result;
        }
      } else if (symbol == END_OF_BLOCK) {
        return CONTINUE;
      } else {
        return UNKNOWN; // length/distance pair: resolving it would need the window
      }
    }
  }

  /** Appends one decoded byte to the VarInt; returns its value once complete. */
  private int accept(int value) {
    varIntValue |= (value & 0x7F) << (7 * varIntBytes);
    varIntBytes++;
    if ((value & 0x80) == 0) {
      return varIntValue;
    }
    return varIntBytes == MAX_VARINT_BYTES ? UNKNOWN : CONTINUE;
  }

  // ---------------------------------------------------------------------------
  // Canonical Huffman (RFC 1951 §3.2.2), after puff.c
  // ---------------------------------------------------------------------------

  /**
   * Decodes one symbol bit by bit. Codes are stored most-significant bit first, so each new bit is
   * appended on the right of the code read so far.
   *
   * @return the symbol, or {@code -1} if no code of up to 15 bits matches
   */
  private int decode(short[] count, short[] symbols) {
    int code = 0;
    int first = 0;
    int index = 0;
    for (int length = 1; length <= MAX_BITS; length++) {
      code |= bits(1);
      int n = count[length];
      if (code - n < first) {
        return symbols[index + (code - first)];
      }
      index += n;
      first = (first + n) << 1;
      code <<= 1;
    }
    return -1;
  }

  /**
   * Builds canonical decoding data: the number of codes of each length and the symbols sorted by
   * code.
   *
   * @return {@code 0} for a complete code, a positive value for an incomplete one, or a negative
   *     value for an over-subscribed (invalid) one
   */
  private static int construct(
      short[] lengths, int from, int n, short[] count, short[] symbols, short[] offset) {
    Arrays.fill(count, (short) 0);
    for (int symbol = 0; symbol < n; symbol++) {
      count[lengths[from + symbol]]++;
    }
    if (count[0] == n) {
      return 0; // no codes: complete, but nothing can be decoded
    }
    int left = 1;
    for (int length = 1; length <= MAX_BITS; length++) {
      left <<= 1;
      left -= count[length];
      if (left < 0) {
        return left;
      }
    }
    offset[1] = 0;
    for (int length = 1; length < MAX_BITS; length++) {
      offset[length + 1] = (short) (offset[length] + count[length]);
    }
    for (int symbol = 0; symbol < n; symbol++) {
      int length = lengths[from + symbol];
      if (length != 0) {
        symbols[offset[length]++] = (short) symbol;
      }
    }
    return left;
  }

  // ---------------------------------------------------------------------------
  // Bit input (RFC 1951 §3.1.1: bits are packed starting with the least-significant bit)
  // ---------------------------------------------------------------------------

  private int bits(int n) {
    while (bitCount < n) {
      if (position >= limit) {
        overrun = true;
        return 0;
      }
      bitBuffer |= (long) (in.getByte(position++) & 0xFF) << bitCount;
      bitCount += 8;
    }
    int value = (int) (bitBuffer & ((1L << n) - 1));
    bitBuffer >>>= n;
    bitCount -= n;
    return value;
  }

  private void dropBits(int n) {
    bitBuffer >>>= n;
    bitCount -= n;
  }
}
