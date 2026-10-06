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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("McNbt codec")
class McNbtTest {

  private static final int END = 0;
  private static final int BYTE = 1;
  private static final int SHORT = 2;
  private static final int INT = 3;
  private static final int LONG = 4;
  private static final int FLOAT = 5;
  private static final int DOUBLE = 6;
  private static final int BYTE_ARRAY = 7;
  private static final int STRING = 8;
  private static final int LIST = 9;
  private static final int COMPOUND = 10;
  private static final int INT_ARRAY = 11;
  private static final int LONG_ARRAY = 12;

  /** Writes a named root tag: type, name, then the payload {@code payload} writes. */
  private static byte[] root(int type, Consumer<ByteBuf> payload) {
    ByteBuf buf = Unpooled.buffer();
    try {
      buf.writeByte(type);
      name(buf, "root");
      payload.accept(buf);
      byte[] bytes = new byte[buf.readableBytes()];
      buf.readBytes(bytes);
      return bytes;
    } finally {
      buf.release();
    }
  }

  private static void name(ByteBuf buf, String name) {
    byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
    buf.writeShort(utf8.length);
    buf.writeBytes(utf8);
  }

  /** Reads {@code tag} followed by a marker byte: the marker must be next. */
  private static byte[] readWithTrailer(byte[] tag) {
    ByteBuf buf = Unpooled.buffer();
    try {
      buf.writeBytes(tag);
      buf.writeByte(0x7F);
      byte[] read = McNbt.readNamed(buf);
      assertEquals(0x7F, buf.readByte(), "the reader stops right after the tag");
      return read;
    } finally {
      buf.release();
    }
  }

  /** Reads {@code tag} from a buffer holding nothing else. */
  private static byte[] readExactly(byte[] tag) {
    ByteBuf buf = Unpooled.wrappedBuffer(tag);
    try {
      byte[] read = McNbt.readNamed(buf);
      assertFalse(buf.isReadable());
      return read;
    } finally {
      buf.release();
    }
  }

  /**
   * Writes the payload of a list holding a list, and so on: {@code depth} lists below this one, the
   * deepest an empty list of TAG_End.
   */
  private static void nestedLists(ByteBuf buf, int depth) {
    for (int i = 0; i < depth; i++) {
      buf.writeByte(LIST);
      buf.writeInt(1);
    }
    buf.writeByte(END);
    buf.writeInt(0);
  }

  @Nested
  @DisplayName("readNamed")
  class ReadNamed {

    @Test
    @DisplayName("should read a compound with every tag type, nested, and stop after it")
    void everyTagType() {
      byte[] tag =
          root(
              COMPOUND,
              buf -> {
                buf.writeByte(BYTE);
                name(buf, "b");
                buf.writeByte(1);
                buf.writeByte(SHORT);
                name(buf, "s");
                buf.writeShort(2);
                buf.writeByte(INT);
                name(buf, "i");
                buf.writeInt(3);
                buf.writeByte(LONG);
                name(buf, "l");
                buf.writeLong(4);
                buf.writeByte(FLOAT);
                name(buf, "f");
                buf.writeFloat(5);
                buf.writeByte(DOUBLE);
                name(buf, "d");
                buf.writeDouble(6);
                buf.writeByte(BYTE_ARRAY);
                name(buf, "ba");
                buf.writeInt(2);
                buf.writeBytes(new byte[] {7, 8});
                buf.writeByte(STRING);
                name(buf, "str");
                name(buf, "text");
                buf.writeByte(INT_ARRAY);
                name(buf, "ia");
                buf.writeInt(1);
                buf.writeInt(9);
                buf.writeByte(LONG_ARRAY);
                name(buf, "la");
                buf.writeInt(1);
                buf.writeLong(10);
                // A list of compounds, each with a list of strings.
                buf.writeByte(LIST);
                name(buf, "entries");
                buf.writeByte(COMPOUND);
                buf.writeInt(2);
                for (int i = 0; i < 2; i++) {
                  buf.writeByte(LIST);
                  name(buf, "names");
                  buf.writeByte(STRING);
                  buf.writeInt(1);
                  name(buf, "n" + i);
                  buf.writeByte(END);
                }
                buf.writeByte(END);
              });

      assertArrayEquals(tag, readWithTrailer(tag));
    }

    @Test
    @DisplayName("should read a lone TAG_End as an absent tag")
    void absentTag() {
      assertArrayEquals(new byte[] {END}, readWithTrailer(new byte[] {END}));
    }

    @Test
    @DisplayName("should read an empty list of TAG_End")
    void emptyEndList() {
      byte[] tag =
          root(
              LIST,
              buf -> {
                buf.writeByte(END);
                buf.writeInt(0);
              });

      assertArrayEquals(tag, readWithTrailer(tag));
    }

    @Test
    @DisplayName("should reject a non-empty list of TAG_End")
    void nonEmptyEndList() {
      byte[] tag =
          root(
              LIST,
              buf -> {
                buf.writeByte(END);
                buf.writeInt(1);
              });

      assertThrows(DecoderException.class, () -> readWithTrailer(tag));
    }

    @Test
    @DisplayName("should reject a negative array length")
    void negativeLength() {
      byte[] tag = root(INT_ARRAY, buf -> buf.writeInt(-1));

      assertThrows(DecoderException.class, () -> readWithTrailer(tag));
    }

    @Test
    @DisplayName("should reject an array longer than the buffer without allocating it")
    void truncatedArray() {
      byte[] tag = root(LONG_ARRAY, buf -> buf.writeInt(Integer.MAX_VALUE));

      assertThrows(DecoderException.class, () -> readWithTrailer(tag));
    }

    @Test
    @DisplayName("should reject a compound that never ends")
    void unterminatedCompound() {
      byte[] tag =
          root(
              COMPOUND,
              buf -> {
                buf.writeByte(INT);
                name(buf, "i");
                buf.writeInt(1);
              });
      ByteBuf buf = Unpooled.wrappedBuffer(tag);
      try {
        assertThrows(DecoderException.class, () -> McNbt.readNamed(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject an unknown tag type")
    void unknownType() {
      byte[] tag = root(13, buf -> {});

      assertThrows(DecoderException.class, () -> readWithTrailer(tag));
    }

    @Test
    @DisplayName("should reject nesting deeper than vanilla allows")
    void tooDeep() {
      byte[] tag =
          root(
              COMPOUND,
              buf -> {
                // The root, then compounds down to depth MAX_DEPTH + 1.
                for (int i = 0; i <= McNbt.MAX_DEPTH; i++) {
                  buf.writeByte(COMPOUND);
                  name(buf, "");
                }
                for (int i = 0; i <= McNbt.MAX_DEPTH + 1; i++) {
                  buf.writeByte(END);
                }
              });

      assertThrows(DecoderException.class, () -> readWithTrailer(tag));
    }

    @ParameterizedTest(name = "type {0}")
    @CsvSource({"1, 1", "2, 2", "3, 4", "4, 8", "5, 4", "6, 8"})
    @DisplayName("should skip a list of fixed-size elements to its last byte")
    void fixedSizeList(int type, int size) {
      byte[] tag =
          root(
              LIST,
              buf -> {
                buf.writeByte(type);
                buf.writeInt(3);
                buf.writeZero(3 * size);
              });

      assertArrayEquals(tag, readWithTrailer(tag));
    }

    @Test
    @DisplayName("should reject lists nested deeper than vanilla allows")
    void listsTooDeep() {
      byte[] tag = root(LIST, buf -> nestedLists(buf, McNbt.MAX_DEPTH + 1));

      assertThrows(DecoderException.class, () -> readWithTrailer(tag));
    }

    @Test
    @DisplayName("should accept lists nested up to the limit")
    void deepestListsAllowed() {
      byte[] tag = root(LIST, buf -> nestedLists(buf, McNbt.MAX_DEPTH));

      assertArrayEquals(tag, readWithTrailer(tag));
    }

    @Test
    @DisplayName("should reject an array length cut short")
    void truncatedArrayLength() {
      byte[] tag = root(INT_ARRAY, buf -> buf.writeShort(0));

      assertThrows(DecoderException.class, () -> readExactly(tag));
    }

    @Test
    @DisplayName("should reject a string length cut short")
    void truncatedStringLength() {
      byte[] tag = root(STRING, buf -> buf.writeByte(0));

      assertThrows(DecoderException.class, () -> readExactly(tag));
    }

    @Test
    @DisplayName("should read a tag that ends exactly at the end of the buffer")
    void endsTheBuffer() {
      byte[] tag = root(STRING, buf -> name(buf, "text"));

      assertArrayEquals(tag, readExactly(tag));
    }

    @Test
    @DisplayName("should accept nesting up to the limit")
    void deepestAllowed() {
      byte[] tag =
          root(
              COMPOUND,
              buf -> {
                // The root, then compounds down to depth MAX_DEPTH.
                for (int i = 0; i < McNbt.MAX_DEPTH; i++) {
                  buf.writeByte(COMPOUND);
                  name(buf, "");
                }
                for (int i = 0; i <= McNbt.MAX_DEPTH; i++) {
                  buf.writeByte(END);
                }
              });

      assertArrayEquals(tag, readWithTrailer(tag));
    }
  }
}
