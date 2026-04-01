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
 * High-performance VarInt codec for the Minecraft protocol.
 *
 * <p>VarInts encode 32-bit signed integers using 1–5 bytes of LEB128 encoding (7 data bits + 1
 * continuation bit per byte).
 *
 * <p><b>Read path</b> uses an unrolled XOR-chain technique (from Google Protobuf): Java's sign
 * extension of {@code byte → int} naturally signals continuation, and a single XOR at the end
 * cleans up accumulated sign bits — eliminating the per-byte {@code & 0x7F} mask found in naïve
 * implementations.
 *
 * <p><b>Write path</b> uses bulk Netty writes ({@link ByteBuf#writeShort}, {@link
 * ByteBuf#writeMedium}, {@link ByteBuf#writeInt}) to reduce buffer method calls from N to 1–2, and
 * tests the original value to avoid a data-dependency chain from {@code >>>= 7}.
 *
 * <p><b>Size</b> is computed in O(1) via a branchless lookup table indexed by {@link
 * Integer#numberOfLeadingZeros}.
 */
public final class VarInt {

  /** Maximum number of bytes a VarInt can occupy. */
  public static final int MAX_BYTES = 5;

  /** Maximum value encodable in a 3-byte VarInt ({@code 2,097,151}), used for packet lengths. */
  public static final int MAX_21_BIT = 0x1FFFFF;

  // ---------------------------------------------------------------------------
  // XOR cleanup masks — each cancels accumulated sign-extension for N bytes
  // ---------------------------------------------------------------------------

  private static final int CLEANUP_2 = ~0 << 7;
  private static final int CLEANUP_3 = CLEANUP_2 ^ (~0 << 14);
  private static final int CLEANUP_4 = CLEANUP_3 ^ (~0 << 21);
  private static final int CLEANUP_5 = CLEANUP_4 ^ (~0 << 28);

  // ---------------------------------------------------------------------------
  // Branchless size lookup
  // ---------------------------------------------------------------------------

  /**
   * {@code SIZE_TABLE[Integer.numberOfLeadingZeros(value)]} gives the encoded byte count. 33 ints =
   * 132 bytes, fits in a single cache line.
   */
  private static final int[] SIZE_TABLE = {
    5,
    5,
    5,
    5, // nlz 0-3: 29-32 significant bits
    4,
    4,
    4,
    4,
    4,
    4,
    4, // nlz 4-10: 22-28 significant bits
    3,
    3,
    3,
    3,
    3,
    3,
    3, // nlz 11-17: 15-21 significant bits
    2,
    2,
    2,
    2,
    2,
    2,
    2, // nlz 18-24: 8-14 significant bits
    1,
    1,
    1,
    1,
    1,
    1,
    1, // nlz 25-31: 1-7 significant bits
    1 // nlz 32: value 0
  };

  // ---------------------------------------------------------------------------
  // Cached error — zero-alloc on the hot error path
  // ---------------------------------------------------------------------------

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException BAD_VARINT =
      new DecoderException("Bad VarInt") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this; // no-op — avoid stale stack trace from class init
        }
      };

  private VarInt() {}

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  /**
   * Reads a VarInt from the buffer, advancing the reader index by the encoded length.
   *
   * @param buf the buffer to read from
   * @return the decoded 32-bit integer
   * @throws DecoderException if the VarInt exceeds 5 bytes or the buffer is truncated
   */
  public static int read(ByteBuf buf) {
    int index = buf.readerIndex();
    if (buf.writerIndex() - index >= MAX_BYTES) {
      return readFast(buf, index);
    }
    return readSafe(buf, index);
  }

  /**
   * Fast path: at least 5 readable bytes guaranteed, so no per-byte bounds checking. Uses the
   * Protobuf XOR-chain — sign extension of each byte provides the continuation signal; a single XOR
   * at the end cleans up.
   */
  private static int readFast(ByteBuf buf, int index) {
    int x = buf.getByte(index++);
    if (x >= 0) {
      buf.readerIndex(index);
      return x;
    }

    x ^= buf.getByte(index++) << 7;
    if (x < 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_2;
    }

    x ^= buf.getByte(index++) << 14;
    if (x >= 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_3;
    }

    x ^= buf.getByte(index++) << 21;
    if (x < 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_4;
    }

    int b4 = buf.getByte(index++);
    x ^= b4 << 28;
    if (b4 < 0) {
      throw BAD_VARINT;
    }
    buf.readerIndex(index);
    return x ^ CLEANUP_5;
  }

  /** Safe path: fewer than 5 bytes may be available, bounds checked before each byte read. */
  private static int readSafe(ByteBuf buf, int index) {
    int end = buf.writerIndex();

    if (index >= end) {
      throw BAD_VARINT;
    }
    int x = buf.getByte(index++);
    if (x >= 0) {
      buf.readerIndex(index);
      return x;
    }

    if (index >= end) {
      throw BAD_VARINT;
    }
    x ^= buf.getByte(index++) << 7;
    if (x < 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_2;
    }

    if (index >= end) {
      throw BAD_VARINT;
    }
    x ^= buf.getByte(index++) << 14;
    if (x >= 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_3;
    }

    if (index >= end) {
      throw BAD_VARINT;
    }
    x ^= buf.getByte(index++) << 21;
    if (x < 0) {
      buf.readerIndex(index);
      return x ^ CLEANUP_4;
    }

    if (index >= end) {
      throw BAD_VARINT;
    }
    int b4 = buf.getByte(index++);
    x ^= b4 << 28;
    if (b4 < 0) {
      throw BAD_VARINT;
    }
    buf.readerIndex(index);
    return x ^ CLEANUP_5;
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  /**
   * Writes a VarInt to the buffer, advancing the writer index by the encoded length.
   *
   * <p>The 1-byte and 2-byte fast paths are inlined; 3–5-byte values are delegated to {@link
   * #writeFull} to keep this method small for JIT inlining.
   *
   * @param buf the buffer to write to
   * @param value the 32-bit integer to encode
   */
  public static void write(ByteBuf buf, int value) {
    if ((value & (0xFFFFFFFF << 7)) == 0) {
      buf.writeByte(value);
    } else if ((value & (0xFFFFFFFF << 14)) == 0) {
      buf.writeShort(((value & 0x7F) | 0x80) << 8 | (value >>> 7));
    } else {
      writeFull(buf, value);
    }
  }

  /** Handles 3-byte, 4-byte, and 5-byte VarInt writes. */
  private static void writeFull(ByteBuf buf, int value) {
    if ((value & (0xFFFFFFFF << 21)) == 0) {
      buf.writeMedium(
          ((value & 0x7F) | 0x80) << 16 | (((value >>> 7) & 0x7F) | 0x80) << 8 | (value >>> 14));
    } else if ((value & (0xFFFFFFFF << 28)) == 0) {
      buf.writeInt(
          ((value & 0x7F) | 0x80) << 24
              | (((value >>> 7) & 0x7F) | 0x80) << 16
              | (((value >>> 14) & 0x7F) | 0x80) << 8
              | (value >>> 21));
    } else {
      buf.writeInt(
          ((value & 0x7F) | 0x80) << 24
              | (((value >>> 7) & 0x7F) | 0x80) << 16
              | (((value >>> 14) & 0x7F) | 0x80) << 8
              | (((value >>> 21) & 0x7F) | 0x80));
      buf.writeByte(value >>> 28);
    }
  }

  // ---------------------------------------------------------------------------
  // Size
  // ---------------------------------------------------------------------------

  /**
   * Returns the number of bytes needed to encode the given value as a VarInt.
   *
   * <p>Branchless O(1): single table lookup via {@link Integer#numberOfLeadingZeros}.
   *
   * @param value the 32-bit integer to measure
   * @return the byte count (1–5)
   */
  public static int size(int value) {
    return SIZE_TABLE[Integer.numberOfLeadingZeros(value)];
  }

  // ---------------------------------------------------------------------------
  // Skip
  // ---------------------------------------------------------------------------

  /**
   * Advances the reader index past a VarInt without decoding it.
   *
   * <p>Faster than {@link #read} when the value is not needed — avoids the XOR accumulation and
   * cleanup. Useful for blind forwarding where only the packet ID position matters.
   *
   * @param buf the buffer to skip in
   * @throws DecoderException if the VarInt exceeds 5 bytes or the buffer is truncated
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
    throw BAD_VARINT;
  }

  // ---------------------------------------------------------------------------
  // Packet-length helper
  // ---------------------------------------------------------------------------

  /**
   * Pre-encodes a 21-bit value as a 3-byte VarInt suitable for {@link ByteBuf#writeMedium(int)}.
   *
   * <p>This always produces a 3-byte encoding (overlong for small values), which is valid per the
   * Minecraft protocol spec. It is intended for packet-length framing where a fixed-size prefix
   * simplifies the encoder.
   *
   * @param value the value to encode (0 to {@value #MAX_21_BIT})
   * @return the encoded 3-byte VarInt packed into an int
   * @throws IllegalArgumentException if value is negative or exceeds 21 bits
   */
  public static int encode21Bit(int value) {
    if (value < 0 || value > MAX_21_BIT) {
      throw new IllegalArgumentException(
          "Value " + value + " out of 21-bit VarInt range [0, " + MAX_21_BIT + "]");
    }
    return ((value & 0x7F) | 0x80) << 16 | (((value >>> 7) & 0x7F) | 0x80) << 8 | (value >>> 14);
  }
}
