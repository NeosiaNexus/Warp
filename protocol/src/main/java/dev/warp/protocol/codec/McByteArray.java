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
 * Byte-array codec for the Minecraft protocol.
 *
 * <p>Byte arrays are encoded as a VarInt length prefix followed by the raw bytes. This codec
 * provides length validation and cached error paths to protect against oversized allocation
 * attacks.
 *
 * <p>The default maximum size is {@value #DEFAULT_MAX_SIZE} bytes. Callers should specify tighter
 * per-packet limits where the protocol defines them.
 */
public final class McByteArray {

  /** Default maximum array size in bytes (1 MiB). */
  public static final int DEFAULT_MAX_SIZE = 1_048_576;

  // ---------------------------------------------------------------------------
  // Cached errors — zero-alloc on the read hot path
  // ---------------------------------------------------------------------------

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException NEGATIVE_LENGTH =
      cachedDecoderException("Byte array length is negative");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException TOO_LARGE =
      cachedDecoderException("Byte array length exceeds maximum allowed size");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException NOT_ENOUGH_DATA =
      cachedDecoderException("Not enough readable bytes for byte array payload");

  private McByteArray() {}

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  /**
   * Reads a byte array from the buffer using the default maximum of {@value #DEFAULT_MAX_SIZE}
   * bytes.
   *
   * @param buf the buffer to read from
   * @return the decoded byte array
   * @throws DecoderException if the array is malformed or exceeds the default size limit
   */
  public static byte[] read(ByteBuf buf) {
    return read(buf, DEFAULT_MAX_SIZE);
  }

  /**
   * Reads a byte array from the buffer with a custom size limit.
   *
   * <p>Validates the length prefix before allocating, preventing oversized allocation attacks from
   * malicious VarInt length values.
   *
   * @param buf the buffer to read from
   * @param maxSize the maximum number of bytes allowed
   * @return the decoded byte array
   * @throws DecoderException if the array is malformed or exceeds the size limit
   */
  public static byte[] read(ByteBuf buf, int maxSize) {
    int length = VarInt.read(buf);
    if (length < 0) {
      throw NEGATIVE_LENGTH;
    }
    if (length > maxSize) {
      throw TOO_LARGE;
    }
    if (!buf.isReadable(length)) {
      throw NOT_ENOUGH_DATA;
    }
    byte[] array = new byte[length];
    buf.readBytes(array);
    return array;
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  /**
   * Writes a byte array to the buffer as a VarInt length prefix followed by the raw bytes.
   *
   * @param buf the buffer to write to
   * @param array the byte array to encode
   */
  public static void write(ByteBuf buf, byte[] array) {
    VarInt.write(buf, array.length);
    buf.writeBytes(array);
  }

  // ---------------------------------------------------------------------------
  // Short-prefixed (1.7.x wire format)
  // ---------------------------------------------------------------------------

  /**
   * Reads a byte array with an unsigned-short length prefix (Minecraft 1.7.x wire format).
   *
   * @param buf the buffer to read from
   * @param maxSize the maximum array size
   * @return the decoded byte array
   * @throws DecoderException if the array is malformed or exceeds the size limit
   */
  public static byte[] readShortPrefixed(ByteBuf buf, int maxSize) {
    int length = buf.readUnsignedShort();
    if (length > maxSize) {
      throw TOO_LARGE;
    }
    if (!buf.isReadable(length)) {
      throw NOT_ENOUGH_DATA;
    }
    byte[] array = new byte[length];
    buf.readBytes(array);
    return array;
  }

  /**
   * Writes a byte array with an unsigned-short length prefix (Minecraft 1.7.x wire format).
   *
   * @param buf the buffer to write to
   * @param array the byte array to encode
   */
  public static void writeShortPrefixed(ByteBuf buf, byte[] array) {
    buf.writeShort(array.length);
    buf.writeBytes(array);
  }

  // ---------------------------------------------------------------------------
  // Size
  // ---------------------------------------------------------------------------

  /**
   * Returns the total encoded size of the byte array in bytes (VarInt prefix + payload).
   *
   * @param array the byte array to measure
   * @return the total wire size in bytes
   */
  public static int encodedSize(byte[] array) {
    return VarInt.size(array.length) + array.length;
  }

  // ---------------------------------------------------------------------------
  // Skip
  // ---------------------------------------------------------------------------

  /**
   * Advances the reader index past a byte array without reading it into memory.
   *
   * <p>Faster than {@link #read} when the array contents are not needed — avoids the {@code byte[]}
   * heap allocation entirely.
   *
   * @param buf the buffer to skip in
   * @throws DecoderException if the length prefix is negative or the buffer is truncated
   */
  public static void skip(ByteBuf buf) {
    int length = VarInt.read(buf);
    if (length < 0) {
      throw NEGATIVE_LENGTH;
    }
    if (!buf.isReadable(length)) {
      throw NOT_ENOUGH_DATA;
    }
    buf.skipBytes(length);
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private static DecoderException cachedDecoderException(String message) {
    return new DecoderException(message) {
      @Override
      public synchronized Throwable fillInStackTrace() {
        return this;
      }
    };
  }
}
