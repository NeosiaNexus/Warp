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
package dev.warp.protocol.text;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.EndBinaryTag;
import net.kyori.adventure.nbt.FloatBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.LongBinaryTag;
import net.kyori.adventure.nbt.ShortBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.jspecify.annotations.Nullable;

/**
 * Reads a nameless network NBT tag sent by a backend, keeping what it can of a malformed one.
 *
 * <p>A compound or list cut short, by a truncated buffer or an unknown tag type, keeps the entries
 * read before the fault, and reading stops there: the caller gets what could be read rather than an
 * error. Nesting is limited to {@value NbtTree#MAX_DEPTH} levels, as the client limits it, and
 * every length is checked against the readable bytes before anything is allocated.
 */
final class NbtReader {

  private static final int TAG_END = 0;

  private final ByteBuf in;

  /** Whether a fault cut the tag short. */
  private boolean truncated;

  private NbtReader(ByteBuf in) {
    this.in = in;
  }

  /**
   * Reads one nameless root tag.
   *
   * @param in the buffer to read from
   * @return the tag, {@link EndBinaryTag} when the buffer holds none or a lone {@code TAG_End},
   *     with what could be read when it is malformed
   */
  static BinaryTag readNameless(ByteBuf in) {
    if (!in.isReadable()) {
      return EndBinaryTag.endBinaryTag();
    }
    NbtReader reader = new NbtReader(in);
    BinaryTag tag = reader.payload(in.readUnsignedByte(), 0);
    return tag == null ? EndBinaryTag.endBinaryTag() : tag;
  }

  /** Reads a payload, or returns {@code null} when nothing of it could be read. */
  private @Nullable BinaryTag payload(int type, int depth) {
    return switch (type) {
      case 1 -> need(Byte.BYTES) ? ByteBinaryTag.byteBinaryTag(in.readByte()) : null;
      case 2 -> need(Short.BYTES) ? ShortBinaryTag.shortBinaryTag(in.readShort()) : null;
      case 3 -> need(Integer.BYTES) ? IntBinaryTag.intBinaryTag(in.readInt()) : null;
      case 4 -> need(Long.BYTES) ? LongBinaryTag.longBinaryTag(in.readLong()) : null;
      case 5 -> need(Float.BYTES) ? FloatBinaryTag.floatBinaryTag(in.readFloat()) : null;
      case 6 -> need(Double.BYTES) ? DoubleBinaryTag.doubleBinaryTag(in.readDouble()) : null;
      case 7 -> byteArray();
      case 8 -> stringTag();
      case 9 -> list(depth);
      case 10 -> compound(depth);
      case 11 -> intArray();
      case 12 -> longArray();
      default -> {
        truncated = true; // TAG_End where a value belongs, or an unknown type
        yield null;
      }
    };
  }

  private @Nullable BinaryTag stringTag() {
    String string = string();
    if (string == null) {
      return null;
    }
    return StringBinaryTag.stringBinaryTag(string);
  }

  private @Nullable BinaryTag byteArray() {
    int length = length(Byte.BYTES);
    if (length < 0) {
      return null;
    }
    byte[] bytes = new byte[length];
    in.readBytes(bytes);
    return ByteArrayBinaryTag.byteArrayBinaryTag(bytes);
  }

  private @Nullable BinaryTag intArray() {
    int length = length(Integer.BYTES);
    if (length < 0) {
      return null;
    }
    int[] ints = new int[length];
    for (int i = 0; i < length; i++) {
      ints[i] = in.readInt();
    }
    return IntArrayBinaryTag.intArrayBinaryTag(ints);
  }

  private @Nullable BinaryTag longArray() {
    int length = length(Long.BYTES);
    if (length < 0) {
      return null;
    }
    long[] longs = new long[length];
    for (int i = 0; i < length; i++) {
      longs[i] = in.readLong();
    }
    return LongArrayBinaryTag.longArrayBinaryTag(longs);
  }

  private @Nullable BinaryTag list(int depth) {
    if (depth >= NbtTree.MAX_DEPTH || !need(Byte.BYTES + Integer.BYTES)) {
      truncated = true;
      return null;
    }
    int elementType = in.readUnsignedByte();
    int size = in.readInt();
    ListBinaryTag.Builder<BinaryTag> list = ListBinaryTag.heterogeneousListBinaryTag();
    for (int i = 0; i < size && elementType != TAG_END && !truncated; i++) {
      BinaryTag element = payload(elementType, depth + 1);
      if (element == null) {
        break;
      }
      list.add(element);
    }
    return list.build();
  }

  private @Nullable BinaryTag compound(int depth) {
    if (depth >= NbtTree.MAX_DEPTH) {
      truncated = true;
      return null;
    }
    CompoundBinaryTag.Builder compound = CompoundBinaryTag.builder();
    while (!truncated && need(Byte.BYTES)) {
      int type = in.readUnsignedByte();
      if (type == TAG_END) {
        return compound.build();
      }
      String name = string();
      if (name == null) {
        break;
      }
      BinaryTag value = payload(type, depth + 1);
      if (value == null) {
        break;
      }
      compound.put(name, value);
    }
    return compound.build();
  }

  /** Reads a modified UTF-8 string, or returns {@code null} when it is cut short. */
  private @Nullable String string() {
    if (!need(Short.BYTES)) {
      return null;
    }
    int length = in.getUnsignedShort(in.readerIndex());
    if (!need(Short.BYTES + length)) {
      return null;
    }
    ByteBuf utf = in.readSlice(Short.BYTES + length);
    try {
      return DataInputStream.readUTF(new ByteBufInputStream(utf.duplicate()));
    } catch (IOException notModifiedUtf8) {
      // Not modified UTF-8: standard UTF-8 decoding replaces what it cannot read.
      return utf.toString(Short.BYTES, length, StandardCharsets.UTF_8);
    }
  }

  /** Reads an array length, or returns -1 when it is negative or longer than the buffer. */
  private int length(int elementBytes) {
    if (!need(Integer.BYTES)) {
      return -1;
    }
    int length = in.readInt();
    if (length < 0 || (long) length * elementBytes > in.readableBytes()) {
      truncated = true;
      return -1;
    }
    return length;
  }

  private boolean need(int bytes) {
    if (in.isReadable(bytes)) {
      return true;
    }
    truncated = true;
    return false;
  }
}
