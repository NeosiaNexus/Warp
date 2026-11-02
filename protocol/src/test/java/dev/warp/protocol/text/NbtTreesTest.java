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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("NBT and JSON trees")
class NbtTreesTest {

  /** A compound holding a value of every NBT type. */
  static final CompoundBinaryTag EVERY_TYPE =
      CompoundBinaryTag.builder()
          .put("b", ByteBinaryTag.byteBinaryTag((byte) -1))
          .put("s", ShortBinaryTag.shortBinaryTag((short) 300))
          .put("i", IntBinaryTag.intBinaryTag(70_000))
          .put("l", LongBinaryTag.longBinaryTag(1L << 40))
          .put("f", FloatBinaryTag.floatBinaryTag(1.5f))
          .put("d", DoubleBinaryTag.doubleBinaryTag(-2.25))
          .put("ba", ByteArrayBinaryTag.byteArrayBinaryTag((byte) 1, (byte) 2))
          .put("str", StringBinaryTag.stringBinaryTag("x"))
          .put(
              "list",
              ListBinaryTag.listBinaryTag(
                  BinaryTagTypes.INT,
                  List.of(IntBinaryTag.intBinaryTag(1), IntBinaryTag.intBinaryTag(2))))
          .put("empty", ListBinaryTag.empty())
          .put("c", CompoundBinaryTag.builder().putString("k", "v").build())
          .put("ia", IntArrayBinaryTag.intArrayBinaryTag(1, 2, 3))
          .put("la", LongArrayBinaryTag.longArrayBinaryTag(4L, 5L))
          .build();

  private byte[] nbtField(BinaryTag tag) {
    ByteBuf buf = Unpooled.buffer();
    new NbtTree(buf).tag("t", tag);
    return ByteBufUtil.getBytes(buf);
  }

  private BinaryTag readBack(BinaryTag tag) {
    byte[] field = nbtField(tag);
    // Drop the field's name ("t", 2 + 1 bytes) to read it as a nameless root.
    ByteBuf root = Unpooled.buffer().writeByte(field[0]).writeBytes(field, 4, field.length - 4);
    BinaryTag read = NbtReader.readNameless(root);
    assertFalse(root.isReadable(), "unread bytes");
    return read;
  }

  @Nested
  @DisplayName("NBT")
  class Nbt {

    @Test
    @DisplayName("should write every tag type and read it back")
    void everyType() {
      assertEquals(EVERY_TYPE, readBack(EVERY_TYPE));
    }

    @Test
    @DisplayName("should box the elements of a mixed list in compounds with an empty name")
    void heterogeneousList() {
      ListBinaryTag mixed =
          ListBinaryTag.heterogeneousListBinaryTag()
              .add(StringBinaryTag.stringBinaryTag("a"))
              .add(IntBinaryTag.intBinaryTag(1))
              .build();

      assertEquals(
          ListBinaryTag.listBinaryTag(
              BinaryTagTypes.COMPOUND,
              List.of(
                  CompoundBinaryTag.builder().putString("", "a").build(),
                  CompoundBinaryTag.builder().putInt("", 1).build())),
          readBack(mixed));
    }

    @Test
    @DisplayName("should write exact bytes: type, name, payload; TAG_End closes a compound")
    void exactBytes() {
      assertArrayEquals(
          new byte[] {0x0A, 0x00, 0x01, 't', 0x01, 0x00, 0x01, 'x', 0x05, 0x00},
          nbtField(CompoundBinaryTag.builder().putByte("x", (byte) 5).build()));
      assertArrayEquals(
          new byte[] {0x09, 0x00, 0x01, 't', 0x00, 0x00, 0x00, 0x00, 0x00},
          nbtField(ListBinaryTag.empty()));
    }
  }

  @Nested
  @DisplayName("lenient reader")
  class Reader {

    private BinaryTag read(int... bytes) {
      byte[] raw = new byte[bytes.length];
      for (int i = 0; i < bytes.length; i++) {
        raw[i] = (byte) bytes[i];
      }
      return NbtReader.readNameless(Unpooled.wrappedBuffer(raw));
    }

    @Test
    @DisplayName("should read nothing and a lone TAG_End as no tag")
    void nothing() {
      assertEquals(EndBinaryTag.endBinaryTag(), read());
      assertEquals(EndBinaryTag.endBinaryTag(), read(0x00));
    }

    @Test
    @DisplayName("should keep the entries read before a truncation or an unknown type")
    void partial() {
      CompoundBinaryTag one = CompoundBinaryTag.builder().putByte("a", (byte) 1).build();

      assertEquals(one, read(0x0A, 0x01, 0x00, 0x01, 'a', 0x01, 0x03, 0x00, 0x01, 'b', 0x00));
      assertEquals(one, read(0x0A, 0x01, 0x00, 0x01, 'a', 0x01, 0x0D, 0x00, 0x01, 'b'));
      assertEquals(one, read(0x0A, 0x01, 0x00, 0x01, 'a', 0x01, 0x01, 0x00, 0x05, 'b'));
      assertEquals(one, read(0x0A, 0x01, 0x00, 0x01, 'a', 0x01, 0x01));
      assertEquals(one, read(0x0A, 0x01, 0x00, 0x01, 'a', 0x01, 0x01, 0x00));
      assertEquals(
          ListBinaryTag.listBinaryTag(BinaryTagTypes.INT, List.of(IntBinaryTag.intBinaryTag(7))),
          read(0x09, 0x03, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x07, 0x00));
      assertEquals(EndBinaryTag.endBinaryTag(), read(0x09, 0x03, 0x00));
      assertEquals(EndBinaryTag.endBinaryTag(), read(0x08, 0x00, 0x05, 'a'));
      assertEquals(EndBinaryTag.endBinaryTag(), read(0x63));
      assertEquals(ListBinaryTag.empty(), read(0x09, 0x00, 0x00, 0x00, 0x00, 0x03));
    }

    @Test
    @DisplayName("should refuse array lengths that are negative or longer than the buffer")
    void arrayLengths() {
      for (int type : new int[] {0x07, 0x0B, 0x0C}) {
        assertEquals(EndBinaryTag.endBinaryTag(), read(type, 0xFF, 0xFF, 0xFF, 0xFF));
        assertEquals(EndBinaryTag.endBinaryTag(), read(type, 0x00, 0x00, 0x00, 0x02, 0x00));
        assertEquals(EndBinaryTag.endBinaryTag(), read(type, 0x00, 0x00));
      }
      for (int type = 1; type <= 6; type++) {
        assertEquals(EndBinaryTag.endBinaryTag(), read(type));
      }
    }

    @Test
    @DisplayName("should stop at the depth a client stops at, lists and compounds alike")
    void depth() {
      // A list of lists of lists...: the reader stops at 512 levels rather than overflowing.
      ByteBuf lists = Unpooled.buffer().writeByte(0x09);
      for (int i = 0; i < 600; i++) {
        lists.writeByte(0x09).writeInt(1);
      }
      BinaryTag tag = NbtReader.readNameless(lists);
      int levels = 0;
      while (tag instanceof ListBinaryTag list && list.size() == 1) {
        tag = list.get(0);
        levels++;
      }
      assertEquals(NbtTree.MAX_DEPTH - 1, levels);

      ByteBuf compounds = Unpooled.buffer().writeByte(0x0A);
      for (int i = 0; i < 600; i++) {
        compounds.writeByte(0x0A).writeShort(1).writeByte('c');
      }
      tag = NbtReader.readNameless(compounds);
      levels = 0;
      while (tag instanceof CompoundBinaryTag compound && compound.get("c") != null) {
        tag = compound.get("c");
        levels++;
      }
      assertEquals(NbtTree.MAX_DEPTH - 1, levels);
    }

    @Test
    @DisplayName("should read empty arrays, and arrays that end the buffer")
    void arrays() {
      assertEquals(ByteArrayBinaryTag.byteArrayBinaryTag(), read(0x07, 0, 0, 0, 0));
      assertEquals(IntArrayBinaryTag.intArrayBinaryTag(), read(0x0B, 0, 0, 0, 0));
      assertEquals(LongArrayBinaryTag.longArrayBinaryTag(), read(0x0C, 0, 0, 0, 0));
      assertEquals(ByteArrayBinaryTag.byteArrayBinaryTag((byte) 9), read(0x07, 0, 0, 0, 1, 9));
      assertEquals(IntArrayBinaryTag.intArrayBinaryTag(9), read(0x0B, 0, 0, 0, 1, 0, 0, 0, 9));
      assertEquals(
          LongArrayBinaryTag.longArrayBinaryTag(9L),
          read(0x0C, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 9));
    }

    @Test
    @DisplayName("should stop a compound after a list of TAG_End claiming elements")
    void endList() {
      assertEquals(
          CompoundBinaryTag.builder()
              .put("l", ListBinaryTag.empty())
              .putByte("x", (byte) 1)
              .build(),
          read(0x0A, 0x09, 0, 1, 'l', 0x00, 0, 0, 0, 2, 0x01, 0, 1, 'x', 0x01, 0x00));
    }

    @Test
    @DisplayName("should stop a list after an element cut short")
    void truncatedElement() {
      // Two compounds: the first holds an unknown type, the reader keeps what it read of it.
      ListBinaryTag list =
          (ListBinaryTag)
              read(
                  0x09, 0x0A, 0, 0, 0, 2, 0x01, 0, 1, 'a', 0x05, 0x63, 0x01, 0, 1, 'b', 0x06, 0x00);

      assertEquals(1, list.size());
      assertEquals(CompoundBinaryTag.builder().putByte("a", (byte) 5).build(), list.get(0));
    }

    @Test
    @DisplayName("should decode modified UTF-8, and anything else as standard UTF-8")
    void modifiedUtf8() {
      // NUL on two bytes, then an emoji as a surrogate pair of three bytes each.
      assertEquals(
          "a" + "\u0000" + "\uD83D\uDE00",
          ((StringBinaryTag) read(0x08, 0, 9, 'a', 0xC0, 0x80, 0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80))
              .value());
      // Standard UTF-8 of the same emoji: not modified UTF-8, read as standard UTF-8.
      assertEquals(
          "\uD83D\uDE00", ((StringBinaryTag) read(0x08, 0, 4, 0xF0, 0x9F, 0x98, 0x80)).value());
      assertEquals("a" + "\uFFFD", ((StringBinaryTag) read(0x08, 0, 2, 'a', 0xFF)).value());
    }
  }

  @Nested
  @DisplayName("JSON")
  class Json {

    @Test
    @DisplayName("should write NBT values as vanilla converts them to JSON")
    void everyType() {
      JsonTree tree = new JsonTree(false);
      tree.beginObjectValue();
      tree.tag("t", EVERY_TYPE);
      tree.endObject();

      String json = tree.text();
      for (String part :
          List.of(
              "\"b\":-1",
              "\"s\":300",
              "\"i\":70000",
              "\"l\":1099511627776",
              "\"f\":1.5",
              "\"d\":-2.25",
              "\"ba\":[1,2]",
              "\"str\":\"x\"",
              "\"list\":[1,2]",
              "\"empty\":[]",
              "\"c\":{\"k\":\"v\"}",
              "\"ia\":[1,2,3]",
              "\"la\":[4,5]")) {
        assertEquals(true, json.contains(part), part + " in " + json);
      }
    }

    @Test
    @DisplayName("should separate fields and list elements with commas")
    void commas() {
      JsonTree tree = new JsonTree(false);
      tree.beginObjectValue();
      tree.intArray("a", new int[] {1, 2});
      tree.beginList("l", TreeSink.Kind.STRING, 2);
      tree.stringValue("x");
      tree.beginObjectValue();
      tree.bool("b", true);
      tree.integer("n", -3);
      tree.endObject();
      tree.endList();
      tree.endObject();

      assertEquals("{\"a\":[1,2],\"l\":[\"x\",{\"b\":true,\"n\":-3}]}", tree.text());
    }

    @Test
    @DisplayName("should refuse to write an end tag")
    void endTag() {
      JsonTree tree = new JsonTree(false);
      tree.beginObjectValue();
      assertThrows(
          IllegalArgumentException.class, () -> tree.tag("t", EndBinaryTag.endBinaryTag()));
    }

    @Test
    @DisplayName("should keep characters outside the escaped ones as they are")
    void unicode() {
      JsonTree tree = new JsonTree(true);
      tree.stringValue("\u00E9\uD83D\uDE00 \u007f");

      assertEquals("\"\u00E9\uD83D\uDE00 \u007f\"", tree.text());
      assertArrayEquals(
          "\"\u00E9\uD83D\uDE00 \u007f\"".getBytes(StandardCharsets.UTF_8),
          tree.text().getBytes(StandardCharsets.UTF_8));
    }
  }
}
