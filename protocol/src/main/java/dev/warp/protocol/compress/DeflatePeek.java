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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

import io.netty.buffer.ByteBuf;

/**
 * Reads the VarInt at the start of a zlib stream — the packet id of a compressed Minecraft frame —
 * without inflating the stream.
 *
 * <p>A compressed frame hides its packet id inside the zlib stream, so a proxy that only needs the
 * id to route the frame would otherwise inflate the whole payload. This class decodes just enough
 * DEFLATE to produce the first one to three bytes: the zlib header, the first block header and, for
 * dynamic-Huffman blocks, the code-length and literal/length codes. It then decodes literals until
 * the VarInt is complete. Distance symbols, the window and the Adler-32 trailer are never touched.
 *
 * <h3>Why it is fast</h3>
 *
 * <ul>
 *   <li>The first {@value #WINDOW} bytes of the stream — more than any valid block header plus the
 *       first literals need — are copied once into a scratch array, then read 64 bits at a time.
 *   <li>The code-length code (at most 7 bits per code) is decoded through a 128-entry table; code
 *       lengths are counted while they are decoded.
 *   <li>The literal/length code is never tabulated: at most three symbols are needed, so they are
 *       resolved canonically, bit by bit, as in Mark Adler's {@code puff.c}.
 * </ul>
 *
 * <h3>Contract</h3>
 *
 * <ul>
 *   <li>For a valid zlib stream, the result is exactly what a full inflate would yield — VarInt
 *       bytes are always literals, because a back-reference at the start of a stream can only copy
 *       the first byte, and a VarInt never repeats its first byte three times.
 *   <li>{@link #UNKNOWN} means "inflate instead": malformed stream, preset dictionary, stream ends
 *       too early, more than {@value #MAX_BLOCKS} empty blocks, or an id wider than three bytes. It
 *       is never an error.
 *   <li>Malformed input never throws, and nothing outside {@code [readerIndex, writerIndex)} is
 *       read. Code validation follows zlib: over-subscribed codes and incomplete codes other than a
 *       single one-bit code are rejected.
 *   <li>Zero allocation; the source buffer's indices are not modified.
 * </ul>
 *
 * <p>Instances hold scratch tables (~2 KiB) and are <b>not</b> thread-safe: use one per connection
 * or per event loop.
 */
public final class DeflatePeek {

  /** Returned when the stream cannot be peeked; the caller must inflate the frame instead. */
  public static final int UNKNOWN = -1;

  /** Maximum number of DEFLATE blocks examined before giving up (bounds empty stored blocks). */
  static final int MAX_BLOCKS = 8;

  /**
   * Bytes of deflate data examined. A dynamic block header takes at most 560 bytes; with the empty
   * blocks allowed before it and the first literals, every valid stream fits.
   */
  static final int WINDOW = 1024;

  /** Packet ids are VarInts of at most three bytes (21 bits) in every protocol version. */
  private static final int MAX_VARINT_BYTES = 3;

  private static final int MAX_BITS = 15;
  private static final int MAX_LITERAL_CODES = 286;
  private static final int MAX_DISTANCE_CODES = 30;
  private static final int CODE_LENGTH_CODES = 19;
  private static final int END_OF_BLOCK = 256;
  private static final int CODE_LENGTH_TABLE_BITS = 7;
  private static final int FIXED_TABLE_BITS = 9;

  /** Internal sentinel: the block ended before the VarInt did; continue with the next block. */
  private static final int CONTINUE = -2;

  private static final VarHandle LONG_LE =
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

  /** Order in which code-length code lengths are transmitted (RFC 1951 §3.2.7). */
  private static final byte[] CODE_LENGTH_ORDER = {
    16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
  };

  /**
   * Fixed literal/length code (RFC 1951 §3.2.6) as a 9-bit table of {@code symbol << 4 | length}.
   */
  private static final short[] FIXED_TABLE = new short[1 << FIXED_TABLE_BITS];

  static {
    byte[] lengths = new byte[288];
    for (int symbol = 0; symbol < 288; symbol++) {
      lengths[symbol] = (byte) (symbol < 144 ? 8 : symbol < 256 ? 9 : symbol < 280 ? 7 : 8);
    }
    if (!fillTable(FIXED_TABLE, FIXED_TABLE_BITS, lengths, 288, new int[16], new int[16])) {
      throw new AssertionError("fixed literal/length code must be complete");
    }
  }

  // ---------------------------------------------------------------------------
  // Scratch state — reused across calls
  // ---------------------------------------------------------------------------

  /** Copy of the deflate data, followed by 16 zero bytes so 64-bit loads never overrun. */
  private final byte[] window = new byte[WINDOW + 16];

  private final byte[] lengths = new byte[MAX_LITERAL_CODES + MAX_DISTANCE_CODES];
  private final byte[] codeLengthLengths = new byte[CODE_LENGTH_CODES];
  private final short[] codeLengthTable = new short[1 << CODE_LENGTH_TABLE_BITS];

  /**
   * Code counts per length: {@code [0, 16)} literal/length code, {@code [16, 32)} distance code.
   */
  private final int[] counts = new int[32];

  private final int[] tableCounts = new int[16];
  private final int[] nextCodes = new int[16];

  // Bit reader over the window.
  private int limit;
  private int position;
  private long bitBuffer;
  private int bitCount;

  // VarInt being assembled.
  private int varIntValue;
  private int varIntBytes;

  /**
   * Returns the VarInt encoded by the first bytes of the zlib stream in {@code zlib}'s readable
   * bytes.
   *
   * @param zlib a buffer whose readable bytes are a zlib stream (RFC 1950); indices untouched
   * @return the VarInt value (the packet id of a Minecraft frame), or {@link #UNKNOWN}
   */
  public int peekVarInt(ByteBuf zlib) {
    int index = zlib.readerIndex();
    int length = zlib.readableBytes();
    if (length < 3) {
      return UNKNOWN;
    }
    // zlib header: CM = 8 (deflate), window ≤ 32 KiB, check bits, no preset dictionary.
    int cmf = zlib.getUnsignedByte(index);
    int flg = zlib.getUnsignedByte(index + 1);
    if ((cmf & 0x0F) != 8 || (cmf >>> 4) > 7 || ((cmf << 8) | flg) % 31 != 0 || (flg & 0x20) != 0) {
      return UNKNOWN;
    }
    limit = Math.min(length - 2, WINDOW);
    zlib.getBytes(index + 2, window, 0, limit);
    LONG_LE.set(window, limit, 0L);
    LONG_LE.set(window, limit + 8, 0L);
    position = 0;
    bitBuffer = 0;
    bitCount = 0;
    varIntValue = 0;
    varIntBytes = 0;
    return peekBlocks();
  }

  private int peekBlocks() {
    for (int block = 0; block < MAX_BLOCKS; block++) {
      refill();
      boolean last = (bitBuffer & 1) != 0;
      int type = (int) (bitBuffer >>> 1) & 3;
      drop(3);
      int result =
          switch (type) {
            case 0 -> stored();
            case 1 -> literals(true, 0);
            case 2 -> dynamic();
            default -> UNKNOWN;
          };
      if (result != CONTINUE) {
        return result == UNKNOWN || overrun() ? UNKNOWN : result;
      }
      if (last || overrun()) {
        return UNKNOWN; // the stream ended, or ran past the data, before the VarInt did
      }
    }
    return UNKNOWN;
  }

  // ---------------------------------------------------------------------------
  // Blocks
  // ---------------------------------------------------------------------------

  private int stored() {
    drop(bitCount & 7); // to the byte boundary
    refill();
    int length = (int) bitBuffer & 0xFFFF;
    int complement = (int) (bitBuffer >>> 16) & 0xFFFF;
    drop(32);
    if (length != (~complement & 0xFFFF)) {
      return UNKNOWN;
    }
    for (int i = 0; i < length; i++) {
      refill();
      int value = (int) bitBuffer & 0xFF;
      drop(8);
      int result = accept(value);
      if (result != CONTINUE) {
        return result;
      }
    }
    return CONTINUE;
  }

  private int dynamic() {
    refill();
    int literalCodes = ((int) bitBuffer & 31) + 257;
    int distanceCodes = ((int) (bitBuffer >>> 5) & 31) + 1;
    int codeLengthCodes = ((int) (bitBuffer >>> 10) & 15) + 4;
    drop(14);
    if (literalCodes > MAX_LITERAL_CODES || distanceCodes > MAX_DISTANCE_CODES) {
      return UNKNOWN;
    }

    // Code-length code: 3 bits per length, must be complete.
    Arrays.fill(codeLengthLengths, (byte) 0);
    for (int i = 0; i < codeLengthCodes; i++) {
      refill();
      codeLengthLengths[CODE_LENGTH_ORDER[i]] = (byte) (bitBuffer & 7);
      drop(3);
    }
    if (!fillTable(
        codeLengthTable,
        CODE_LENGTH_TABLE_BITS,
        codeLengthLengths,
        CODE_LENGTH_CODES,
        tableCounts,
        nextCodes)) {
      return UNKNOWN;
    }

    // Literal/length and distance code lengths, run-length encoded, counted as they are decoded.
    Arrays.fill(counts, 0);
    int total = literalCodes + distanceCodes;
    int index = 0;
    while (index < total) {
      refill();
      int entry = codeLengthTable[(int) bitBuffer & ((1 << CODE_LENGTH_TABLE_BITS) - 1)];
      drop(entry & 15);
      int symbol = entry >>> 4;
      if (symbol < 16) {
        lengths[index] = (byte) symbol;
        count(symbol, index, literalCodes);
        index++;
        continue;
      }
      int length = 0;
      int repeat;
      if (symbol == 16) {
        if (index == 0) {
          return UNKNOWN;
        }
        length = lengths[index - 1];
        repeat = 3 + ((int) bitBuffer & 3);
        drop(2);
      } else if (symbol == 17) {
        repeat = 3 + ((int) bitBuffer & 7);
        drop(3);
      } else {
        repeat = 11 + ((int) bitBuffer & 127);
        drop(7);
      }
      // The run must end within the declared code lengths. total <= lengths.length (checked
      // above), so this keeps every write below in bounds.
      int end = index + repeat;
      if (end > total) {
        return UNKNOWN;
      }
      for (; index < end; index++) {
        lengths[index] = (byte) length;
        count(length, index, literalCodes);
      }
    }
    if (overrun() || lengths[END_OF_BLOCK] == 0 || !acceptableCode(0) || !acceptableCode(16)) {
      return UNKNOWN;
    }
    return literals(false, literalCodes);
  }

  /**
   * Decodes literals until the VarInt completes, the block ends, or a back-reference appears.
   *
   * @param fixed whether the block uses the fixed code, else the dynamic code in {@link #counts}
   * @param literalCodes the number of literal/length code lengths (dynamic blocks only)
   */
  private int literals(boolean fixed, int literalCodes) {
    while (true) {
      refill();
      int symbol = fixed ? decodeFixed() : decodeDynamic(literalCodes);
      if (symbol < 0) {
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
        return UNKNOWN; // length/distance pair: a VarInt byte is never one
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
  // Huffman codes (RFC 1951 §3.2.2)
  // ---------------------------------------------------------------------------

  private int decodeFixed() {
    int entry = FIXED_TABLE[(int) bitBuffer & ((1 << FIXED_TABLE_BITS) - 1)];
    drop(entry & 15);
    int symbol = entry >>> 4;
    return symbol < MAX_LITERAL_CODES ? symbol : -1;
  }

  /**
   * Decodes one literal/length symbol canonically, bit by bit (codes are stored most-significant
   * bit first), then maps the code's rank among codes of its length to its symbol by scanning the
   * code lengths — cheaper than building a symbol table for the one to three symbols needed.
   */
  private int decodeDynamic(int literalCodes) {
    int code = 0;
    int first = 0;
    for (int length = 1; length <= MAX_BITS; length++) {
      code |= (int) (bitBuffer >>> (length - 1)) & 1;
      int n = counts[length];
      if (code - n < first) {
        drop(length);
        int rank = code - first;
        for (int symbol = 0; symbol < literalCodes; symbol++) {
          if (lengths[symbol] == length && rank-- == 0) {
            return symbol;
          }
        }
        return -1;
      }
      first = (first + n) << 1;
      code <<= 1;
    }
    return -1;
  }

  /** Counts a decoded code length into the literal/length or the distance code. */
  private void count(int length, int index, int literalCodes) {
    if (length != 0) {
      counts[length | (index < literalCodes ? 0 : 16)]++;
    }
  }

  /** zlib's rule: not over-subscribed, and incomplete only for a lone one-bit code. */
  private boolean acceptableCode(int offset) {
    int left = 1;
    int maxLength = 0;
    for (int length = 1; length <= MAX_BITS; length++) {
      int n = counts[offset + length];
      left = (left << 1) - n;
      if (left < 0) {
        return false;
      }
      if (n != 0) {
        maxLength = length;
      }
    }
    return left == 0 || maxLength <= 1;
  }

  /**
   * Fills a lookup table indexed by the next {@code bits} input bits with {@code symbol << 4 |
   * length}. Only complete codes no longer than {@code bits} are accepted.
   */
  private static boolean fillTable(
      short[] table, int bits, byte[] codeLengths, int n, int[] count, int[] next) {
    Arrays.fill(count, 0);
    for (int symbol = 0; symbol < n; symbol++) {
      count[codeLengths[symbol]]++;
    }
    count[0] = 0;
    int left = 1;
    for (int length = 1; length <= MAX_BITS; length++) {
      left = (left << 1) - count[length];
      if (left < 0 || (length > bits && count[length] != 0)) {
        return false;
      }
    }
    if (left != 0) {
      return false;
    }
    next[1] = 0;
    for (int length = 1; length < MAX_BITS; length++) {
      next[length + 1] = (next[length] + count[length]) << 1;
    }
    for (int symbol = 0; symbol < n; symbol++) {
      int length = codeLengths[symbol];
      if (length == 0) {
        continue;
      }
      int code = next[length]++;
      int reversed = Integer.reverse(code) >>> (32 - length);
      short entry = (short) (symbol << 4 | length);
      for (int i = reversed; i < table.length; i += 1 << length) {
        table[i] = entry;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------------------
  // Bit input (RFC 1951 §3.1.1: bits are packed starting with the least-significant bit)
  // ---------------------------------------------------------------------------

  /**
   * Tops the bit buffer up to at least 56 bits with one 64-bit load (bits above the count are the
   * next bytes, so loading them again is harmless). Past the copied data it loads the zero padding,
   * which {@link #overrun()} detects; it never reads past the padding.
   */
  private void refill() {
    if (position > limit + 8) {
      bitCount = 63; // far past the data: keep feeding zeros, overrun() rejects the result
      return;
    }
    bitBuffer |= (long) LONG_LE.get(window, position) << bitCount;
    position += (63 - bitCount) >>> 3;
    bitCount |= 56;
  }

  private void drop(int n) {
    bitBuffer >>>= n;
    bitCount -= n;
  }

  /** Whether more bits were consumed than the copied data holds. */
  private boolean overrun() {
    return (long) position * 8 - bitCount > (long) limit * 8;
  }
}
