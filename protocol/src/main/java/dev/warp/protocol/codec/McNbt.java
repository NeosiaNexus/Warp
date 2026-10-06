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
 * NBT codec for the Minecraft protocol, as far as the proxy needs it: it finds where a tag ends, so
 * that a packet can carry the tag verbatim without building it into objects.
 *
 * <p>Before 1.20.2 the protocol writes a root tag the way a file does: a type ID, a name (an
 * unsigned short length and modified UTF-8 bytes), then the payload. A lone {@code TAG_End} type ID
 * stands for "no tag". 1.20.2 drops the root name; this class reads the named form, the only one
 * the proxy needs (registries and dimension types in Join Game and Respawn before 1.20.2).
 *
 * <p>Skipping walks the tag structure without allocating. Nesting is limited to {@value #MAX_DEPTH}
 * levels, like vanilla, and every length is checked against the readable bytes before the reader
 * index moves.
 */
public final class McNbt {

  /**
   * Deepest nesting accepted, the limit vanilla applies: the root tag is at depth 0, the tags in a
   * list or compound one level below it.
   */
  public static final int MAX_DEPTH = 512;

  private static final int TAG_END = 0;
  private static final int TAG_BYTE = 1;
  private static final int TAG_SHORT = 2;
  private static final int TAG_INT = 3;
  private static final int TAG_LONG = 4;
  private static final int TAG_FLOAT = 5;
  private static final int TAG_DOUBLE = 6;
  private static final int TAG_BYTE_ARRAY = 7;
  private static final int TAG_STRING = 8;
  private static final int TAG_LIST = 9;
  private static final int TAG_COMPOUND = 10;
  private static final int TAG_INT_ARRAY = 11;
  private static final int TAG_LONG_ARRAY = 12;

  // ---------------------------------------------------------------------------
  // Cached errors: zero-alloc on the read path
  // ---------------------------------------------------------------------------

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException NEGATIVE_LENGTH =
      cachedDecoderException("NBT array or list length is negative");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException NOT_ENOUGH_DATA =
      cachedDecoderException("Not enough readable bytes for NBT tag");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException TOO_DEEP =
      cachedDecoderException("NBT tag nested deeper than " + MAX_DEPTH + " levels");

  private McNbt() {}

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  /**
   * Reads one named root tag and returns its exact bytes (type ID, name and payload), ready to be
   * written back with {@link ByteBuf#writeBytes(byte[])}.
   *
   * @param buf the buffer to read from
   * @return the encoded tag
   * @throws DecoderException if the tag is malformed, truncated or nested too deeply
   */
  public static byte[] readNamed(ByteBuf buf) {
    int start = buf.readerIndex();
    skipNamed(buf);
    byte[] tag = new byte[buf.readerIndex() - start];
    buf.getBytes(start, tag);
    return tag;
  }

  // ---------------------------------------------------------------------------
  // Skip
  // ---------------------------------------------------------------------------

  /**
   * Advances the reader index past one named root tag without decoding it.
   *
   * @param buf the buffer to skip in
   * @throws DecoderException if the tag is malformed, truncated or nested too deeply
   */
  public static void skipNamed(ByteBuf buf) {
    int type = readType(buf);
    if (type == TAG_END) {
      return;
    }
    skipString(buf);
    skipPayload(buf, type, 0);
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private static void skipPayload(ByteBuf buf, int type, int depth) {
    switch (type) {
      case TAG_BYTE -> skip(buf, Byte.BYTES);
      case TAG_SHORT -> skip(buf, Short.BYTES);
      case TAG_INT, TAG_FLOAT -> skip(buf, Integer.BYTES);
      case TAG_LONG, TAG_DOUBLE -> skip(buf, Long.BYTES);
      case TAG_BYTE_ARRAY -> skip(buf, (long) readLength(buf) * Byte.BYTES);
      case TAG_INT_ARRAY -> skip(buf, (long) readLength(buf) * Integer.BYTES);
      case TAG_LONG_ARRAY -> skip(buf, (long) readLength(buf) * Long.BYTES);
      case TAG_STRING -> skipString(buf);
      case TAG_LIST -> skipList(buf, depth);
      case TAG_COMPOUND -> skipCompound(buf, depth);
      default -> throw new DecoderException("Unknown NBT tag type " + type);
    }
  }

  private static void skipList(ByteBuf buf, int depth) {
    checkDepth(depth);
    int elementType = readType(buf);
    int count = readLength(buf);
    switch (elementType) {
      // Fixed-size elements: one bounds check for the whole list.
      case TAG_BYTE -> skip(buf, (long) count * Byte.BYTES);
      case TAG_SHORT -> skip(buf, (long) count * Short.BYTES);
      case TAG_INT, TAG_FLOAT -> skip(buf, (long) count * Integer.BYTES);
      case TAG_LONG, TAG_DOUBLE -> skip(buf, (long) count * Long.BYTES);
      case TAG_END -> {
        if (count != 0) {
          throw new DecoderException("NBT list of TAG_End with " + count + " elements");
        }
      }
      default -> {
        for (int i = 0; i < count; i++) {
          skipPayload(buf, elementType, depth + 1);
        }
      }
    }
  }

  private static void skipCompound(ByteBuf buf, int depth) {
    checkDepth(depth);
    for (int type = readType(buf); type != TAG_END; type = readType(buf)) {
      skipString(buf);
      skipPayload(buf, type, depth + 1);
    }
  }

  private static int readType(ByteBuf buf) {
    if (!buf.isReadable()) {
      throw NOT_ENOUGH_DATA;
    }
    return buf.readUnsignedByte();
  }

  private static int readLength(ByteBuf buf) {
    if (!buf.isReadable(Integer.BYTES)) {
      throw NOT_ENOUGH_DATA;
    }
    int length = buf.readInt();
    if (length < 0) {
      throw NEGATIVE_LENGTH;
    }
    return length;
  }

  /** Skips a string in NBT form: an unsigned short byte length, then modified UTF-8. */
  private static void skipString(ByteBuf buf) {
    if (!buf.isReadable(Short.BYTES)) {
      throw NOT_ENOUGH_DATA;
    }
    skip(buf, buf.readUnsignedShort());
  }

  private static void skip(ByteBuf buf, long length) {
    if (length > buf.readableBytes()) {
      throw NOT_ENOUGH_DATA;
    }
    buf.skipBytes((int) length);
  }

  private static void checkDepth(int depth) {
    if (depth > MAX_DEPTH) {
      throw TOO_DEEP;
    }
  }

  private static DecoderException cachedDecoderException(String message) {
    return new DecoderException(message) {
      @Override
      public synchronized Throwable fillInStackTrace() {
        return this;
      }
    };
  }
}
