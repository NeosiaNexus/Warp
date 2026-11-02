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
package dev.warp.protocol.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.command.ArgumentParser.DoubleParser;
import dev.warp.protocol.command.ArgumentParser.EntityParser;
import dev.warp.protocol.command.ArgumentParser.FloatParser;
import dev.warp.protocol.command.ArgumentParser.ForgeEnumParser;
import dev.warp.protocol.command.ArgumentParser.IntegerParser;
import dev.warp.protocol.command.ArgumentParser.LongParser;
import dev.warp.protocol.command.ArgumentParser.PlainParser;
import dev.warp.protocol.command.ArgumentParser.ResourceKind;
import dev.warp.protocol.command.ArgumentParser.ResourceParser;
import dev.warp.protocol.command.ArgumentParser.ScoreHolderParser;
import dev.warp.protocol.command.ArgumentParser.StringMode;
import dev.warp.protocol.command.ArgumentParser.StringParser;
import dev.warp.protocol.command.ArgumentParser.TimeParser;
import dev.warp.protocol.command.ArgumentParser.WrappedParser;
import dev.warp.protocol.command.CommandNode.Argument;
import dev.warp.protocol.command.CommandNode.Literal;
import dev.warp.protocol.command.CommandNode.Root;
import dev.warp.protocol.command.CommandTreeDecoding.Decoded;
import dev.warp.protocol.command.CommandTreeDecoding.Opaque;

import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Command tree model")
class CommandModelTest {

  // ---------------------------------------------------------------------------
  // Children
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Children")
  class ChildrenTests {

    @Test
    @DisplayName("should hold indices in order, copied in and out")
    void holdIndices() {
      int[] source = {3, 1, 2};
      Children children = Children.of(source);
      source[0] = 9;

      int[] out = children.toArray();
      out[1] = 9;

      assertEquals(3, children.size());
      assertFalse(children.isEmpty());
      assertEquals(3, children.get(0));
      assertArrayEquals(new int[] {3, 1, 2}, children.toArray());
      assertArrayEquals(new int[] {3, 1, 2}, children.stream().toArray());
      assertTrue(children.contains(2));
      assertFalse(children.contains(9));
      assertEquals("[3, 1, 2]", children.toString());
    }

    @Test
    @DisplayName("should append without changing the original")
    void append() {
      Children children = Children.of(1);

      Children longer = children.with(2);

      assertEquals(Children.of(1, 2), longer);
      assertEquals(Children.of(1), children);
      assertEquals(Children.of(1, 0), children.with(0));
      assertThrows(IllegalArgumentException.class, () -> children.with(-1));
    }

    @Test
    @DisplayName("should share the empty list and refuse negative indices")
    void emptyAndNegative() {
      assertSame(Children.of(), Children.of(new int[0]));
      assertSame(Children.of(), Children.wrap(new int[0]));
      assertTrue(Children.of().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> Children.of(0, -1));
    }

    @Test
    @DisplayName("should compare by indices and order")
    void equality() {
      assertEquals(Children.of(1, 2), Children.of(1, 2));
      assertEquals(Children.of(1, 2).hashCode(), Children.of(1, 2).hashCode());
      assertEquals(java.util.Arrays.hashCode(new int[] {1, 2}), Children.of(1, 2).hashCode());
      assertNotEquals(Children.of(1, 2), Children.of(2, 1));
      assertNotEquals(Children.of(1), "[1]");
    }
  }

  // ---------------------------------------------------------------------------
  // Nodes
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("CommandNode")
  class Nodes {

    @Test
    @DisplayName("should give the root no name, redirect or flag")
    void root() {
      Root root = new Root(Children.of(1));

      assertNull(root.name());
      assertEquals(OptionalInt.empty(), root.redirect());
      assertFalse(root.executable());
      assertFalse(root.restricted());
      assertEquals(new Root(Children.of(2)), root.withChildren(Children.of(2)));
      assertSame(root, root.withRedirect(OptionalInt.empty()));
      assertThrows(IllegalArgumentException.class, () -> root.withRedirect(OptionalInt.of(0)));
      assertSame(root, root.withExecutable(false));
      assertSame(root, root.withRestricted(false));
      assertThrows(IllegalArgumentException.class, () -> root.withExecutable(true));
      assertThrows(IllegalArgumentException.class, () -> root.withRestricted(true));
    }

    @Test
    @DisplayName("should copy a literal with each component changed")
    void literal() {
      Literal literal = CommandNode.literal("tp");

      assertEquals("tp", literal.name());
      assertEquals(Children.of(4), literal.withChildren(Children.of(4)).children());
      assertEquals(OptionalInt.of(2), literal.withRedirect(OptionalInt.of(2)).redirect());
      assertTrue(literal.withExecutable(true).executable());
      assertTrue(literal.withRestricted(true).restricted());
      assertFalse(literal.executable());
      assertFalse(literal.restricted());
      assertThrows(IllegalArgumentException.class, () -> literal.withRedirect(OptionalInt.of(-1)));
    }

    @Test
    @DisplayName("should copy an argument with each component changed")
    void argument() {
      Argument argument = CommandNode.argument("player", ArgumentParser.word());

      assertEquals("player", argument.name());
      assertNull(argument.suggestions());
      assertEquals(
          Argument.ASK_SERVER, argument.withSuggestions(Argument.ASK_SERVER).suggestions());
      assertEquals(Children.of(1), argument.withChildren(Children.of(1)).children());
      assertEquals(OptionalInt.of(3), argument.withRedirect(OptionalInt.of(3)).redirect());
      assertTrue(argument.withExecutable(true).executable());
      assertTrue(argument.withRestricted(true).restricted());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new Argument(
                  "x",
                  ArgumentParser.word(),
                  null,
                  Children.of(),
                  OptionalInt.of(-2),
                  false,
                  false));
    }
  }

  // ---------------------------------------------------------------------------
  // Parsers
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ArgumentParser")
  class Parsers {

    @Test
    @DisplayName("should name each parser by its argument type's identifier")
    void identifiers() {
      assertEquals("brigadier:float", new FloatParser(null, null).identifier());
      assertEquals("brigadier:double", new DoubleParser(null, null).identifier());
      assertEquals("brigadier:integer", new IntegerParser(null, null).identifier());
      assertEquals("brigadier:long", new LongParser(null, null).identifier());
      assertEquals("brigadier:string", ArgumentParser.word().identifier());
      assertEquals("minecraft:entity", new EntityParser(true, true).identifier());
      assertEquals("minecraft:score_holder", new ScoreHolderParser(true).identifier());
      assertEquals("minecraft:time", new TimeParser(0).identifier());
      assertEquals(
          "minecraft:resource_or_tag_key",
          new ResourceParser(ResourceKind.RESOURCE_OR_TAG_KEY, "minecraft:item").identifier());
      assertEquals("forge:enum", new ForgeEnumParser("a.B").identifier());
      assertEquals("minecraft:vec3", ArgumentParser.of("minecraft:vec3").identifier());
    }

    @Test
    @DisplayName("should refuse a plain parser for a type with properties, or no type")
    void plainOnlyWithoutProperties() {
      assertThrows(IllegalArgumentException.class, () -> new PlainParser("brigadier:integer"));
      assertThrows(IllegalArgumentException.class, () -> new PlainParser("minecraft:time"));
      assertThrows(IllegalArgumentException.class, () -> new PlainParser("forge:enum"));
      assertThrows(
          IllegalArgumentException.class, () -> new PlainParser("crossstitch:mod_argument"));
      assertThrows(IllegalArgumentException.class, () -> new PlainParser(""));
      assertEquals("forge:modid", new PlainParser("forge:modid").identifier());
    }

    @Test
    @DisplayName("should share the parsers decoding gives most often")
    void shared() {
      assertSame(ArgumentParser.word(), StringParser.of(StringMode.SINGLE_WORD));
      assertSame(ArgumentParser.greedyString(), StringParser.of(StringMode.GREEDY_PHRASE));
      assertEquals(
          new StringParser(StringMode.QUOTABLE_PHRASE),
          StringParser.of(StringMode.QUOTABLE_PHRASE));
      assertEquals(new EntityParser(true, false), EntityParser.of(1));
      assertEquals(new EntityParser(false, true), EntityParser.of(2));
      assertSame(EntityParser.of(3), EntityParser.of(3));
      assertEquals(new ScoreHolderParser(true), ScoreHolderParser.of(true));
      assertEquals(new ScoreHolderParser(false), ScoreHolderParser.of(false));
    }

    @Test
    @DisplayName("should keep a wrapped type's properties to itself, and compare them by content")
    void wrapped() {
      byte[] properties = {1, 2};
      WrappedParser byId = new WrappedParser(null, 70, properties);
      properties[0] = 9;

      byte[] out = byId.properties();
      out[1] = 9;

      assertArrayEquals(new byte[] {1, 2}, byId.properties());
      assertEquals("#70", byId.identifier());
      assertEquals(new WrappedParser(null, 70, new byte[] {1, 2}), byId);
      assertEquals(new WrappedParser(null, 70, new byte[] {1, 2}).hashCode(), byId.hashCode());
      assertNotEquals(new WrappedParser(null, 71, new byte[] {1, 2}), byId);
      assertNotEquals(new WrappedParser(null, 70, new byte[] {1}), byId);
      assertNotEquals(new WrappedParser("a:b", -1, new byte[] {1, 2}), byId);
      assertEquals("WrappedParser[#70, properties=0102]", byId.toString());
      assertNotEquals(byId, ArgumentParser.word());
      assertNotEquals(byId.hashCode(), new WrappedParser(null, 70, new byte[] {1, 3}).hashCode());
      assertNotEquals(
          new WrappedParser("a:b", -1, new byte[0]), new WrappedParser("c:d", -1, new byte[0]));
      assertEquals("#0", new WrappedParser(null, 0, new byte[0]).identifier());
      assertEquals("a:b", new WrappedParser("a:b", -1, new byte[0]).identifier());
      assertThrows(IllegalArgumentException.class, () -> new WrappedParser("a:b", 3, new byte[0]));
      assertThrows(IllegalArgumentException.class, () -> new WrappedParser(null, -1, new byte[0]));
    }
  }

  // ---------------------------------------------------------------------------
  // Decoding results
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("CommandTreeDecoding")
  class Results {

    @Test
    @DisplayName("should explain an opaque tree in one line, for the log")
    void opaqueMessage() {
      Opaque opaque = new Opaque(ProtocolVersion.MINECRAFT_26_3, 12, "argument type #62");

      assertEquals(
          "Commands sent for 26.3 forwarded untouched, without proxy commands: node 12 has"
              + " argument type #62 (a modded server's own argument type is the usual cause;"
              + " installing CrossStitch on the server lets Warp read past it)",
          opaque.message());
      assertEquals(CommandTree.empty(), new Decoded(CommandTree.empty()).tree());
    }
  }
}
