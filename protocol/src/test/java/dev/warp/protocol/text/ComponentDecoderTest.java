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
import dev.warp.protocol.codec.VarInt;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("ComponentCodec decoding")
class ComponentDecoderTest {

  private static final UUID ENTITY = UUID.fromString("6a085b2c-19fb-4986-b453-231aa942bbec");
  private static final int[] ENTITY_INTS = {1778932524, 435898758, -1269619942, -1455244308};

  private static Component json(String json) {
    return ComponentCodec.decodeJson(json);
  }

  /** Decodes network NBT as a 1.21.5 client in the play state would receive it. */
  private static Component nbt(BinaryTag tag) {
    ByteBuf buf = Unpooled.buffer();
    new NbtTree(buf).tag("", tag); // a named field: type, empty name, payload
    buf.skipBytes(3); // the nameless root keeps the type: put it back below
    ByteBuf root = Unpooled.buffer().writeByte(tag.type().id()).writeBytes(buf);
    return ComponentCodec.decode(root, ProtocolVersion.MINECRAFT_1_21_5, ProtocolState.PLAY);
  }

  // ---------------------------------------------------------------------------
  // Empty reasons
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("empty reasons")
  class Empty {

    @Test
    @DisplayName("should read nothing, an empty string and empty text as the empty component")
    void empty() {
      for (Component empty :
          List.of(
              json(""),
              json(j("''")),
              json(j("{'text':''}")),
              json("null"),
              ComponentCodec.decode(
                  new byte[0], ProtocolVersion.MINECRAFT_1_20_2, ProtocolState.PLAY),
              ComponentCodec.decode(
                  new byte[0], ProtocolVersion.MINECRAFT_1_21_5, ProtocolState.PLAY),
              ComponentCodec.decode(
                  new byte[] {0x00}, ProtocolVersion.MINECRAFT_1_21_5, ProtocolState.PLAY),
              nbt(StringBinaryTag.stringBinaryTag("")))) {
        assertSame(Component.empty(), empty);
      }
    }

    @Test
    @DisplayName("should tell a real reason apart from an empty one")
    void notEmpty() {
      assertEquals(Component.text(" "), json(j("' '")));
      assertEquals(Component.text("", NamedTextColor.RED), json(j("{'text':'','color':'red'}")));
    }
  }

  // ---------------------------------------------------------------------------
  // JSON of every era
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("JSON")
  class Json {

    @Test
    @DisplayName("should read a bare string, an object and an array root")
    void roots() {
      assertEquals(Component.text("hi"), json(j("'hi'")));
      assertEquals(
          Component.text("hi", NamedTextColor.RED), json(j("{'text':'hi','color':'red'}")));
      assertEquals(Component.text("a").append(Component.text("b")), json(j("['a','b']")));
    }

    @Test
    @DisplayName("should read legacy hover values: text, an SNBT item, an SNBT entity")
    void legacyHovers() {
      assertEquals(
          Component.text("h").hoverEvent(HoverEvent.showText(Component.text("tip"))),
          json(j("{'text':'h','hoverEvent':{'action':'show_text','value':'tip'}}")));
      assertEquals(
          Component.text("h").hoverEvent(HoverEvent.showItem(Key.key("diamond"), 2)),
          json(
              j(
                  "{'text':'h','hoverEvent':{'action':'show_item',"
                      + "'value':'{id:\\'minecraft:diamond\\',Count:2b}'}}")));
      assertEquals(
          Component.text("h")
              .hoverEvent(HoverEvent.showEntity(Key.key("pig"), ENTITY, Component.text("Bob"))),
          json(
              j(
                  "{'text':'h','hoverEvent':{'action':'show_entity','value':{'text':"
                      + "'{type:\\'Pig\\',id:\\'6a085b2c-19fb-4986-b453-231aa942bbec\\',name:\\'Bob\\'}'}}}")));
      assertEquals(
          Component.text("h")
              .hoverEvent(
                  HoverEvent.showEntity(
                      Key.key("pig"), ENTITY, Component.text("Bob", NamedTextColor.RED))),
          json(
              j(
                  "{'text':'h','hoverEvent':{'action':'show_entity','value':'{type:\\'minecraft:pig\\',"
                      + "id:\\'6a085b2c-19fb-4986-b453-231aa942bbec\\',"
                      + "name:\\'{\\\\\\'text\\\\\\':\\\\\\'Bob\\\\\\',\\\\\\'color\\\\\\':\\\\\\'red\\\\\\'}\\'}'}}")));
    }

    @Test
    @DisplayName("should leave out a hover event it cannot read, keeping the component")
    void unreadableHovers() {
      for (String hover :
          List.of(
              "{'action':'show_item','value':'{id:'}",
              "{'action':'show_item','value':'{id:\\'Not A Key\\'}'}",
              "{'action':'show_entity','value':'{type:\\'pig\\',id:\\'nope\\'}'}",
              "{'action':'show_entity','contents':{'type':'pig'}}",
              "{'action':'show_banana','contents':'x'}",
              "{'contents':'x'}",
              "'show_text'")) {
        assertEquals(
            Component.text("h", NamedTextColor.RED),
            json(j("{'text':'h','color':'red','hoverEvent':" + hover + "}")),
            hover);
      }
    }

    @Test
    @DisplayName("should read the 1.21.5 snake case events and their typed fields")
    void snakeCase() {
      assertEquals(
          Component.text("c")
              .clickEvent(ClickEvent.runCommand("/server"))
              .hoverEvent(HoverEvent.showEntity(Key.key("pig"), ENTITY)),
          json(
              j(
                  "{'text':'c','click_event':{'action':'run_command','command':'/server'},"
                      + "'hover_event':{'action':'show_entity','id':'minecraft:pig',"
                      + "'uuid':[1778932524,435898758,-1269619942,-1455244308]}}")));
      assertEquals(
          Component.text("c").clickEvent(ClickEvent.changePage(3)),
          json(j("{'text':'c','click_event':{'action':'change_page','page':3}}")));
      assertEquals(
          Component.text("c").clickEvent(ClickEvent.changePage(3)),
          json(j("{'text':'c','clickEvent':{'action':'change_page','value':'3'}}")));
      assertEquals(
          Component.text("c").hoverEvent(HoverEvent.showItem(Key.key("stone"), 4)),
          json(
              j(
                  "{'text':'c','hover_event':{'action':'show_item','id':'minecraft:stone','count':4}}")));
    }

    @Test
    @DisplayName("should leave out a click event it cannot read")
    void unreadableClicks() {
      for (String click :
          List.of(
              "{'action':'twitch_user_info','value':'x'}",
              "{'action':'change_page','value':'two'}",
              "{'value':'x'}",
              "'run_command'")) {
        assertEquals(
            Component.text("c"), json(j("{'text':'c','clickEvent':" + click + "}")), click);
      }
    }

    @Test
    @DisplayName("should read a custom click payload that is not a string as its text")
    void customPayload() {
      assertEquals(
          Component.text("c")
              .clickEvent(
                  ClickEvent.custom(
                      Key.key("warp:x"), BinaryTagHolder.binaryTagHolder("{\"a\":1}"))),
          json(
              j("{'text':'c','click_event':{'action':'custom','id':'warp:x','payload':{'a':1}}}")));
    }

    @Test
    @DisplayName("should fall back to the text it can read, or the input, when it is no component")
    void fallback() {
      assertEquals(Component.text("not json {"), json("not json {"));
      assertEquals(Component.text("ab"), json(j("{'text':'a','color':5,'extra':[{'text':'b'}]}")));
      assertEquals(Component.text("Fall"), json(j("{'translate':'k','fallback':'Fall','with':7}")));
      assertEquals(Component.text("{\"x\":1}"), json(j("{'x':1}")));
      assertEquals(Component.text("k"), json(j("{'translate':'k','font':'Bad Font','with':{}}")));
    }

    @Test
    @DisplayName("should leave out a font that is not a key")
    void badFont() {
      assertEquals(Component.text("f"), json(j("{'text':'f','font':'Bad Font'}")));
    }
  }

  // ---------------------------------------------------------------------------
  // NBT
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("NBT")
  class Nbt {

    @Test
    @DisplayName("should read bytes of style fields as booleans")
    void booleans() {
      assertEquals(
          Component.text("b")
              .decoration(TextDecoration.BOLD, true)
              .decoration(TextDecoration.ITALIC, false),
          nbt(
              CompoundBinaryTag.builder()
                  .putString("text", "b")
                  .putByte("bold", (byte) 1)
                  .putByte("italic", (byte) 0)
                  .build()));
    }

    @Test
    @DisplayName("should unbox list elements boxed with an empty name")
    void boxedLists() {
      ListBinaryTag extra =
          ListBinaryTag.listBinaryTag(
              BinaryTagTypes.COMPOUND,
              List.of(
                  CompoundBinaryTag.builder().putString("", "a").build(),
                  CompoundBinaryTag.builder()
                      .putString("text", "b")
                      .putString("color", "red")
                      .build()));

      assertEquals(
          Component.text("").append(Component.text("a"), Component.text("b", NamedTextColor.RED)),
          nbt(CompoundBinaryTag.builder().putString("text", "").put("extra", extra).build()));
    }

    @Test
    @DisplayName("should read a list root: its first element, then the others as its children")
    void listRoot() {
      assertEquals(
          Component.text("a").append(Component.text("b")),
          nbt(
              ListBinaryTag.listBinaryTag(
                  BinaryTagTypes.STRING,
                  List.of(
                      StringBinaryTag.stringBinaryTag("a"),
                      StringBinaryTag.stringBinaryTag("b")))));
    }

    @Test
    @DisplayName("should read an entity UUID as ints, and a custom payload as SNBT")
    void intsAndPayloads() {
      CompoundBinaryTag hover =
          CompoundBinaryTag.builder()
              .putString("action", "show_entity")
              .putString("id", "minecraft:pig")
              .put("uuid", IntArrayBinaryTag.intArrayBinaryTag(ENTITY_INTS))
              .build();
      CompoundBinaryTag click =
          CompoundBinaryTag.builder()
              .putString("action", "custom")
              .putString("id", "warp:x")
              .put("payload", CompoundBinaryTag.builder().putInt("a", 1).build())
              .build();

      assertEquals(
          Component.text("e")
              .hoverEvent(HoverEvent.showEntity(Key.key("pig"), ENTITY))
              .clickEvent(
                  ClickEvent.custom(Key.key("warp:x"), BinaryTagHolder.binaryTagHolder("{a:1}"))),
          nbt(
              CompoundBinaryTag.builder()
                  .putString("text", "e")
                  .put("hover_event", hover)
                  .put("click_event", click)
                  .build()));
    }

    @Test
    @DisplayName("should keep what it could read of truncated NBT")
    void truncated() {
      byte[] whole =
          ComponentCodec.encode(
              Component.text("Kept").append(Component.text("also", NamedTextColor.RED)),
              ProtocolVersion.MINECRAFT_1_21_5);
      // Cut in the child's colour: the child keeps its text, the colour is lost.
      byte[] cut = java.util.Arrays.copyOf(whole, whole.length - 6);

      assertEquals(
          Component.text("Kept").append(Component.text("also")),
          ComponentCodec.decode(cut, ProtocolVersion.MINECRAFT_1_21_5, ProtocolState.PLAY));
      assertEquals(
          Component.text("Kept"),
          ComponentCodec.decode(
              java.util.Arrays.copyOf(whole, 14),
              ProtocolVersion.MINECRAFT_1_21_5,
              ProtocolState.PLAY));
    }
  }

  // ---------------------------------------------------------------------------
  // Wire forms
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("wire")
  class Wire {

    @Test
    @DisplayName("should read JSON in the login state of every version")
    void loginJson() {
      ByteBuf buf = Unpooled.buffer();
      byte[] json = j("{'text':'no','color':'red'}").getBytes(StandardCharsets.UTF_8);
      VarInt.write(buf, json.length);
      buf.writeBytes(json);

      assertEquals(
          Component.text("no", NamedTextColor.RED),
          ComponentCodec.decode(buf, ProtocolVersion.MINECRAFT_26_1, ProtocolState.LOGIN));
    }

    @Test
    @DisplayName("should read the bytes there are when the length prefix claims more")
    void shortString() {
      byte[] raw = new byte[] {0x7F, '"', 'h', 'i', '"'};

      assertEquals(
          Component.text("hi"),
          ComponentCodec.decode(raw, ProtocolVersion.MINECRAFT_1_20_2, ProtocolState.PLAY));
    }

    @Test
    @DisplayName("should read a malformed length prefix as text")
    void malformedPrefix() {
      byte[] raw =
          new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 'x'};

      assertEquals(
          Component.text("\u0080\u0080\u0080\u0080\u0080x".replace('\u0080', '\uFFFD')),
          ComponentCodec.decode(raw, ProtocolVersion.MINECRAFT_1_8, ProtocolState.PLAY));
    }
  }

  // ---------------------------------------------------------------------------
  // Round trips
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("round trips")
  class RoundTrips {

    /** A component every version from {@code since} holds whole, with that version. */
    static Stream<Arguments> components() {
      Component rich =
          Component.text("A", NamedTextColor.GOLD)
              .decoration(TextDecoration.BOLD, true)
              .decoration(TextDecoration.ITALIC, false)
              .append(Component.text("b").clickEvent(ClickEvent.runCommand("/server lobby")))
              .append(
                  Component.translatable(
                      "chat.type.text",
                      Component.text("x"),
                      Component.text("y", NamedTextColor.AQUA)
                          .hoverEvent(HoverEvent.showText(Component.text("tip")))));
      Component modern =
          rich.append(
              Component.text("e")
                  .color(TextColor.color(0x123456))
                  .font(Key.key("uniform"))
                  .insertion("ins")
                  .hoverEvent(
                      HoverEvent.showEntity(Key.key("pig"), ENTITY, Component.text("Bob"))));
      Component latest =
          modern.append(
              Component.translatable(
                      "k",
                      "Fallback",
                      List.of(TranslationArgument.component(Component.keybind("key.jump"))))
                  .shadowColor(ShadowColor.shadowColor(0x80FF0000))
                  .clickEvent(ClickEvent.changePage(2))
                  .hoverEvent(HoverEvent.showItem(Key.key("diamond"), 3)));
      return Stream.of(
              ProtocolVersion.MINECRAFT_1_7_6,
              ProtocolVersion.MINECRAFT_1_8,
              ProtocolVersion.MINECRAFT_1_12_2,
              ProtocolVersion.MINECRAFT_1_13_2,
              ProtocolVersion.MINECRAFT_1_15_2,
              ProtocolVersion.MINECRAFT_1_16,
              ProtocolVersion.MINECRAFT_1_19,
              ProtocolVersion.MINECRAFT_1_20_2,
              ProtocolVersion.MINECRAFT_1_20_3,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolVersion.MINECRAFT_1_21_5,
              ProtocolVersion.MINECRAFT_26_1,
              ProtocolVersion.MINECRAFT_26_3)
          .flatMap(
              version ->
                  Stream.of(
                      Arguments.of(version, rich),
                      Arguments.of(
                          version,
                          version.isAtLeast(ProtocolVersion.MINECRAFT_1_16) ? modern : rich),
                      Arguments.of(
                          version,
                          version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_4) ? latest : rich)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("components")
    @DisplayName("should read back what it encodes, in play and login states")
    void roundTrip(ProtocolVersion version, Component component) {
      byte[] play = ComponentCodec.encode(component, version);
      String login = ComponentCodec.json(component, version);

      assertEquals(component, ComponentCodec.decode(play, version, ProtocolState.PLAY));
      assertEquals(component, ComponentCodec.decodeJson(login));
    }
  }
}
