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
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolVersion;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.BlockNBTComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.serializer.gson.GsonDataComponentValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The features each version leaves out, seen where they would show: alongside a style the version
 * writes, so that the component is no bare string; and alone in a version that writes plain text as
 * a bare string, where a feature left out makes it one.
 */
@DisplayName("ComponentWriter feature gates")
class ComponentWriterGatesTest {

  private static final ProtocolVersion V1_7 = ProtocolVersion.MINECRAFT_1_7_6;
  private static final ProtocolVersion V1_8 = ProtocolVersion.MINECRAFT_1_8;
  private static final ProtocolVersion V1_12 = ProtocolVersion.MINECRAFT_1_12;
  private static final ProtocolVersion V1_16 = ProtocolVersion.MINECRAFT_1_16_4;
  private static final ProtocolVersion V1_20_2 = ProtocolVersion.MINECRAFT_1_20_2;
  private static final ProtocolVersion V1_20_3 = ProtocolVersion.MINECRAFT_1_20_3;
  private static final ProtocolVersion V1_21_2 = ProtocolVersion.MINECRAFT_1_21_2;
  private static final ProtocolVersion V1_21_4 = ProtocolVersion.MINECRAFT_1_21_4;
  private static final ProtocolVersion V1_21_5 = ProtocolVersion.MINECRAFT_1_21_5;
  private static final ProtocolVersion V1_21_9 = ProtocolVersion.MINECRAFT_1_21_9;
  private static final UUID ID = UUID.fromString("6a085b2c-19fb-4986-b453-231aa942bbec");

  private String json(Component component, ProtocolVersion version) {
    return ComponentCodec.json(component, version);
  }

  @Nested
  @DisplayName("style fields")
  class StyleFields {

    @Test
    @DisplayName("should leave out what the version lacks, next to what it writes")
    void besideAColour() {
      assertEquals(
          j("{'color':'red','text':'i'}"),
          json(Component.text("i", NamedTextColor.RED).insertion("x"), V1_7));
      assertEquals(
          j("{'text':'s','color':'red'}"),
          json(
              Component.text("s", NamedTextColor.RED)
                  .shadowColor(ShadowColor.shadowColor(0xFF000000)),
              V1_21_2));
      assertEquals(
          j("{'color':'red','text':'f'}"),
          json(Component.text("f", NamedTextColor.RED).font(Key.key("uniform")), V1_8));
    }

    @Test
    @DisplayName("should write plain text as a bare string when only left-out fields are set")
    void aloneInCompactVersions() {
      assertEquals(j("'f'"), json(Component.text("f").font(Key.key("uniform")), V1_8));
      assertEquals(
          j("'c'"), json(Component.text("c").clickEvent(ClickEvent.copyToClipboard("v")), V1_8));
      assertEquals(
          j("'h'"),
          json(Component.text("h").hoverEvent(HoverEvent.showEntity(Key.key("pig"), ID)), V1_7));
      assertEquals(
          j("{'text':'d','bold':false}"),
          json(Component.text("d").decoration(TextDecoration.BOLD, false), V1_20_3));
    }

    @Test
    @DisplayName("should keep a URL, a command or a page a version before 1.21.5 takes as it is")
    void untypedEvents() {
      assertEquals(
          j("{'clickEvent':{'action':'open_url','value':'javascript:x'},'text':'c'}"),
          json(Component.text("c").clickEvent(ClickEvent.openUrl("javascript:x")), V1_20_2));
      assertEquals(
          j("{'clickEvent':{'action':'change_page','value':'1'},'text':'c'}"),
          json(Component.text("c").clickEvent(ClickEvent.changePage(1)), V1_20_2));
      assertEquals(
          j("{'text':'c','click_event':{'action':'open_url','url':'https://a'}}"),
          json(Component.text("c").clickEvent(ClickEvent.openUrl("https://a")), V1_21_5));
    }

    @Test
    @DisplayName("should leave out hover events the version lacks, next to what it writes")
    @SuppressWarnings("deprecation") // achievements, removed in 1.12
    void hovers() {
      assertEquals(
          j("{'color':'red','text':'h'}"),
          json(
              Component.text("h", NamedTextColor.RED)
                  .hoverEvent(HoverEvent.showEntity(Key.key("pig"), ID)),
              V1_7));
      assertEquals(
          j("{'color':'red','text':'h'}"),
          json(
              Component.text("h", NamedTextColor.RED)
                  .hoverEvent(HoverEvent.showAchievement("achievement.x")),
              V1_12));
      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_entity','value':'{type:\\'minecraft:pig\\',"
                  + "id:\\'6a085b2c-19fb-4986-b453-231aa942bbec\\'}'},'text':'h'}"),
          json(Component.text("h").hoverEvent(HoverEvent.showEntity(Key.key("pig"), ID)), V1_8));
    }
  }

  @Nested
  @DisplayName("contents")
  class Contents {

    @Test
    @DisplayName("should write substitute contents in an object where plain text is no string")
    @SuppressWarnings("deprecation") // the resolved value of a score
    void substitutes() {
      assertEquals(
          j("{'text':'7'}"),
          json(Component.score().name("*").objective("o").value("7").build(), V1_16));
      assertEquals(
          j("{'score':{'name':'*','objective':'o'}}"), json(Component.score("*", "o"), V1_16));
      assertEquals(
          j("{'color':'red','text':''}"),
          json(Component.score("*", "o").color(NamedTextColor.RED), V1_7));
      assertEquals(
          j("{'color':'red','text':'@p'}"),
          json(Component.selector("@p").color(NamedTextColor.RED), V1_7));
      assertEquals(
          j("{'text':''}"), json(Component.object(ObjectContents.sprite(Key.key("a"))), V1_20_2));
      Component nbt =
          Component.blockNBT(
              "p",
              BlockNBTComponent.WorldPos.worldPos(
                  BlockNBTComponent.WorldPos.Coordinate.absolute(0),
                  BlockNBTComponent.WorldPos.Coordinate.absolute(0),
                  BlockNBTComponent.WorldPos.Coordinate.absolute(0)));
      assertEquals(j("''"), json(nbt, V1_8));
      assertEquals(j("{'nbt':'p','block':'0 0 0'}"), json(nbt, V1_20_3));
    }

    @Test
    @DisplayName("should write a player head by name only when it has nothing else")
    void playerHeads() {
      PlayerHeadObjectContents.Builder steve = ObjectContents.playerHead().name("Steve");

      assertEquals(
          j(
              "{'player':{'name':'Steve','id':[1778932524,435898758,-1269619942,-1455244308]},"
                  + "'hat':true}"),
          json(Component.object(steve.id(ID).build()), V1_21_9));
      assertEquals(
          j("{'player':{'name':'Steve','properties':[{'name':'k','value':'v'}]},'hat':true}"),
          json(
              Component.object(
                  ObjectContents.playerHead()
                      .name("Steve")
                      .profileProperty(PlayerHeadObjectContents.property("k", "v"))
                      .build()),
              V1_21_9));
      assertEquals(
          j("{'player':{'name':'Steve','texture':'minecraft:t'},'hat':true}"),
          json(
              Component.object(
                  ObjectContents.playerHead().name("Steve").texture(Key.key("t")).build()),
              V1_21_9));
      assertEquals(
          j("{'player':{'id':[1778932524,435898758,-1269619942,-1455244308]},'hat':true}"),
          json(Component.object(ObjectContents.playerHead(ID)), V1_21_9));
    }
  }

  @Nested
  @DisplayName("NBT lists and depth")
  class NbtLists {

    private BinaryTag nbt(Component component) {
      ByteBuf buf = Unpooled.wrappedBuffer(ComponentCodec.encode(component, V1_21_4));
      return NbtReader.readNameless(buf);
    }

    @Test
    @DisplayName("should box every plain child of a mixed list, wherever it stands")
    void boxed() {
      Component mixed =
          Component.text("")
              .append(Component.text("a"))
              .append(Component.text("b"))
              .append(Component.text("c", NamedTextColor.RED));

      assertEquals(
          CompoundBinaryTag.builder()
              .putString("text", "")
              .put(
                  "extra",
                  ListBinaryTag.listBinaryTag(
                      BinaryTagTypes.COMPOUND,
                      List.of(
                          CompoundBinaryTag.builder().putString("", "a").build(),
                          CompoundBinaryTag.builder().putString("", "b").build(),
                          CompoundBinaryTag.builder()
                              .putString("text", "c")
                              .putString("color", "red")
                              .build())))
              .build(),
          nbt(mixed));
    }

    @Test
    @DisplayName("should take a wide component, closing what it opens")
    void wide() {
      TextComponent.Builder wide = Component.text().content("w");
      for (int i = 0; i < 400; i++) {
        wide.append(Component.text(i, NamedTextColor.RED));
      }

      BinaryTag tag = nbt(wide.build());

      assertEquals(
          400,
          ((ListBinaryTag) Objects.requireNonNull(((CompoundBinaryTag) tag).get("extra"))).size());
    }

    @Test
    @DisplayName("should open 512 lists and compounds, and refuse one more")
    void depthLimit() {
      ByteBuf buf = Unpooled.buffer();
      NbtTree tree = new NbtTree(buf);
      tree.beginObjectValue();
      for (int i = 1; i < NbtTree.MAX_DEPTH; i++) {
        tree.beginObject("c");
      }

      assertThrows(
          IllegalArgumentException.class, () -> tree.beginList("l", TreeSink.Kind.STRING, 0));
    }

    @Test
    @DisplayName("should refuse NBT data nested deeper than a client reads")
    void deepData() {
      CompoundBinaryTag deep = CompoundBinaryTag.empty();
      for (int i = 0; i < NbtTree.MAX_DEPTH; i++) {
        deep = CompoundBinaryTag.builder().put("c", deep).build();
      }
      CompoundBinaryTag tooDeep = deep;
      ListBinaryTag deepList = ListBinaryTag.empty();
      for (int i = 0; i < NbtTree.MAX_DEPTH; i++) {
        deepList = ListBinaryTag.listBinaryTag(BinaryTagTypes.LIST, List.of(deepList));
      }
      ListBinaryTag tooDeepList = deepList;

      assertThrows(
          IllegalArgumentException.class, () -> new NbtTree(Unpooled.buffer()).tag("t", tooDeep));
      assertThrows(
          IllegalArgumentException.class,
          () -> new NbtTree(Unpooled.buffer()).tag("t", tooDeepList));
    }
  }

  @Nested
  @DisplayName("item data components")
  class DataComponents {

    @Test
    @DisplayName("should leave out values Adventure holds as no SNBT")
    void conversions() {
      DataComponentValue gson =
          GsonDataComponentValue.gsonDataComponentValue(JsonParser.parseString("7"));
      DataComponentValue unknown = new DataComponentValue() {};

      assertEquals(
          j(
              "{'text':'i','hover_event':{'action':'show_item','id':'minecraft:stone','count':1,"
                  + "'components':{'minecraft:c':1}}}"),
          json(
              Component.text("i")
                  .hoverEvent(
                      HoverEvent.showItem(
                          HoverEvent.ShowItem.showItem(
                              Key.key("stone"),
                              1,
                              Map.of(
                                  Key.key("a"),
                                  gson,
                                  Key.key("b"),
                                  unknown,
                                  Key.key("c"),
                                  BinaryTagHolder.binaryTagHolder("1"))))),
              V1_21_5));
    }
  }

  @Nested
  @DisplayName("legacy action bar")
  class LegacyActionBar {

    @Test
    @DisplayName("should strip the styles of translation arguments, keeping other arguments")
    void arguments() {
      Component bar =
          Component.translatable(
              "k",
              java.util.List.of(
                  net.kyori.adventure.text.TranslationArgument.component(
                      Component.text("a", NamedTextColor.RED)
                          .append(Component.text("b", NamedTextColor.BLUE))),
                  net.kyori.adventure.text.TranslationArgument.numeric(5)));

      assertEquals(
          j("{'extra':[{'translate':'k','with':[{'extra':['b'],'text':'a'},'5']}],'text':''}"),
          json(ComponentCodec.legacyActionBar(bar), V1_8));
    }

    @Test
    @DisplayName("should write the codes of each segment, not those of the segments before it")
    void codesInTurn() {
      Component bar =
          Component.text()
              .append(Component.text("a", NamedTextColor.RED))
              .append(Component.text("b", NamedTextColor.BLUE))
              .append(Component.text("c", NamedTextColor.RED))
              .build();

      assertEquals(
          j("{'extra':['\u00a7r\u00a7c','a','\u00a7r\u00a79','b','\u00a7r\u00a7c','c'],'text':''}"),
          json(ComponentCodec.legacyActionBar(bar), V1_8));
    }
  }

  @Nested
  @DisplayName("legacy tooltip names")
  class LegacyNames {

    @Test
    @DisplayName("should show a name as the plain text of its component before 1.13")
    void plainNames() {
      Component name =
          Component.translatable("k", "Fall")
              .append(Component.translatable("key.only"))
              .append(Component.keybind("key.jump"))
              .append(Component.selector("@p"))
              .append(Component.score().name("*").objective("o").value("3").build())
              .append(Component.score("*", "o"))
              .append(Component.storageNBT("p", Key.key("s")));

      assertEquals(
          j(
              "{'hoverEvent':{'action':'show_entity','value':{'text':'{type:\\'minecraft:pig\\',"
                  + "id:\\'6a085b2c-19fb-4986-b453-231aa942bbec\\',name:\\'Fallkey.onlykey.jump@p3\\'}'}},"
                  + "'text':'h'}"),
          json(
              Component.text("h").hoverEvent(HoverEvent.showEntity(Key.key("pig"), ID, name)),
              V1_12));
    }
  }

  @Test
  @DisplayName("should write plain text as a string tag from 1.20.3")
  void stringTagRoot() {
    assertEquals(
        StringBinaryTag.stringBinaryTag("x"),
        NbtReader.readNameless(
            Unpooled.wrappedBuffer(ComponentCodec.encode(Component.text("x"), V1_21_4))));
  }
}
