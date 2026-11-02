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

import io.netty.buffer.ByteBuf;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.FloatBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.LongBinaryTag;
import net.kyori.adventure.nbt.ShortBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

/**
 * Writes a component tree as network NBT straight into a buffer: the form of components in the
 * configuration and play states from 1.20.3.
 *
 * <p>The root tag has a type byte and no name (network NBT, since 1.20.2), a field is a type byte,
 * a name and a payload, a list announces its element type and size, and a compound ends with {@code
 * TAG_End}. Strings are modified UTF-8 with an unsigned-short length, which the client reads with
 * {@link java.io.DataInput#readUTF}: U+0000 takes two bytes and a supplementary character is a
 * surrogate pair of three bytes each, where standard UTF-8 would make the client fail the packet.
 *
 * <p>The client refuses a string longer than 65 535 bytes and nesting deeper than 512 lists and
 * compounds ({@code NbtAccounter}); both are refused here, before anything reaches it.
 */
final class NbtTree implements TreeSink {

  /** Deepest nesting of lists and compounds a client reads. */
  static final int MAX_DEPTH = 512;

  /** Longest string payload in bytes: its length prefix is an unsigned short. */
  static final int MAX_STRING_BYTES = 0xFFFF;

  private static final int TAG_END = 0;
  private static final int TAG_BYTE = 1;
  private static final int TAG_INT = 3;
  private static final int TAG_STRING = 8;
  private static final int TAG_LIST = 9;
  private static final int TAG_COMPOUND = 10;
  private static final int TAG_INT_ARRAY = 11;

  private final ByteBuf out;

  /** Lists and compounds open around the next value; 0 at the root. */
  private int depth;

  NbtTree(ByteBuf out) {
    this.out = out;
  }

  @Override
  public boolean homogeneousLists() {
    return true;
  }

  // ---------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------

  @Override
  public void string(String key, String value) {
    field(TAG_STRING, key);
    utf(value);
  }

  @Override
  public void bool(String key, boolean value) {
    field(TAG_BYTE, key);
    out.writeByte(value ? 1 : 0);
  }

  @Override
  public void integer(String key, int value) {
    field(TAG_INT, key);
    out.writeInt(value);
  }

  @Override
  public void intArray(String key, int[] values) {
    field(TAG_INT_ARRAY, key);
    out.writeInt(values.length);
    for (int value : values) {
      out.writeInt(value);
    }
  }

  @Override
  public void tag(String key, BinaryTag tag) {
    field(tag.type().id(), key);
    payload(tag);
  }

  @Override
  public void beginObject(String key) {
    field(TAG_COMPOUND, key);
    enter();
  }

  @Override
  public void beginList(String key, Kind kind, int size) {
    field(TAG_LIST, key);
    enter();
    out.writeByte(kind == Kind.STRING ? TAG_STRING : TAG_COMPOUND);
    out.writeInt(size);
  }

  // ---------------------------------------------------------------------------
  // Values
  // ---------------------------------------------------------------------------

  @Override
  public void stringValue(String value) {
    if (depth == 0) {
      out.writeByte(TAG_STRING);
    }
    utf(value);
  }

  @Override
  public void beginObjectValue() {
    if (depth == 0) {
      out.writeByte(TAG_COMPOUND);
    }
    enter();
  }

  @Override
  public void endObject() {
    out.writeByte(TAG_END);
    depth--;
  }

  @Override
  public void endList() {
    depth--;
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private void field(int type, String key) {
    out.writeByte(type);
    utf(key);
  }

  private void enter() {
    if (depth >= MAX_DEPTH) {
      throw new IllegalArgumentException(
          "Component nested deeper than " + MAX_DEPTH + " NBT lists and compounds");
    }
    depth++;
  }

  /** Writes the payload of an NBT value, as its field or list element. */
  private void payload(BinaryTag tag) {
    switch (tag) {
      case ByteBinaryTag b -> out.writeByte(b.value());
      case ShortBinaryTag s -> out.writeShort(s.value());
      case IntBinaryTag i -> out.writeInt(i.value());
      case LongBinaryTag l -> out.writeLong(l.value());
      case FloatBinaryTag f -> out.writeFloat(f.value());
      case DoubleBinaryTag d -> out.writeDouble(d.value());
      case StringBinaryTag s -> utf(s.value());
      case ByteArrayBinaryTag bytes -> {
        out.writeInt(bytes.size());
        out.writeBytes(bytes.value());
      }
      case IntArrayBinaryTag ints -> {
        out.writeInt(ints.size());
        for (int i = 0; i < ints.size(); i++) {
          out.writeInt(ints.get(i));
        }
      }
      case LongArrayBinaryTag longs -> {
        out.writeInt(longs.size());
        for (int i = 0; i < longs.size(); i++) {
          out.writeLong(longs.get(i));
        }
      }
      case ListBinaryTag list -> {
        enter();
        ListBinaryTag homogeneous = list.wrapHeterogeneity();
        out.writeByte(homogeneous.elementType().id());
        out.writeInt(homogeneous.size());
        for (BinaryTag element : homogeneous) {
          payload(element);
        }
        depth--;
      }
      case CompoundBinaryTag compound -> {
        enter();
        for (var entry : compound) {
          tag(entry.getKey(), entry.getValue());
        }
        out.writeByte(TAG_END);
        depth--;
      }
      default -> throw new IllegalArgumentException("Unknown NBT tag type " + tag.type());
    }
  }

  /** Writes a string as modified UTF-8 after its unsigned-short byte length. */
  private void utf(String text) {
    int length = modifiedUtf8Length(text);
    if (length > MAX_STRING_BYTES) {
      throw new IllegalArgumentException(
          "Text too long for an NBT string: "
              + length
              + " bytes of modified UTF-8 (max "
              + MAX_STRING_BYTES
              + ")");
    }
    out.writeShort(length);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c != 0 && c < 0x80) {
        out.writeByte(c);
      } else if (c < 0x800) {
        out.writeByte(0xC0 | (c >> 6));
        out.writeByte(0x80 | (c & 0x3F));
      } else {
        out.writeByte(0xE0 | (c >> 12));
        out.writeByte(0x80 | ((c >> 6) & 0x3F));
        out.writeByte(0x80 | (c & 0x3F));
      }
    }
  }

  /** Returns the modified UTF-8 byte length of {@code text}. */
  static int modifiedUtf8Length(String text) {
    int length = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c != 0 && c < 0x80) {
        length += 1;
      } else if (c < 0x800) {
        length += 2;
      } else {
        length += 3;
      }
    }
    return length;
  }
}
