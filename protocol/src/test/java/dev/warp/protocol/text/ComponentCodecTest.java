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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_11;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_11_1;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_12;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_12_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_13;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_13_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_14;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_15;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_15_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_17;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_5;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_5;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_6;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_7;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_9;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_7_6;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_8;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_9;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_26_1;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolVersion;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.BlockNBTComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("ComponentCodec encoding")
class ComponentCodecTest {

  private static final UUID ENTITY = UUID.fromString("6a085b2c-19fb-4986-b453-231aa942bbec");
  private static final int[] ENTITY_INTS = {1778932524, 435898758, -1269619942, -1455244308};

  /** JSON written with single quotes, for readability. */
  static String j(String singleQuoted) {
    return singleQuoted.replace('\'', '"');
  }

  static String json(Component component, ProtocolVersion version) {
    return ComponentCodec.json(component, version);
  }

  /** The NBT a 1.20.3+ client receives, read back into tags. */
  static BinaryTag nbt(Component component, ProtocolVersion version) {
    ByteBuf buf = Unpooled.wrappedBuffer(ComponentCodec.encode(component, version));
    BinaryTag tag = NbtReader.readNameless(buf);
    assertFalse(buf.isReadable(), "nothing may follow the tag");
    return tag;
  }

  CompoundBinaryTag.Builder compound() {
    return CompoundBinaryTag.builder();
  }

  // ---------------------------------------------------------------------------
  // Plain text
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("plain text")
  class PlainText {

    static List<ProtocolVersion> compactVersions() {
      return List.of(MINECRAFT_1_7_6, MINECRAFT_1_8, MINECRAFT_1_20_3, MINECRAFT_26_1);
    }

    static List<ProtocolVersion> objectVersions() {
      return List.of(MINECRAFT_1_9, MINECRAFT_1_16, MINECRAFT_1_20_2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("compactVersions")
    @DisplayName("should collapse into a bare JSON string up to 1.8.9 and from 1.20.3")
    void bareJsonString(ProtocolVersion version) {
      assertEquals(j("'Hello'"), json(Component.text("Hello"), version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("objectVersions")
    @DisplayName("should be a text object from 1.9 to 1.20.2, as vanilla writes it")
    void textObject(ProtocolVersion version) {
      assertEquals(j("{'text':'Hello'}"), json(Component.text("Hello"), version));
    }

    @Test
    @DisplayName("should be a VarInt-prefixed JSON string in the play state before 1.20.3")
    void prefixedJson() {
      byte[] encoded = ComponentCodec.encode(Component.text("Kicked"), MINECRAFT_1_20_2);

      assertArrayEquals(concat(new byte[] {0x11}, utf8(j("{'text':'Kicked'}"))), encoded);
    }

    @Test
    @DisplayName("should be a network NBT string tag from 1.20.3")
    void nbtStringTag() {
      byte[] encoded = ComponentCodec.encode(Component.text("Kicked"), MINECRAFT_1_20_3);

      assertArrayEquals(concat(new byte[] {0x08, 0x00, 0x06}, utf8("Kicked")), encoded);
    }

    @Test
    @DisplayName(
        "should write modified UTF-8 in NBT: U+0000 on two bytes, surrogates on three each")
    void modifiedUtf8() throws IOException {
      String text = "a\u0000\u00E9\u20AC\uD83D\uDE00";

      byte[] encoded = ComponentCodec.encode(Component.text(text), MINECRAFT_1_20_3);

      DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
      assertEquals(0x08, in.readByte());
      assertEquals(text, in.readUTF());
      assertEquals(0, in.available());
      assertEquals(3 + 1 + 2 + 2 + 3 + 6, encoded.length);
    }

    @Test
    @DisplayName("should accept 65 535 bytes of NBT string and refuse one more, writing nothing")
    void nbtStringLimit() {
      String longest = "a".repeat(65_535);
      ByteBuf buf = Unpooled.buffer();
      buf.writeByte(0x7F);

      assertEquals(
          3 + 65_535, ComponentCodec.encode(Component.text(longest), MINECRAFT_1_20_3).length);
      assertThrows(
          IllegalArgumentException.class,
          () -> ComponentCodec.write(buf, Component.text(longest + "a"), MINECRAFT_1_20_3));
      assertEquals(1, buf.writerIndex(), "a refused component leaves the buffer as it was");
    }

    @Test
    @DisplayName("should refuse JSON longer than the version reads: 32 767 until 1.13.2")
    void legacyJsonLimit() {
      Component fits = Component.text("a".repeat(32_756));
      Component tooLong = Component.text("a".repeat(32_757));

      assertEquals(32_767, ComponentCodec.json(fits, MINECRAFT_1_13_2).length());
      ComponentCodec.encode(fits, MINECRAFT_1_13_2);
      assertThrows(
          IllegalArgumentException.class, () -> ComponentCodec.encode(tooLong, MINECRAFT_1_13_2));
      ComponentCodec.encode(Component.text("a".repeat(262_133)), MINECRAFT_1_14);
      assertThrows(
          IllegalArgumentException.class,
          () -> ComponentCodec.encode(Component.text("a".repeat(262_134)), MINECRAFT_1_14));
    }

    @Test
    @DisplayName("should refuse nesting deeper than the 512 levels of NBT a client reads")
    void depthLimit() {
      Component deep = Component.text("x", NamedTextColor.RED);
      for (int i = 0; i < 300; i++) {
        deep = Component.text().content("x").color(NamedTextColor.RED).append(deep).build();
      }
      Component tooDeep = deep;

      assertThrows(
          IllegalArgumentException.class, () -> ComponentCodec.encode(tooDeep, MINECRAFT_1_20_3));
      ComponentCodec.encode(tooDeep, MINECRAFT_1_20_2); // JSON has no such limit
    }
  }

  // ---------------------------------------------------------------------------
  // JSON escaping
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("JSON escaping")
  class Escaping {

    @Test
    @DisplayName("should escape quotes, backslashes, control characters and line separators")
    void controlCharacters() {
      String text = "\" \\ \b \f \n \r \t \u0001 \u001f \u2028 \u2029 \u00e9";

      assertEquals(
          "\"\\\" \\\\ \\b \\f \\n \\r \\t \\u0001 \\u001f \\u2028 \\u2029 \u00e9\"",
          json(Component.text(text), MINECRAFT_1_8));
    }

    @Test
    @DisplayName("should escape HTML characters up to 1.13.2, as the game's Gson did")
    void htmlSafeUntil114() {
      Component text = Component.text("<>&='");
      String escaped = "\\u003c\\u003e\\u0026\\u003d\\u0027";

      assertEquals('"' + escaped + '"', json(text, MINECRAFT_1_8));
      assertEquals("{\"text\":\"" + escaped + "\"}", json(text, MINECRAFT_1_13_2));
      assertEquals("{\"text\":\"<>&='\"}", json(text, MINECRAFT_1_14));
    }

    @Test
    @DisplayName("should escape field names too")
    void names() {
      Component item =
          Component.text(
              "i",
              Style.style(
                  HoverEvent.showItem(
                      HoverEvent.ShowItem.showItem(
                          Key.key("stone"),
                          1,
                          Map.of(
                              Key.key("custom_data"),
                              BinaryTagHolder.binaryTagHolder("{\"a\\\"b\":1b}"))))));

      assertEquals(
          "{\"text\":\"i\",\"hover_event\":{\"action\":\"show_item\",\"id\":\"minecraft:stone\","
              + "\"count\":1,\"components\":{\"minecraft:custom_data\":{\"a\\\"b\":1}}}}",
          json(item, MINECRAFT_1_21_5));
    }
  }

  // ---------------------------------------------------------------------------
  // Styles
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("styles")
  class Styles {

    private final Component styled =
        Component.text("S")
            .color(NamedTextColor.RED)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.UNDERLINED, true)
            .decoration(TextDecoration.STRIKETHROUGH, true)
            .decoration(TextDecoration.OBFUSCATED, true);

    @Test
    @DisplayName("should write the style before the contents up to 1.20.2")
    void gsonOrder() {
      assertEquals(
          j(
              "{'bold':true,'italic':false,'underlined':true,'strikethrough':true,"
                  + "'obfuscated':true,'color':'red','text':'S'}"),
          json(styled, MINECRAFT_1_20_2));
    }

    @Test
    @DisplayName("should write the contents before the style from 1.20.3, in the codec's order")
    void codecOrder() {
      assertEquals(
          j(
              "{'text':'S','color':'red','bold':true,'italic':false,'underlined':true,"
                  + "'strikethrough':true,'obfuscated':true}"),
          json(styled, MINECRAFT_1_20_3));
    }

    @Test
    @DisplayName("should write booleans as bytes in NBT")
    void nbtBooleans() {
      assertEquals(
          compound()
              .putString("text", "S")
              .putString("color", "red")
              .putByte("bold", (byte) 1)
              .putByte("italic", (byte) 0)
              .putByte("underlined", (byte) 1)
              .putByte("strikethrough", (byte) 1)
              .putByte("obfuscated", (byte) 1)
              .build(),
          nbt(styled, MINECRAFT_1_20_3));
    }

    @Test
    @DisplayName("should downsample RGB to the nearest named colour before 1.16")
    void rgb() {
      Component rgb = Component.text("c", TextColor.color(0xFF5556));

      assertEquals(j("{'color':'red','text':'c'}"), json(rgb, MINECRAFT_1_15_2));
      assertEquals(j("{'color':'#FF5556','text':'c'}"), json(rgb, MINECRAFT_1_16));
      assertEquals(
          j("{'color':'#0A0B0C','text':'c'}"),
          json(Component.text("c", TextColor.color(0x0A0B0C)), MINECRAFT_1_16));
    }

    @Test
    @DisplayName("should write insertion from 1.8")
    void insertion() {
      Component inserted = Component.text("i").insertion("/server");

      assertEquals(j("'i'"), json(inserted, MINECRAFT_1_7_6));
      assertEquals(j("{'insertion':'/server','text':'i'}"), json(inserted, MINECRAFT_1_8));
      assertEquals(j("{'text':'i','insertion':'/server'}"), json(inserted, MINECRAFT_1_20_3));
    }

    @Test
    @DisplayName("should write the font from 1.16")
    void font() {
      Component font = Component.text("f").font(Key.key("uniform"));

      assertEquals(j("{'text':'f'}"), json(font, MINECRAFT_1_15_2));
      assertEquals(j("{'font':'minecraft:uniform','text':'f'}"), json(font, MINECRAFT_1_16));
      assertEquals(j("{'text':'f','font':'minecraft:uniform'}"), json(font, MINECRAFT_1_20_3));
    }

    @Test
    @DisplayName("should write the shadow colour as an ARGB int from 1.21.4")
    void shadowColor() {
      Component shadow =
          Component.text("s").style(Style.style().shadowColor(ShadowColor.shadowColor(0xFF000000)));

      assertEquals(j("'s'"), json(shadow, MINECRAFT_1_21_2));
      assertEquals(j("{'text':'s','shadow_color':-16777216}"), json(shadow, MINECRAFT_1_21_4));
      assertEquals(
          compound().putString("text", "s").putInt("shadow_color", 0xFF000000).build(),
          nbt(shadow, MINECRAFT_1_21_4));
    }
  }

  // ---------------------------------------------------------------------------
  // Children and translation arguments
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("children and arguments")
  class Children {

    private final Component nested =
        Component.text("A", NamedTextColor.GOLD)
            .append(Component.text("b", Style.style(TextDecoration.BOLD)))
            .append(Component.text("c"));

    @Test
    @DisplayName("should collapse plain children into strings in JSON")
    void json() {
      assertEquals(
          j("{'color':'gold','extra':[{'bold':true,'text':'b'},'c'],'text':'A'}"),
          ComponentCodecTest.json(nested, MINECRAFT_1_8));
    }

    @Test
    @DisplayName(
        "should keep an NBT list to one type: plain text boxed when the children are mixed")
    void mixedNbt() {
      ListBinaryTag extra =
          ListBinaryTag.listBinaryTag(
              net.kyori.adventure.nbt.BinaryTagTypes.COMPOUND,
              List.of(
                  compound().putString("text", "b").putByte("bold", (byte) 1).build(),
                  compound().putString("", "c").build()));

      assertEquals(
          compound().putString("text", "A").put("extra", extra).putString("color", "gold").build(),
          nbt(nested, MINECRAFT_1_20_3));
    }

    @Test
    @DisplayName("should write a list of strings in NBT when every child is plain text")
    void plainNbt() {
      Component plain = Component.text("").append(Component.text("a"), Component.text("b"));

      assertEquals(
          compound()
              .putString("text", "")
              .put(
                  "extra",
                  ListBinaryTag.listBinaryTag(
                      net.kyori.adventure.nbt.BinaryTagTypes.STRING,
                      List.of(
                          StringBinaryTag.stringBinaryTag("a"),
                          StringBinaryTag.stringBinaryTag("b"))))
              .build(),
          nbt(plain, MINECRAFT_1_20_3));
    }

    @Test
    @DisplayName("should write numbers and booleans among translation arguments as their text")
    void primitiveArguments() {
      Component translatable =
          Component.translatable(
              "k",
              List.of(
                  TranslationArgument.numeric(5),
                  TranslationArgument.bool(true),
                  TranslationArgument.component(Component.text("x", NamedTextColor.AQUA))));

      assertEquals(
          j("{'translate':'k','with':['5','true',{'color':'aqua','text':'x'}]}"),
          ComponentCodecTest.json(translatable, MINECRAFT_1_8));
    }

    @Test
    @DisplayName("should write a translation's fallback from 1.19.4")
    void fallback() {
      Component translatable = Component.translatable("warp.k", "Fallback");

      assertEquals(
          j("{'translate':'warp.k'}"), ComponentCodecTest.json(translatable, MINECRAFT_1_19_3));
      assertEquals(
          j("{'translate':'warp.k','fallback':'Fallback'}"),
          ComponentCodecTest.json(translatable, MINECRAFT_1_19_4));
    }
  }

  // ---------------------------------------------------------------------------
  // Contents
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("contents")
  class Contents {

    @Test
    @DisplayName("should send a keybind's translation before 1.12, which has no keybind")
    void keybind() {
      Component keybind = Component.keybind("key.jump");

      assertEquals(j("{'translate':'key.jump'}"), json(keybind, MINECRAFT_1_11_1));
      assertEquals(j("{'keybind':'key.jump'}"), json(keybind, MINECRAFT_1_12));
    }

    @Test
    @DisplayName("should send a score's resolved value as text, the score when it has none")
    @SuppressWarnings("deprecation") // the resolved value
    void score() {
      Component unresolved = Component.score("*", "kills");
      Component resolved = Component.score().name("*").objective("kills").value("7").build();

      assertEquals(j("''"), json(unresolved, MINECRAFT_1_7_6));
      assertEquals(
          j("{'score':{'name':'*','objective':'kills'}}"), json(unresolved, MINECRAFT_1_8));
      assertEquals(j("'7'"), json(resolved, MINECRAFT_1_20_3));
      assertEquals(j("'7'"), json(resolved, MINECRAFT_1_7_6));
    }

    @Test
    @DisplayName("should send a selector as its pattern before 1.8, its separator from 1.17")
    void selector() {
      Component selector = Component.selector("@p", Component.text("; "));

      assertEquals(j("'@p'"), json(selector, MINECRAFT_1_7_6));
      assertEquals(j("{'selector':'@p'}"), json(selector, MINECRAFT_1_16_4));
      assertEquals(
          j("{'selector':'@p','separator':{'text':'; '}}"), json(selector, MINECRAFT_1_17));
      assertEquals(j("{'selector':'@p','separator':'; '}"), json(selector, MINECRAFT_1_20_3));
    }

    @Test
    @DisplayName("should send NBT contents from 1.14, command storage from 1.15")
    void nbtContents() {
      Component block =
          Component.blockNBT(
              "Items",
              BlockNBTComponent.WorldPos.worldPos(
                  BlockNBTComponent.WorldPos.Coordinate.absolute(1),
                  BlockNBTComponent.WorldPos.Coordinate.relative(2),
                  BlockNBTComponent.WorldPos.Coordinate.absolute(3)));
      Component storage = Component.storageNBT("path", Key.key("warp:data"));
      Component entity =
          Component.entityNBT()
              .nbtPath("Name")
              .selector("@s")
              .interpret(true)
              .separator(Component.text(","))
              .build();

      assertEquals(j("{'text':''}"), json(block, MINECRAFT_1_13_2));
      assertEquals(
          j("{'nbt':'Items','interpret':false,'block':'1 ~2 3'}"), json(block, MINECRAFT_1_14));
      assertEquals(j("{'text':''}"), json(storage, MINECRAFT_1_14));
      assertEquals(
          j("{'nbt':'path','interpret':false,'storage':'warp:data'}"),
          json(storage, MINECRAFT_1_15));
      assertEquals(j("{'nbt':'path','storage':'warp:data'}"), json(storage, MINECRAFT_1_20_3));
      assertEquals(
          j("{'nbt':'Name','interpret':true,'entity':'@s'}"), json(entity, MINECRAFT_1_16_4));
      assertEquals(
          j("{'nbt':'Name','interpret':true,'separator':',','entity':'@s'}"),
          json(entity, MINECRAFT_1_20_3));
    }

    @Test
    @DisplayName("should send objects from 1.21.9, nothing before")
    void objects() {
      Component sprite = Component.object(ObjectContents.sprite(Key.key("item/diamond")));
      Component atlas =
          Component.object(ObjectContents.sprite(Key.key("gui"), Key.key("hud/heart")));

      assertEquals(j("''"), json(sprite, MINECRAFT_1_21_7));
      assertEquals(j("{'sprite':'minecraft:item/diamond'}"), json(sprite, MINECRAFT_1_21_9));
      assertEquals(
          j("{'atlas':'minecraft:gui','sprite':'minecraft:hud/heart'}"),
          json(atlas, MINECRAFT_1_21_9));
    }

    @Test
    @DisplayName("should send a player head by name, or by profile")
    void playerHeads() {
      Component byName = Component.object(ObjectContents.playerHead("Steve"));
      Component byProfile =
          Component.object(
              ObjectContents.playerHead()
                  .id(ENTITY)
                  .name("Steve")
                  .profileProperty(PlayerHeadObjectContents.property("textures", "v", "sig"))
                  .profileProperty(PlayerHeadObjectContents.property("other", "w"))
                  .texture(Key.key("skin"))
                  .hat(false)
                  .build());

      assertEquals(j("{'player':'Steve','hat':true}"), json(byName, MINECRAFT_1_21_9));
      assertEquals(
          j(
              "{'player':{'name':'Steve','id':[1778932524,435898758,-1269619942,-1455244308],"
                  + "'properties':[{'name':'textures','value':'v','signature':'sig'},"
                  + "{'name':'other','value':'w'}],'texture':'minecraft:skin'},'hat':false}"),
          json(byProfile, MINECRAFT_1_21_9));
    }
  }

  // ---------------------------------------------------------------------------
  // Click events
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("click events")
  class ClickEvents {

    private String click(ClickEvent event, ProtocolVersion version) {
      return json(Component.text("c").clickEvent(event), version);
    }

    @Test
    @DisplayName("should write a value before 1.21.5, a typed field from 1.21.5")
    void typedFromSnakeCase() {
      assertEquals(
          j("{'clickEvent':{'action':'run_command','value':'/server'},'text':'c'}"),
          click(ClickEvent.runCommand("/server"), MINECRAFT_1_20_2));
      assertEquals(
          j("{'text':'c','clickEvent':{'action':'suggest_command','value':'/msg '}}"),
          click(ClickEvent.suggestCommand("/msg "), MINECRAFT_1_21_4));
      assertEquals(
          j("{'text':'c','click_event':{'action':'run_command','command':'/server'}}"),
          click(ClickEvent.runCommand("/server"), MINECRAFT_1_21_5));
      assertEquals(
          j("{'text':'c','click_event':{'action':'open_url','url':'https://a.b'}}"),
          click(ClickEvent.openUrl("https://a.b"), MINECRAFT_1_21_5));
      assertEquals(
          j("{'text':'c','click_event':{'action':'copy_to_clipboard','value':'v'}}"),
          click(ClickEvent.copyToClipboard("v"), MINECRAFT_1_21_5));
    }

    @Test
    @DisplayName("should copy to the clipboard from 1.15 only")
    void clipboard() {
      assertEquals(j("{'text':'c'}"), click(ClickEvent.copyToClipboard("v"), MINECRAFT_1_14));
      assertEquals(
          j("{'clickEvent':{'action':'copy_to_clipboard','value':'v'},'text':'c'}"),
          click(ClickEvent.copyToClipboard("v"), MINECRAFT_1_15));
    }

    @Test
    @DisplayName("should change pages from 1.8, the page a positive int from 1.21.5")
    void changePage() {
      assertEquals(j("'c'"), click(ClickEvent.changePage(2), MINECRAFT_1_7_6));
      assertEquals(
          j("{'clickEvent':{'action':'change_page','value':'2'},'text':'c'}"),
          click(ClickEvent.changePage(2), MINECRAFT_1_8));
      assertEquals(
          j("{'text':'c','click_event':{'action':'change_page','page':2}}"),
          click(ClickEvent.changePage(2), MINECRAFT_1_21_5));
      assertEquals(j("'c'"), click(ClickEvent.changePage(0), MINECRAFT_1_21_5));
    }

    @Test
    @DisplayName("should leave out from 1.21.5 a URL a client refuses, and complete a bare host")
    void untrustedUrls() {
      assertEquals(
          j("{'text':'c','click_event':{'action':'open_url','url':'https://warp.dev/x'}}"),
          click(ClickEvent.openUrl("warp.dev/x"), MINECRAFT_1_21_5));
      assertEquals(
          j("{'text':'c','click_event':{'action':'open_url','url':'HTTP://a'}}"),
          click(ClickEvent.openUrl("HTTP://a"), MINECRAFT_1_21_5));
      assertEquals(j("'c'"), click(ClickEvent.openUrl("javascript:alert(1)"), MINECRAFT_1_21_5));
      assertEquals(j("'c'"), click(ClickEvent.openUrl("http://a b"), MINECRAFT_1_21_5));
      assertEquals(j("'c'"), click(ClickEvent.openUrl("a b"), MINECRAFT_1_21_5));
      assertEquals(
          j("{'text':'c','clickEvent':{'action':'open_url','value':'warp.dev'}}"),
          click(ClickEvent.openUrl("warp.dev"), MINECRAFT_1_21_4));
    }

    @Test
    @DisplayName("should leave out from 1.21.5 a command with a character chat refuses")
    void chatCharacters() {
      for (String command : List.of("/say §c", "/say \n", "/say \u007f")) {
        assertEquals(j("'c'"), click(ClickEvent.runCommand(command), MINECRAFT_1_21_5));
        assertEquals(j("'c'"), click(ClickEvent.suggestCommand(command), MINECRAFT_1_21_5));
      }
      assertEquals(
          j("{'text':'c','clickEvent':{'action':'run_command','value':'/say §c'}}"),
          click(ClickEvent.runCommand("/say §c"), MINECRAFT_1_21_4));
      assertEquals(
          j("{'text':'c','click_event':{'action':'run_command','command':'/say ~'}}"),
          click(ClickEvent.runCommand("/say ~"), MINECRAFT_1_21_5));
    }

    @Test
    @DisplayName("should send custom actions from 1.21.6, with their NBT payload")
    void custom() {
      ClickEvent custom =
          ClickEvent.custom(Key.key("warp:menu"), BinaryTagHolder.binaryTagHolder("{page:2}"));
      ClickEvent malformed =
          ClickEvent.custom(Key.key("warp:menu"), BinaryTagHolder.binaryTagHolder("{page:"));

      assertEquals(j("'c'"), click(custom, MINECRAFT_1_21_5));
      assertEquals(
          j("{'text':'c','click_event':{'action':'custom','id':'warp:menu','payload':{'page':2}}}"),
          click(custom, MINECRAFT_1_21_6));
      assertEquals(
          compound()
              .putString("text", "c")
              .put(
                  "click_event",
                  compound()
                      .putString("action", "custom")
                      .putString("id", "warp:menu")
                      .put("payload", compound().putInt("page", 2).build())
                      .build())
              .build(),
          nbt(Component.text("c").clickEvent(custom), MINECRAFT_1_21_6));
      assertEquals(
          j("{'text':'c','click_event':{'action':'custom','id':'warp:menu'}}"),
          click(malformed, MINECRAFT_1_21_6));
    }

    @Test
    @DisplayName("should never send open_file, which servers may not, nor a dialog")
    void neverSent() {
      assertEquals(j("'c'"), click(ClickEvent.openFile("/etc/passwd"), MINECRAFT_1_8));
      assertEquals(j("'c'"), click(ClickEvent.openFile("/etc/passwd"), MINECRAFT_26_1));
      assertEquals(
          j("'c'"),
          click(
              ClickEvent.showDialog(new net.kyori.adventure.dialog.DialogLike() {}),
              MINECRAFT_26_1));
    }
  }

  // ---------------------------------------------------------------------------
  // Hover events
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("hover events")
  class HoverEvents {

    private String hover(HoverEvent<?> event, ProtocolVersion version) {
      return json(Component.text("h").hoverEvent(event), version);
    }

    @Test
    @DisplayName("should show text as a value, contents from 1.16, a value again from 1.21.5")
    void showText() {
      HoverEvent<Component> tip = HoverEvent.showText(Component.text("tip", NamedTextColor.GREEN));

      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_text','value':{'color':'green','text':'tip'}},'text':'h'}"),
          hover(tip, MINECRAFT_1_15_2));
      assertEquals(
          j("{'hoverEvent':{'action':'show_text','value':{'text':'tip'}},'text':'h'}"),
          hover(HoverEvent.showText(Component.text("tip")), MINECRAFT_1_15_2));
      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_text','contents':{'color':'green','text':'tip'}},'text':'h'}"),
          hover(tip, MINECRAFT_1_16));
      assertEquals(
          j(
              "{'text':'h','hover_event':{'action':'show_text','value':{'text':'tip','color':'green'}}}"),
          hover(tip, MINECRAFT_1_21_5));
      assertEquals(
          j("{'hoverEvent':{'action':'show_text','value':'tip'},'text':'h'}"),
          hover(HoverEvent.showText(Component.text("tip")), MINECRAFT_1_8));
    }

    @Test
    @DisplayName("should show an item as SNBT text before 1.16")
    @SuppressWarnings("deprecation") // the pre-1.20.5 item NBT
    void legacyItem() {
      HoverEvent<HoverEvent.ShowItem> item =
          HoverEvent.showItem(
              Key.key("diamond_sword"), 2, BinaryTagHolder.binaryTagHolder("{Damage:3}"));

      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_item','value':"
                  + "{'text':'{id:\\'minecraft:diamond_sword\\',Count:2b,tag:{Damage:3}}'}},"
                  + "'text':'h'}"),
          hover(item, MINECRAFT_1_15_2));
      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_item','value':"
                  + "'{id:\\'minecraft:stone\\',Count:1b}'},'text':'h'}"),
          hover(HoverEvent.showItem(Key.key("stone"), 1), MINECRAFT_1_8));
    }

    @Test
    @DisplayName("should show an item in contents from 1.16, its count when not 1 until 1.20.4")
    @SuppressWarnings("deprecation") // the pre-1.20.5 item NBT
    void itemContents() {
      assertEquals(
          j("{'hoverEvent':{'action':'show_item','contents':{'id':'minecraft:stone'}},'text':'h'}"),
          hover(HoverEvent.showItem(Key.key("stone"), 1), MINECRAFT_1_16));
      assertEquals(
          j(
              "{'text':'h','hoverEvent':{'action':'show_item','contents':"
                  + "{'id':'minecraft:stone','count':3,'tag':'{a:1b}'}}}"),
          hover(
              HoverEvent.showItem(Key.key("stone"), 3, BinaryTagHolder.binaryTagHolder("{a:1b}")),
              MINECRAFT_1_20_3));
      assertEquals(
          j(
              "{'text':'h','hoverEvent':{'action':'show_item','contents':"
                  + "{'id':'minecraft:stone','count':1}}}"),
          hover(
              HoverEvent.showItem(Key.key("stone"), 1, BinaryTagHolder.binaryTagHolder("{a:1b}")),
              MINECRAFT_1_20_5));
    }

    @Test
    @DisplayName("should show an item's data components from 1.20.5, inlined from 1.21.5")
    void itemComponents() {
      HoverEvent<HoverEvent.ShowItem> item =
          HoverEvent.showItem(
              HoverEvent.ShowItem.showItem(
                  Key.key("stone"),
                  1,
                  Map.of(
                      Key.key("custom_name"), BinaryTagHolder.binaryTagHolder("'Rock'"),
                      Key.key("glint"), DataComponentValue.removed())));

      String contents = json(Component.text("h").hoverEvent(item), MINECRAFT_1_20_5);
      String inlined = hover(item, MINECRAFT_1_21_5);

      assertEquals(
          true,
          contents.contains(j("'contents':{'id':'minecraft:stone','count':1,'components':{")));
      assertEquals(true, contents.contains(j("'minecraft:custom_name':'Rock'")));
      assertEquals(true, contents.contains(j("'!minecraft:glint':{}")));
      assertEquals(
          true,
          inlined.startsWith(
              j(
                  "{'text':'h','hover_event':{'action':'show_item','id':'minecraft:stone','count':1,'components':{")));
      assertEquals(
          j("{'text':'h','hover_event':{'action':'show_item','id':'minecraft:stone','count':1}}"),
          hover(
              HoverEvent.showItem(
                  HoverEvent.ShowItem.showItem(
                      Key.key("stone"),
                      1,
                      Map.of(Key.key("bad"), BinaryTagHolder.binaryTagHolder("{unclosed")))),
              MINECRAFT_1_21_5));
    }

    @Test
    @DisplayName("should show an entity as SNBT text before 1.16, its name as JSON from 1.13")
    void legacyEntity() {
      HoverEvent<HoverEvent.ShowEntity> entity =
          HoverEvent.showEntity(Key.key("pig"), ENTITY, Component.text("Bob", NamedTextColor.RED));

      assertEquals(j("'h'"), hover(entity, MINECRAFT_1_7_6));
      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_entity','value':'{type:\\'minecraft:pig\\',"
                  + "id:\\'6a085b2c-19fb-4986-b453-231aa942bbec\\',name:\\'Bob\\'}'},'text':'h'}"),
          hover(entity, MINECRAFT_1_8));
      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_entity','value':{'text':'{type:\\'minecraft:pig\\',"
                  + "id:\\'6a085b2c-19fb-4986-b453-231aa942bbec\\',name:\\'Bob\\'}'}},'text':'h'}"),
          hover(entity, MINECRAFT_1_12_2));
      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_entity','value':{'text':'{type:\\'minecraft:pig\\',"
                  + "id:\\'6a085b2c-19fb-4986-b453-231aa942bbec\\',"
                  + "name:\\'{\\\\\\'color\\\\\\':\\\\\\'red\\\\\\',\\\\\\'text\\\\\\':\\\\\\'Bob\\\\\\'}\\'}'}},"
                  + "'text':'h'}"),
          hover(entity, MINECRAFT_1_13));
    }

    @Test
    @DisplayName("should show an entity in contents from 1.16, its UUID as ints from 1.20.3")
    void entityContents() {
      HoverEvent<HoverEvent.ShowEntity> named =
          HoverEvent.showEntity(Key.key("pig"), ENTITY, Component.text("Bob"));
      HoverEvent<HoverEvent.ShowEntity> unnamed = HoverEvent.showEntity(Key.key("pig"), ENTITY);

      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_entity','contents':{'type':'minecraft:pig',"
                  + "'id':'6a085b2c-19fb-4986-b453-231aa942bbec','name':{'text':'Bob'}}},'text':'h'}"),
          hover(named, MINECRAFT_1_16));
      assertEquals(
          j(
              "{'text':'h','hoverEvent':{'action':'show_entity','contents':{'type':'minecraft:pig',"
                  + "'id':[1778932524,435898758,-1269619942,-1455244308]}}}"),
          hover(unnamed, MINECRAFT_1_20_3));
      assertEquals(
          j(
              "{'text':'h','hover_event':{'action':'show_entity','id':'minecraft:pig',"
                  + "'uuid':[1778932524,435898758,-1269619942,-1455244308],'name':'Bob'}}"),
          hover(named, MINECRAFT_1_21_5));
      assertEquals(
          compound()
              .putString("text", "h")
              .put(
                  "hover_event",
                  compound()
                      .putString("action", "show_entity")
                      .putString("id", "minecraft:pig")
                      .put("uuid", IntArrayBinaryTag.intArrayBinaryTag(ENTITY_INTS))
                      .build())
              .build(),
          nbt(Component.text("h").hoverEvent(unnamed), MINECRAFT_1_21_5));
    }

    @Test
    @DisplayName("should show an achievement up to 1.11.2 only")
    @SuppressWarnings("deprecation") // achievements, removed in 1.12
    void achievement() {
      HoverEvent<String> achievement = HoverEvent.showAchievement("achievement.openInventory");

      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_achievement','value':"
                  + "{'text':'achievement.openInventory'}},'text':'h'}"),
          hover(achievement, MINECRAFT_1_11));
      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_achievement','value':'achievement.openInventory'},"
                  + "'text':'h'}"),
          hover(achievement, MINECRAFT_1_8));
      assertEquals(j("{'text':'h'}"), hover(achievement, MINECRAFT_1_12));
    }
  }

  // ---------------------------------------------------------------------------
  // Reading encoded components
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("readRaw")
  class ReadRaw {

    @Test
    @DisplayName("should read a JSON string with its prefix, and an NBT tag, leaving what follows")
    void readsOneComponent() {
      for (ProtocolVersion version : List.of(MINECRAFT_1_20_2, MINECRAFT_1_20_3)) {
        byte[] encoded = ComponentCodec.encode(Component.text("x", NamedTextColor.RED), version);
        ByteBuf buf = Unpooled.buffer().writeBytes(encoded).writeByte(0x2A);

        assertArrayEquals(encoded, ComponentCodec.readRaw(buf, version));
        assertEquals(0x2A, buf.readByte());
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Legacy action bar
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("legacyActionBar")
  class LegacyActionBar {

    @Test
    @DisplayName("should move styles into section sign codes, keeping translatables")
    void sectionCodes() {
      Component bar =
          Component.text("Hi ", NamedTextColor.RED)
              .append(
                  Component.text("you", Style.style(TextDecoration.BOLD, TextDecoration.ITALIC)))
              .append(
                  Component.translatable("k", Component.text("arg", NamedTextColor.BLUE))
                      .color(NamedTextColor.RED))
              .append(Component.text("", NamedTextColor.GREEN))
              .append(
                  Component.text(
                      "!",
                      Style.style(
                          TextDecoration.OBFUSCATED,
                          TextDecoration.STRIKETHROUGH,
                          TextDecoration.UNDERLINED)));

      assertEquals(
          j(
              "{'extra':['§r§c','Hi ','§r§c§l§o','you','§r§c',{'translate':'k','with':['arg']},"
                  + "'§r§c§k§m§n','!'],'text':''}"),
          json(ComponentCodec.legacyActionBar(bar), MINECRAFT_1_8));
    }

    @Test
    @DisplayName("should add no code to unstyled text, and reset after styled text")
    void unstyled() {
      Component bar =
          Component.text("a")
              .append(Component.text("b", NamedTextColor.WHITE), Component.text("c"));

      assertEquals(
          j("{'extra':['a','§r§f','b','§r','c'],'text':''}"),
          json(ComponentCodec.legacyActionBar(bar), MINECRAFT_1_8));
    }

    @Test
    @DisplayName("should downsample RGB to its nearest named colour")
    void rgb() {
      assertEquals(
          j("{'extra':['§r§c','x'],'text':''}"),
          json(
              ComponentCodec.legacyActionBar(Component.text("x", TextColor.color(0xFF5556))),
              MINECRAFT_1_8));
    }
  }

  static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
