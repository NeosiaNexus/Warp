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

import static dev.warp.protocol.text.ComponentCodecTest.j;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;

import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.EndBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What the decoder makes of events and values that only some versions, or no version, send. */
@DisplayName("ComponentCodec decoding, normalization")
class ComponentNormalizationTest {

  private static final UUID ID = UUID.fromString("6a085b2c-19fb-4986-b453-231aa942bbec");

  private Component json(String json) {
    return ComponentCodec.decodeJson(j(json));
  }

  private Component hovered(String hover) {
    return json("{'text':'h','hoverEvent':" + hover + "}");
  }

  @Nested
  @DisplayName("items")
  class Items {

    @Test
    @DisplayName("should read an item from its id alone, a 1.16 shorthand")
    void idOnly() {
      assertEquals(
          Component.text("h").hoverEvent(HoverEvent.showItem(Key.key("stone"), 1)),
          hovered("{'action':'show_item','contents':'minecraft:stone'}"));
      assertEquals(Component.text("h"), hovered("{'action':'show_item','contents':'Not A Key'}"));
      assertEquals(Component.text("h"), hovered("{'action':'show_item','contents':['x']}"));
      assertEquals(Component.text("h"), hovered("{'action':'show_item','contents':{'count':2}}"));
    }

    @Test
    @DisplayName("should read the count, the NBT tag and the data components an item carries")
    @SuppressWarnings("deprecation") // the pre-1.20.5 item NBT
    void fields() {
      assertEquals(
          Component.text("h").hoverEvent(HoverEvent.showItem(Key.key("stone"), 1)),
          hovered("{'action':'show_item','contents':{'id':'stone','count':'many'}}"));
      assertEquals(
          Component.text("h")
              .hoverEvent(
                  HoverEvent.showItem(
                      Key.key("stone"), 2, BinaryTagHolder.binaryTagHolder("{\"a\":1}"))),
          hovered("{'action':'show_item','contents':{'id':'stone','count':2,'tag':{'a':1}}}"));
      assertEquals(
          Component.text("h").hoverEvent(HoverEvent.showItem(Key.key("stone"), 1)),
          hovered("{'action':'show_item','contents':{'id':'stone','components':'none'}}"));
      Component withComponents =
          hovered("{'action':'show_item','id':'stone','count':1,'components':{'minecraft:a':1}}");
      HoverEvent.ShowItem item = (HoverEvent.ShowItem) withComponents.hoverEvent().value();
      assertEquals(1, item.dataComponents().size());
    }
  }

  @Nested
  @DisplayName("entities")
  class Entities {

    @Test
    @DisplayName("should read a UUID as a string or four ints, and refuse anything else")
    void uuids() {
      Component pig = Component.text("h").hoverEvent(HoverEvent.showEntity(Key.key("pig"), ID));

      assertEquals(
          pig,
          hovered(
              "{'action':'show_entity','contents':{'type':'pig',"
                  + "'id':'6a085b2c-19fb-4986-b453-231aa942bbec'}}"));
      assertEquals(
          Component.text("h"),
          hovered("{'action':'show_entity','contents':{'type':'pig','id':[1,2,3]}}"));
      assertEquals(
          Component.text("h"),
          hovered("{'action':'show_entity','contents':{'type':'pig','id':['a','b','c','d']}}"));
      assertEquals(
          Component.text("h"),
          hovered("{'action':'show_entity','contents':{'type':'pig','id':{}}}"));
      assertEquals(Component.text("h"), hovered("{'action':'show_entity','contents':'pig'}"));
      assertEquals(
          Component.text("h"),
          hovered("{'action':'show_entity','id':'minecraft:pig','uuid':'not a uuid'}"));
    }

    @Test
    @DisplayName("should read a legacy name as JSON when it is, as text otherwise")
    void names() {
      assertEquals(
          Component.text("h")
              .hoverEvent(HoverEvent.showEntity(Key.key("pig"), ID, Component.text("{Bob"))),
          hovered(
              "{'action':'show_entity','value':'{type:\\'pig\\',"
                  + "id:\\'6a085b2c-19fb-4986-b453-231aa942bbec\\',name:\\'{Bob\\'}'}"));
      assertEquals(
          Component.text("h")
              .hoverEvent(HoverEvent.showEntity(Key.key("pig"), ID, Component.text("Bob"))),
          hovered(
              "{'action':'show_entity','value':'{type:\\'pig\\',"
                  + "id:\\'6a085b2c-19fb-4986-b453-231aa942bbec\\',name:\\'[\\\\\\'Bob\\\\\\']\\'}'}"));
      assertEquals(
          Component.text("h")
              .hoverEvent(HoverEvent.showEntity(Key.key("pig"), ID, Component.text("Bob"))),
          hovered(
              "{'action':'show_entity','value':'{type:\\'pig\\',"
                  + "id:\\'6a085b2c-19fb-4986-b453-231aa942bbec\\',name:\\'\\\\\\'Bob\\\\\\'\\'}'}"));
      assertEquals(
          Component.text("h")
              .hoverEvent(HoverEvent.showEntity(Key.key("ns:pig"), ID, Component.text("Bob"))),
          hovered(
              "{'action':'show_entity','contents':{'type':'ns:pig',"
                  + "'id':'6a085b2c-19fb-4986-b453-231aa942bbec','name':'Bob'}}"));
    }
  }

  @Nested
  @DisplayName("hover text and achievements")
  class Texts {

    @Test
    @DisplayName("should prefer contents to a value, and drop a tooltip with neither")
    @SuppressWarnings("deprecation") // achievements
    void forms() {
      assertEquals(
          Component.text("h").hoverEvent(HoverEvent.showText(Component.text("new"))),
          hovered("{'action':'show_text','contents':'new','value':'old'}"));
      assertEquals(Component.text("h"), hovered("{'action':'show_text'}"));
      assertEquals(Component.text("h"), hovered("{'action':{'nope':1},'value':'x'}"));
      assertEquals(
          Component.text("h").hoverEvent(HoverEvent.showAchievement("achievement.x")),
          hovered("{'action':'show_achievement','value':{'text':'achievement.x'}}"));
      assertEquals(Component.text("h"), hovered("{'action':'show_achievement'}"));
    }
  }

  @Nested
  @DisplayName("clicks")
  class Clicks {

    @Test
    @DisplayName("should read typed and string pages, and other fields as their text")
    void fields() {
      assertEquals(
          Component.text("c").clickEvent(ClickEvent.changePage(4)),
          json("{'text':'c','clickEvent':{'action':'change_page','page':4,'value':'9'}}"));
      assertEquals(Component.text("c"), json("{'text':'c','clickEvent':{'action':'change_page'}}"));
      assertEquals(
          Component.text("c").clickEvent(ClickEvent.runCommand("true")),
          json("{'text':'c','clickEvent':{'action':'run_command','value':true}}"));
      assertEquals(Component.text("c"), json("{'text':'c','clickEvent':{'action':['x']}}"));
    }
  }

  @Nested
  @DisplayName("NBT values")
  class NbtValues {

    @Test
    @DisplayName("should keep bytes as numbers outside boolean fields, arrays as numbers")
    void numbers() {
      CompoundBinaryTag tag =
          CompoundBinaryTag.builder()
              .putString("text", "")
              .put("bold", ByteBinaryTag.byteBinaryTag((byte) 0))
              .put("count", ByteBinaryTag.byteBinaryTag((byte) 3))
              .put("bytes", ByteArrayBinaryTag.byteArrayBinaryTag((byte) 1, (byte) 2))
              .put("longs", LongArrayBinaryTag.longArrayBinaryTag(3L, 4L))
              .build();

      assertEquals(
          true,
          ComponentDecoder.toJson(tag, null)
              .getAsJsonObject()
              .get("count")
              .getAsJsonPrimitive()
              .isNumber());
      assertEquals(
          false, ComponentDecoder.toJson(tag, null).getAsJsonObject().get("bold").getAsBoolean());
      assertEquals(
          "[1,2]", ComponentDecoder.toJson(tag, null).getAsJsonObject().get("bytes").toString());
      assertEquals(
          "[3,4]", ComponentDecoder.toJson(tag, null).getAsJsonObject().get("longs").toString());
    }

    @Test
    @DisplayName("should unbox only a compound of one empty-named entry")
    void unboxing() {
      ListBinaryTag list =
          ListBinaryTag.listBinaryTag(
              BinaryTagTypes.COMPOUND,
              List.of(
                  CompoundBinaryTag.builder().putString("", "a").build(),
                  CompoundBinaryTag.builder().putString("", "b").putString("c", "d").build(),
                  CompoundBinaryTag.builder().putString("e", "f").build()));

      assertEquals("\"a\"", ComponentDecoder.toJson(list, null).getAsJsonArray().get(0).toString());
      assertEquals(
          true, ComponentDecoder.toJson(list, null).getAsJsonArray().get(1).isJsonObject());
    }

    @Test
    @DisplayName("should read a component it cannot make out as empty when it shows no text")
    void unreadableNbt() {
      ByteBuf buf = Unpooled.buffer();
      buf.writeByte(0x0A).writeByte(0x03).writeShort(1).writeByte('x').writeInt(1).writeByte(0);

      assertSame(
          Component.empty(),
          ComponentCodec.decode(buf, ProtocolVersion.MINECRAFT_1_21_5, ProtocolState.PLAY));
      assertSame(Component.empty(), ComponentDecoder.nbt(EndBinaryTag.endBinaryTag()));
      assertEquals(Component.text("t"), ComponentDecoder.nbt(StringBinaryTag.stringBinaryTag("t")));
    }
  }

  @Nested
  @DisplayName("wire")
  class Wire {

    @Test
    @DisplayName("should read an empty string as empty, and leave what follows it")
    void emptyString() {
      ByteBuf buf = Unpooled.wrappedBuffer(new byte[] {0x00, '"', 'x', '"'});

      assertSame(
          Component.empty(),
          ComponentCodec.decode(buf, ProtocolVersion.MINECRAFT_1_20_2, ProtocolState.PLAY));
      assertEquals(3, buf.readableBytes());
    }
  }
}
