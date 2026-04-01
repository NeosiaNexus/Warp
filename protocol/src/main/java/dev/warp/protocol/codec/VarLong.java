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
package dev.warp.protocol.codec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * High-performance VarLong codec for the Minecraft protocol.
 *
 * <p>VarLongs encode 64-bit signed integers using 1–10 bytes of LEB128 encoding (7 data bits + 1
 * continuation bit per byte).
 *
 * <p>The read path uses the same unrolled XOR-chain technique as {@link VarInt}, extended to 10
 * bytes. Bytes 0–4 are processed in the inlined fast path; bytes 5–9 are delegated to a separate
 * method to keep the common case small for JIT inlining.
 *
 * <p>The write path inlines 1–3 byte bulk writes and falls back to a byte-at-a-time loop for 4–10
 * byte values (rare in the Minecraft protocol).
 *
 * <p>Size is computed in O(1) via a branchless lookup table indexed by {@link
 * Long#numberOfLeadingZeros}.
 */
public final class VarLong {

  /** Maximum number of bytes a VarLong can occupy. */
  public static final int MAX_BYTES = 10;

  // ---------------------------------------------------------------------------
  // XOR cleanup masks — each cancels accumulated sign-extension for N bytes
  // ---------------------------------------------------------------------------

  private static final long CLEANUP_2 = ~0L << 7;
  private static final long CLEANUP_3 = CLEANUP_2 ^ (~0L << 14);
  private static final long CLEANUP_4 = CLEANUP_3 ^ (~0L << 21);
  private static final long CLEANUP_5 = CLEANUP_4 ^ (~0L << 28);
  private static final long CLEANUP_6 = CLEANUP_5 ^ (~0L << 35);
  private static final long CLEANUP_7 = CLEANUP_6 ^ (~0L << 42);
  private static final long CLEANUP_8 = CLEANUP_7 ^ (~0L << 49);
  private static final long CLEANUP_9 = CLEANUP_8 ^ (~0L << 56);
  private static final long CLEANUP_10 = CLEANUP_9 ^ (~0L << 63);

  // ---------------------------------------------------------------------------
  // Branchless size lookup
  // ---------------------------------------------------------------------------

  /**
   * {@code SIZE_TABLE[Long.numberOfLeadingZeros(value)]} gives the encoded byte count. 65 ints =
   * 260 bytes, fits in 5 cache lines.
   */
  private static final int[] SIZE_TABLE = {
    10, // nlz 0: 64 bits
    9,
    9,
    9,
    9,
    9,
    9,
    9, // nlz 1-7: 57-63 bits
    8,
    8,
    8,
    8,
    8,
    8,
    8, // nlz 8-14: 50-56 bits
    7,
    7,
    7,
    7,
    7,
    7,
    7, // nlz 15-21: 43-49 bits
    6,
    6,
    6,
    6,
    6,
    6,
    6, // nlz 22-28: 36-42 bits
    5,
    5,
    5,
    5,
    5,
    5,
    5, // nlz 29-35: 29-35 bits
    4,
    4,
    4,
    4,
    4,
    4,
    4, // nlz 36-42: 22-28 bits
    3,
    3,
    3,
    3,
    3,
    3,
    3, // nlz 43-49: 15-21 bits
    2,
    2,
    2,
    2,
    2,
    2,
    2, // nlz 50-56: 8-14 bits
    1,
    1,
    1,
    1,
    1,
    1,
    1, // nlz 57-63: 1-7 bits
    1 // nlz 64: value 0
  };

  // ---------------------------------------------------------------------------
  // Cached error
  // ---------------------------------------------------------------------------

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException BAD_VARLONG =
      new DecoderException("Bad VarLong") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this; // no-op — avoid stale stack trace from class init
        }
      };

  private VarLong() {}

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  /**
   * Reads a VarLong from the buffer, advancing the reader index by the encoded length.
   *
   * @param buf the buffer to read from
   * @return the decoded 64-bit integer
   * @throws DecoderException if the VarLong exceeds 10 bytes or the buffer is truncated
   */
  public static long read(ByteBuf buf) {
    int index = buf.readerIndex();
    if (buf.writerIndex() - index >= MAX_BYTES) {
      return readFast(buf, index);
    }
    return readSafe(buf, index);
  }

  /**
   * Fast path: at least 10 readable bytes guaranteed. Bytes 0–4 are handled here (most VarLongs in
   * Minecraft fit in 5 bytes); bytes 5–9 are delegated to {@link #readFastHigh}.
   */
  private static long readFast(ByteBuf buf, int index) {
    // Byte 0 (shift 0)
    long x = buf.getByte(index++);
    if (x >= 0) {
      buf.readerIndex(index);
      return x;
    }

    // Byte 1 (shift 7) — int-width shift, sign-extended to long for XOR
    x ^= buf.getByte(index++) << 7;
    if (x < 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_2;
    }

    // Byte 2 (shift 14)
    x ^= buf.getByte(index++) << 14;
    if (x >= 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_3;
    }

    // Byte 3 (shift 21)
    x ^= buf.getByte(index++) << 21;
    if (x < 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_4;
    }

    // Byte 4 (shift 28) — long-width shift required from here
    x ^= (long) buf.getByte(index++) << 28;
    if (x >= 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_5;
    }

    // Bytes 5–9: delegated to keep this method small for JIT inlining
    return readFastHigh(buf, index, x);
  }

  /** Continuation of the fast read path for bytes 5–9 (shifts 35–63). */
  private static long readFastHigh(ByteBuf buf, int index, long x) {
    // Byte 5 (shift 35)
    x ^= (long) buf.getByte(index++) << 35;
    if (x < 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_6;
    }

    // Byte 6 (shift 42)
    x ^= (long) buf.getByte(index++) << 42;
    if (x >= 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_7;
    }

    // Byte 7 (shift 49)
    x ^= (long) buf.getByte(index++) << 49;
    if (x < 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_8;
    }

    // Byte 8 (shift 56)
    x ^= (long) buf.getByte(index++) << 56;
    if (x >= 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_9;
    }

    // Byte 9 (shift 63) — final byte, continuation bit = error
    int b9 = buf.getByte(index++);
    x ^= (long) b9 << 63;
    if (b9 < 0) {
      throw BAD_VARLONG;
    }
    buf.readerIndex(index);
    return x ^ CLEANUP_10;
  }

  /**
   * Safe path: fewer than 10 bytes may be available. Uses a traditional loop with per-byte bounds
   * checking and masking.
   */
  private static long readSafe(ByteBuf buf, int index) {
    int end = buf.writerIndex();
    long result = 0;
    for (int shift = 0; shift < 64; shift += 7) {
      if (index >= end) {
        throw BAD_VARLONG;
      }
      long b = buf.getByte(index++);
      result |= (b & 0x7FL) << shift;
      if (b >= 0) {
        buf.readerIndex(index);
        return result;
      }
    }
    throw BAD_VARLONG;
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  /**
   * Writes a VarLong to the buffer, advancing the writer index by the encoded length.
   *
   * <p>The 1-byte, 2-byte, and 3-byte fast paths use bulk Netty writes. Larger values (rare in the
   * Minecraft protocol) fall back to a byte-at-a-time loop.
   *
   * @param buf the buffer to write to
   * @param value the 64-bit integer to encode
   */
  public static void write(ByteBuf buf, long value) {
    if ((value & (0xFFFFFFFFFFFFFFFFL << 7)) == 0) {
      buf.writeByte((int) value);
    } else if ((value & (0xFFFFFFFFFFFFFFFFL << 14)) == 0) {
      buf.writeShort((int) (((value & 0x7F) | 0x80) << 8 | (value >>> 7)));
    } else if ((value & (0xFFFFFFFFFFFFFFFFL << 21)) == 0) {
      buf.writeMedium(
          (int)
              (((value & 0x7F) | 0x80) << 16
                  | (((value >>> 7) & 0x7F) | 0x80) << 8
                  | (value >>> 14)));
    } else {
      writeFull(buf, value);
    }
  }

  /** Handles 4–10 byte VarLong writes with a byte-at-a-time loop. */
  private static void writeFull(ByteBuf buf, long value) {
    while (true) {
      if ((value & ~0x7FL) == 0) {
        buf.writeByte((int) value);
        return;
      }
      buf.writeByte((int) (value & 0x7F) | 0x80);
      value >>>= 7;
    }
  }

  // ---------------------------------------------------------------------------
  // Skip
  // ---------------------------------------------------------------------------

  /**
   * Advances the reader index past a VarLong without decoding it.
   *
   * <p>Faster than {@link #read} when the value is not needed — avoids the XOR accumulation and
   * cleanup.
   *
   * @param buf the buffer to skip in
   * @throws DecoderException if the VarLong exceeds 10 bytes or the buffer is truncated
   */
  public static void skip(ByteBuf buf) {
    int index = buf.readerIndex();
    int end = Math.min(index + MAX_BYTES, buf.writerIndex());
    while (index < end) {
      if (buf.getByte(index++) >= 0) {
        buf.readerIndex(index);
        return;
      }
    }
    throw BAD_VARLONG;
  }

  // ---------------------------------------------------------------------------
  // Size
  // ---------------------------------------------------------------------------

  /**
   * Returns the number of bytes needed to encode the given value as a VarLong.
   *
   * <p>Branchless O(1): single table lookup via {@link Long#numberOfLeadingZeros}.
   *
   * @param value the 64-bit integer to measure
   * @return the byte count (1–10)
   */
  public static int size(long value) {
    return SIZE_TABLE[Long.numberOfLeadingZeros(value)];
  }
}
