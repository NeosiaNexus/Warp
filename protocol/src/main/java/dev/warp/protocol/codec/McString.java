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

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.DecoderException;

/**
 * High-performance String codec for the Minecraft protocol.
 *
 * <p>Strings are encoded as a VarInt byte-length prefix followed by UTF-8 payload. The protocol
 * caps strings at {@value #DEFAULT_MAX_CHARS} UTF-16 code units, yielding a maximum wire size of
 * 98,304 bytes ({@value #DEFAULT_MAX_CHARS} × 3 + 3).
 *
 * <p><b>Read path</b> validates four properties in sequence:
 *
 * <ol>
 *   <li>Byte length is non-negative
 *   <li>Byte length does not exceed {@code maxChars * 3} (UTF-8 worst case)
 *   <li>Buffer contains at least that many readable bytes
 *   <li>Decoded character count does not exceed {@code maxChars}
 * </ol>
 *
 * <p>The byte-length pre-check rejects oversized payloads <em>before</em> decoding, preventing both
 * allocation bombs and wasted CPU on malicious input.
 *
 * <p><b>Write path</b> uses {@link ByteBufUtil#utf8Bytes(CharSequence)} for O(n) byte-count
 * calculation followed by {@link ByteBuf#writeCharSequence(CharSequence, java.nio.charset.Charset)}
 * for single-pass encoding — avoiding the intermediate {@code byte[]} allocation found in
 * alternative proxy implementations.
 *
 * <p>Common error paths use cached {@link DecoderException} instances to eliminate garbage on the
 * read hot path.
 */
public final class McString {

  /** Default maximum string length in UTF-16 code units, per the Minecraft protocol spec. */
  public static final int DEFAULT_MAX_CHARS = 32_767;

  /**
   * Maximum UTF-8 bytes per UTF-16 code unit. BMP characters (U+0000–U+FFFF) use 1–3 bytes;
   * supplementary characters use a surrogate pair (2 code units) for 4 bytes, which is still ≤ 3
   * bytes per code unit.
   */
  private static final int MAX_UTF8_BYTES_PER_CHAR = 3;

  // ---------------------------------------------------------------------------
  // Cached errors — zero-alloc on the read hot path
  // ---------------------------------------------------------------------------

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException NEGATIVE_LENGTH =
      cachedDecoderException("String byte length is negative");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException BYTE_LIMIT_EXCEEDED =
      cachedDecoderException("String byte length exceeds maximum for character limit");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException NOT_ENOUGH_DATA =
      cachedDecoderException("Not enough readable bytes for string payload");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException CHAR_LIMIT_EXCEEDED =
      cachedDecoderException("Decoded string exceeds maximum character count");

  private McString() {}

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  /**
   * Reads a string from the buffer using the default maximum of {@value #DEFAULT_MAX_CHARS}
   * characters.
   *
   * @param buf the buffer to read from
   * @return the decoded string
   * @throws DecoderException if the string is malformed or exceeds the default size limit
   */
  public static String read(ByteBuf buf) {
    return read(buf, DEFAULT_MAX_CHARS);
  }

  /**
   * Reads a string from the buffer with a custom character limit.
   *
   * <p>Validation order: non-negative byte length → byte length within UTF-8 bound → buffer has
   * enough data → decoded character count within limit. This rejects oversized payloads as early as
   * possible, before touching buffer contents.
   *
   * @param buf the buffer to read from
   * @param maxChars the maximum number of UTF-16 code units allowed
   * @return the decoded string
   * @throws DecoderException if the string is malformed or exceeds the size limit
   */
  public static String read(ByteBuf buf, int maxChars) {
    int byteLength = VarInt.read(buf);
    if (byteLength < 0) {
      throw NEGATIVE_LENGTH;
    }
    if (byteLength > (long) maxChars * MAX_UTF8_BYTES_PER_CHAR) {
      throw BYTE_LIMIT_EXCEEDED;
    }
    if (!buf.isReadable(byteLength)) {
      throw NOT_ENOUGH_DATA;
    }

    String str = buf.toString(buf.readerIndex(), byteLength, StandardCharsets.UTF_8);
    buf.skipBytes(byteLength);

    if (str.length() > maxChars) {
      throw CHAR_LIMIT_EXCEEDED;
    }
    return str;
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  /**
   * Writes a string to the buffer using the default maximum of {@value #DEFAULT_MAX_CHARS}
   * characters.
   *
   * @param buf the buffer to write to
   * @param str the string to encode
   * @throws IllegalArgumentException if the string exceeds the default size limit
   */
  public static void write(ByteBuf buf, String str) {
    write(buf, str, DEFAULT_MAX_CHARS);
  }

  /**
   * Writes a string to the buffer with a custom character limit.
   *
   * <p>Character count is validated before encoding. The UTF-8 byte count is computed via {@link
   * ByteBufUtil#utf8Bytes(CharSequence)} (pure arithmetic, no allocation), then written as a VarInt
   * prefix followed by the UTF-8 payload via {@link ByteBuf#writeCharSequence}.
   *
   * @param buf the buffer to write to
   * @param str the string to encode
   * @param maxChars the maximum number of UTF-16 code units allowed
   * @throws IllegalArgumentException if the string exceeds the size limit
   */
  public static void write(ByteBuf buf, String str, int maxChars) {
    if (str.length() > maxChars) {
      throw new IllegalArgumentException(
          "String too long: " + str.length() + " characters (max " + maxChars + ")");
    }
    int byteCount = ByteBufUtil.utf8Bytes(str);
    VarInt.write(buf, byteCount);
    buf.writeCharSequence(str, StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------
  // Size
  // ---------------------------------------------------------------------------

  /**
   * Returns the total encoded size of the string in bytes (VarInt prefix + UTF-8 payload).
   *
   * <p>Useful for pre-calculating packet sizes without writing to a buffer.
   *
   * @param str the string to measure
   * @return the total wire size in bytes
   */
  public static int encodedSize(String str) {
    int byteCount = ByteBufUtil.utf8Bytes(str);
    return VarInt.size(byteCount) + byteCount;
  }

  // ---------------------------------------------------------------------------
  // Skip
  // ---------------------------------------------------------------------------

  /**
   * Advances the reader index past a string without decoding it.
   *
   * <p>Faster than {@link #read} when the string value is not needed — avoids UTF-8 decoding and
   * {@link String} allocation. Useful for blind forwarding where only field positions matter.
   *
   * @param buf the buffer to skip in
   * @throws DecoderException if the length prefix is negative or the buffer is truncated
   */
  public static void skip(ByteBuf buf) {
    int byteLength = VarInt.read(buf);
    if (byteLength < 0) {
      throw NEGATIVE_LENGTH;
    }
    if (!buf.isReadable(byteLength)) {
      throw NOT_ENOUGH_DATA;
    }
    buf.skipBytes(byteLength);
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
