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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_12_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_13;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_15_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_18_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_5;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_6;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_26_3;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
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
import dev.warp.protocol.fuzz.Rejections;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("CommandTreeCodec")
class CommandTreeCodecTest {

  // ---------------------------------------------------------------------------
  // Vanilla trees
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("on the trees vanilla servers send operators")
  class VanillaTrees {

    static Stream<ProtocolVersion> captured() {
      return CommandFixtures.capturedVersions().stream();
    }

    @Test
    @DisplayName("should have a captured tree of every protocol with a commands packet")
    void captureEveryProtocol() {
      List<Integer> missing =
          CommandFixtures.commandProtocols()
              .map(ProtocolVersion::protocol)
              .filter(protocol -> CommandFixtures.capturedOf(protocol) == null)
              .toList();

      assertEquals(List.of(), missing, "protocols without a capture: run npm run command-trees");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("captured")
    @DisplayName("should decode the tree and encode it back to the same bytes")
    void roundTrip(ProtocolVersion version) {
      byte[] captured = CommandFixtures.capture(version);

      CommandTree tree = decoded(captured, version);

      assertArrayEquals(captured, encode(tree, version));
      // An operator's tree: some 670 nodes in 1.13, 1500 today.
      assertTrue(tree.size() > 600, () -> version + ": " + tree);
      assertEquals(tree, decoded(encode(tree, version), version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("captured")
    @DisplayName("should decode every node as Mojang's own report of the tree describes it")
    void matchMojangReport(ProtocolVersion version) {
      CommandTree tree = decoded(CommandFixtures.capture(version), version);
      JsonObject report = CommandFixtures.report(version);

      ReportCheck check = new ReportCheck(tree, report, version);
      check.run();

      // Every node is a child of another (vanilla sends no unreachable node), and redirects are
      // checked against the report too.
      assertEquals(tree.size(), check.checked, version::name);
      assertTrue(check.redirects > 10, () -> version + ": " + check.redirects + " redirects");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("captured")
    @DisplayName("should find restricted commands from 1.21.6 only, where clients read the flag")
    void restrictedFrom1216(ProtocolVersion version) {
      CommandTree tree = decoded(CommandFixtures.capture(version), version);

      long restricted = tree.nodes().stream().filter(CommandNode::restricted).count();

      assertEquals(version.isAtLeast(MINECRAFT_1_21_6), restricted > 0, () -> version.name());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("captured")
    @DisplayName("should leave the payload's indices where they were")
    void keepIndices(ProtocolVersion version) {
      ByteBuf payload = Unpooled.wrappedBuffer(CommandFixtures.capture(version));
      int reader = payload.readerIndex();
      int writer = payload.writerIndex();

      CommandTreeCodec.decode(payload, version);

      assertEquals(reader, payload.readerIndex());
      assertEquals(writer, payload.writerIndex());
    }
  }

  /**
   * Walks a decoded tree along Mojang's {@code commands.json} report of it: the server sends an
   * operator every command its report lists, but those of an integrated server ({@code /publish}),
   * so every node the root reaches must be in the report, alike.
   */
  private static final class ReportCheck {
    private final CommandTree tree;
    private final JsonObject report;
    private final ProtocolVersion version;
    private final boolean[] seen;
    private int checked;
    private int redirects;

    ReportCheck(CommandTree tree, JsonObject report, ProtocolVersion version) {
      this.tree = tree;
      this.report = report;
      this.version = version;
      this.seen = new boolean[tree.size()];
    }

    void run() {
      walk(tree.rootIndex(), report, "");
    }

    private void walk(int index, JsonObject expected, String path) {
      if (seen[index]) {
        return;
      }
      seen[index] = true;
      CommandNode node = tree.node(index);
      checked++;
      String type = expected.get("type").getAsString();
      switch (node) {
        case Root _ -> assertEquals("root", type, path);
        case Literal _ -> assertEquals("literal", type, path);
        case Argument argument -> {
          assertEquals("argument", type, path);
          assertEquals(expected.get("parser").getAsString(), argument.parser().identifier(), path);
          assertEquals(properties(expected.get("properties")), properties(argument.parser()), path);
        }
      }
      assertEquals(expected.has("executable"), node.executable(), () -> path + " executable");
      // The report leaves out a redirect to the root, whose path is empty. And the vanilla server
      // drops the redirect of a node whose target it has not converted yet when it builds a
      // player's tree: {@code execute if blocks ... all} has none, {@code masked} has its own.
      if (node.redirect().isPresent()) {
        int target =
            expected.has("redirect")
                ? resolve(expected.getAsJsonArray("redirect"))
                : tree.rootIndex();
        assertEquals(target, node.redirect().getAsInt(), () -> path + " redirect");
        redirects++;
      }
      JsonObject children = expected.has("children") ? expected.getAsJsonObject("children") : null;
      for (int i = 0; i < node.children().size(); i++) {
        int child = node.children().get(i);
        String name = String.valueOf(tree.node(child).name());
        JsonElement next = children == null ? null : children.get(name);
        assertNotNull(
            next, () -> version.name() + ": " + path + " " + name + " is not in the report");
        walk(child, next.getAsJsonObject(), path + " " + name);
      }
    }

    private int resolve(JsonArray path) {
      int index = tree.rootIndex();
      for (JsonElement name : path) {
        OptionalInt child = tree.child(index, name.getAsString());
        assertTrue(child.isPresent(), () -> "redirect to " + path);
        index = child.getAsInt();
      }
      return index;
    }

    /** Mojang's JSON form of a parser's properties. */
    private Map<String, Object> properties(ArgumentParser parser) {
      return switch (parser) {
        case FloatParser p -> range(p.min(), p.max());
        case DoubleParser p -> range(p.min(), p.max());
        case IntegerParser p -> range(p.min(), p.max());
        case LongParser p -> range(p.min(), p.max());
        case StringParser p ->
            Map.of(
                "type",
                switch (p.mode()) {
                  case SINGLE_WORD -> "word";
                  case QUOTABLE_PHRASE -> "phrase";
                  case GREEDY_PHRASE -> "greedy";
                });
        case EntityParser p ->
            Map.of(
                "amount", p.single() ? "single" : "multiple",
                "type", p.playersOnly() ? "players" : "entities");
        case ScoreHolderParser p -> Map.of("amount", p.multiple() ? "multiple" : "single");
        case TimeParser p ->
            version.isAtLeast(ArgumentTypes.TIME_MINIMUM_SINCE)
                ? Map.of("min", (double) p.minimum())
                : Map.of();
        case ResourceParser p -> Map.of("registry", p.registry());
        case PlainParser _, ForgeEnumParser _, WrappedParser _ -> Map.of();
      };
    }

    private static Map<String, Object> properties(@Nullable JsonElement json) {
      if (json == null) {
        return Map.of();
      }
      java.util.Map<String, Object> properties = new java.util.HashMap<>();
      for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
        var value = entry.getValue().getAsJsonPrimitive();
        properties.put(
            entry.getKey(), value.isNumber() ? value.getAsDouble() : value.getAsString());
      }
      return properties;
    }

    private static Map<String, Object> range(@Nullable Number min, @Nullable Number max) {
      java.util.Map<String, Object> properties = new java.util.HashMap<>();
      if (min != null) {
        properties.put("min", asJson(min));
      }
      if (max != null) {
        properties.put("max", asJson(max));
      }
      return properties;
    }

    /** A number as the report's JSON reads back: a float by its shortest decimal form. */
    private static double asJson(Number number) {
      return number instanceof Float ? Double.parseDouble(number.toString()) : number.doubleValue();
    }
  }

  // ---------------------------------------------------------------------------
  // Parsers
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("each parser")
  class Parsers {

    static Stream<Arguments> parsers() {
      return Stream.of(
          Arguments.of(ArgumentParser.of("brigadier:bool"), ""),
          Arguments.of(new FloatParser(null, null), "00"),
          Arguments.of(new FloatParser(1.5f, null), "01 3fc00000"),
          Arguments.of(new FloatParser(null, -2f), "02 c0000000"),
          Arguments.of(new DoubleParser(0.0, 1.0), "03 0000000000000000 3ff0000000000000"),
          Arguments.of(new IntegerParser(0, null), "01 00000000"),
          Arguments.of(new IntegerParser(-1, 255), "03 ffffffff 000000ff"),
          Arguments.of(new LongParser(null, Long.MAX_VALUE), "02 7fffffffffffffff"),
          Arguments.of(new LongParser(-5L, null), "01 fffffffffffffffb"),
          Arguments.of(ArgumentParser.word(), "00"),
          Arguments.of(new StringParser(StringMode.QUOTABLE_PHRASE), "01"),
          Arguments.of(ArgumentParser.greedyString(), "02"),
          Arguments.of(new EntityParser(false, false), "00"),
          Arguments.of(new EntityParser(true, false), "01"),
          Arguments.of(new EntityParser(false, true), "02"),
          Arguments.of(new EntityParser(true, true), "03"),
          Arguments.of(new ScoreHolderParser(false), "00"),
          Arguments.of(new ScoreHolderParser(true), "01"),
          Arguments.of(
              new ResourceParser(ResourceKind.RESOURCE, "minecraft:entity_type"),
              "15 6d696e6563726166743a656e746974795f74797065"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parsers")
    @DisplayName("should write its properties and read them back, by identifier and by id")
    void roundTrip(ArgumentParser parser, String properties) {
      for (ProtocolVersion version : List.of(MINECRAFT_1_18_2, MINECRAFT_1_21_4)) {
        CommandTree tree = singleArgument(parser);

        byte[] wire = encode(tree, version);

        assertEquals(tree, decoded(wire, version), version::name);
        assertWire(argumentBytes(parser, properties, version), wire, version.name());
      }
    }

    @Test
    @DisplayName("should write the minimum of minecraft:time from 1.19.4 only")
    void timeMinimum() {
      CommandTree tree = singleArgument(new TimeParser(20));

      CommandTree before = decoded(encode(tree, MINECRAFT_1_19_3), MINECRAFT_1_19_3);
      CommandTree since = decoded(encode(tree, MINECRAFT_1_19_4), MINECRAFT_1_19_4);

      assertEquals(singleArgument(new TimeParser(0)), before);
      assertEquals(tree, since);
      assertTrue(hex(encode(tree, MINECRAFT_1_19_4)).endsWith("0000001400"));
    }

    @Test
    @DisplayName("should read and write every resource kind with its registry")
    void resourceKinds() {
      for (ResourceKind kind : ResourceKind.values()) {
        CommandTree tree = singleArgument(new ResourceParser(kind, "minecraft:item"));

        assertEquals(tree, decoded(encode(tree, MINECRAFT_26_3), MINECRAFT_26_3), kind::name);
      }
    }

    @Test
    @DisplayName("should read Forge's enum and mod id types by identifier before 1.19")
    void forgeTypes() {
      CommandTree tree =
          tree(
              b -> {
                b.argument(b.root(), "mode", new ForgeEnumParser("net.example.Mode"));
                b.argument(b.root(), "mod", ArgumentParser.of("forge:modid"));
              });

      byte[] wire = encode(tree, MINECRAFT_1_16_4);

      assertEquals(tree, decoded(wire, MINECRAFT_1_16_4));
      assertThrows(IllegalArgumentException.class, () -> encode(tree, MINECRAFT_1_19));
    }

    @Test
    @DisplayName("should refuse to write a vanilla type the version's clients do not know")
    void refuseUnknownTypes() {
      assertThrows(
          IllegalArgumentException.class,
          () -> encode(singleArgument(ArgumentParser.of("minecraft:uuid")), MINECRAFT_1_15_2));
      assertThrows(
          IllegalArgumentException.class,
          () -> encode(singleArgument(ArgumentParser.of("minecraft:hex_color")), MINECRAFT_1_21_5));
      assertThrows(
          IllegalArgumentException.class,
          () -> encode(singleArgument(new LongParser(null, null)), MINECRAFT_1_13));
    }
  }

  // ---------------------------------------------------------------------------
  // CrossStitch
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("on CrossStitch-wrapped argument types")
  class CrossStitch {

    @Test
    @DisplayName("should read the wrapped type by registry id, and write it unwrapped, from 1.19")
    void unwrapById() {
      byte[] wire =
          packet(
              b -> {
                rootNode(b, 1);
                argumentHeader(b, "thing");
                VarInt.write(b, WrappedParser.REGISTRY_ID);
                VarInt.write(b, 70); // the backend's own id
                VarInt.write(b, 3);
                b.writeBytes(new byte[] {1, 2, 3});
              });

      CommandTree tree = decoded(wire, MINECRAFT_26_3);

      Argument argument = (Argument) tree.node(1);
      assertEquals(new WrappedParser(null, 70, new byte[] {1, 2, 3}), argument.parser());
      assertEquals("#70", argument.parser().identifier());
      assertWire(
          "02 00 01 01 02 00 05 7468696e67 46 010203 00",
          encode(tree, MINECRAFT_26_3),
          "unwrapped");
    }

    @Test
    @DisplayName("should read the wrapped type by identifier, and write it unwrapped, before 1.19")
    void unwrapByIdentifier() {
      byte[] wire =
          packet(
              b -> {
                rootNode(b, 1);
                argumentHeader(b, "thing");
                McString.write(b, WrappedParser.IDENTIFIER);
                McString.write(b, "example:thing");
                VarInt.write(b, 1);
                b.writeByte(9);
              });

      CommandTree tree = decoded(wire, MINECRAFT_1_16_4);

      Argument argument = (Argument) tree.node(1);
      assertEquals(new WrappedParser("example:thing", -1, new byte[] {9}), argument.parser());
      assertWire(
          "02 00 01 01 02 00 05 7468696e67 0d 6578616d706c653a7468696e67 09 00",
          encode(tree, MINECRAFT_1_16_4),
          "unwrapped");
      assertThrows(IllegalArgumentException.class, () -> encode(tree, MINECRAFT_1_19));
      CommandTree byId = singleArgument(new WrappedParser(null, 4, new byte[0]));
      assertThrows(IllegalArgumentException.class, () -> encode(byId, MINECRAFT_1_18_2));
    }
  }

  // ---------------------------------------------------------------------------
  // Opaque
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("on packets Warp cannot represent")
  class OpaquePackets {

    static Stream<Arguments> opaque() {
      return Stream.of(
          Arguments.of(
              "a modded argument type id",
              MINECRAFT_26_3,
              (Consumer<ByteBuf>)
                  b -> {
                    argumentHeader(b, "x");
                    VarInt.write(b, 62);
                  },
              "argument type #62, none of the 62 vanilla argument types of 26.3"),
          Arguments.of(
              "a negative argument type id",
              MINECRAFT_1_19,
              (Consumer<ByteBuf>)
                  b -> {
                    argumentHeader(b, "x");
                    VarInt.write(b, -1);
                  },
              "argument type #-1"),
          Arguments.of(
              "a modded argument type identifier",
              MINECRAFT_1_18_2,
              (Consumer<ByteBuf>)
                  b -> {
                    argumentHeader(b, "x");
                    McString.write(b, "example:thing");
                  },
              "argument type example:thing, not a vanilla or Forge one"),
          Arguments.of(
              "a vanilla argument type the version does not have",
              MINECRAFT_1_15_2,
              (Consumer<ByteBuf>)
                  b -> {
                    argumentHeader(b, "x");
                    McString.write(b, "minecraft:uuid");
                  },
              "argument type minecraft:uuid, which 1.15.2 clients do not know"),
          Arguments.of(
              "the restricted flag before 1.21.6",
              MINECRAFT_1_21_5,
              (Consumer<ByteBuf>) b -> literal(b, 0x21, "x"),
              "unknown flags 0x20"),
          Arguments.of(
              "an unknown flag",
              MINECRAFT_26_3,
              (Consumer<ByteBuf>) b -> literal(b, 0x41, "x"),
              "unknown flags 0x40"),
          Arguments.of(
              "node type 3",
              MINECRAFT_26_3,
              (Consumer<ByteBuf>) b -> b.writeByte(0x03),
              "node type 3"),
          Arguments.of(
              "a literal with the suggestions flag",
              MINECRAFT_26_3,
              (Consumer<ByteBuf>) b -> literal(b, 0x11, "x"),
              "a literal with suggestions"),
          Arguments.of(
              "unknown entity flags",
              MINECRAFT_1_18_2,
              (Consumer<ByteBuf>)
                  b -> {
                    argumentHeader(b, "x");
                    McString.write(b, "minecraft:entity");
                    b.writeByte(0x04);
                  },
              "minecraft:entity flags 0x4"),
          Arguments.of(
              "unknown score holder flags",
              MINECRAFT_1_21_4,
              (Consumer<ByteBuf>)
                  b -> {
                    argumentHeader(b, "x");
                    VarInt.write(b, 30); // minecraft:score_holder
                    b.writeByte(0x02);
                  },
              "minecraft:score_holder flags 0x2"),
          Arguments.of(
              "unknown number flags",
              MINECRAFT_1_21_4,
              (Consumer<ByteBuf>)
                  b -> {
                    argumentHeader(b, "x");
                    VarInt.write(b, 3); // brigadier:integer
                    b.writeByte(0x05);
                  },
              "brigadier:integer flags 0x5"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("opaque")
    @DisplayName("should report the packet opaque, at the node holding it, for the log")
    void reportOpaque(
        String what, ProtocolVersion version, Consumer<ByteBuf> secondNode, String reason) {
      byte[] wire =
          packet(
              b -> {
                rootNode(b, 1);
                secondNode.accept(b);
                b.writeBytes(new byte[] {0x7f, 0x7f, 0x7f}); // never read
              });

      CommandTreeDecoding decoding = CommandTreeCodec.decode(Unpooled.wrappedBuffer(wire), version);

      Opaque opaque = assertInstanceOf(Opaque.class, decoding, what);
      assertEquals(1, opaque.node());
      assertEquals(version, opaque.version());
      assertTrue(opaque.reason().startsWith(reason), opaque::reason);
      assertTrue(opaque.message().contains(version.name()), opaque::message);
      assertTrue(opaque.message().contains("node 1 has " + opaque.reason()), opaque::message);
    }

    @Test
    @DisplayName("should report a root with flags opaque, whatever the flag")
    void rootFlags() {
      for (int flags : new int[] {0x04, 0x08, 0x10, 0x20}) {
        byte[] wire =
            packet(
                1,
                b -> {
                  b.writeByte(flags);
                  VarInt.write(b, 0); // no children
                  if ((flags & 0x08) != 0) {
                    VarInt.write(b, 0); // redirect
                  }
                });

        Opaque opaque = assertInstanceOf(Opaque.class, decode(wire, MINECRAFT_26_3));

        assertEquals("a root with flags 0x" + Integer.toHexString(flags), opaque.reason());
        assertEquals(0, opaque.node());
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Malformed
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("on malformed packets")
  class Malformed {

    static Stream<Arguments> malformed() {
      return Stream.of(
          Arguments.of("an empty payload", "", "Bad VarInt"),
          Arguments.of(
              "a node count larger than the packet", "05 00 00", "Invalid command node count"),
          Arguments.of("a negative node count", "ffffffff0f", "Invalid command node count"),
          Arguments.of(
              "a child out of range", "01 00 01 05 00", "Child 5 of node 0 is out of range"),
          Arguments.of("a negative child", "01 00 01 ffffffff0f 00", "Negative command node index"),
          Arguments.of("a redirect out of range", "02 00 01 01 09 00 07 01 78 00", "Redirect 7"),
          Arguments.of("a root index out of range", "01 00 00 03", "Root index 3"),
          Arguments.of(
              "a root that is not a root", "02 00 01 01 01 00 01 78 01", "Node 1, the root"),
          Arguments.of("a root as a child", "02 00 01 01 00 00 00", "has a root node"),
          Arguments.of(
              "children looping", "03 00 01 01 01 01 02 01 78 01 01 01 01 79 00", "Children loop"),
          Arguments.of(
              "redirects looping",
              "03 00 02 01 02 09 00 02 01 78 09 00 01 01 79 00",
              "Redirect loop"),
          Arguments.of(
              "a node redirecting to itself", "02 00 01 01 09 00 01 01 78 00", "Redirect loop"),
          Arguments.of("trailing bytes", "01 00 00 00 ff", "bytes after the root index"),
          Arguments.of("a truncated name", "02 00 01 01 01 00 05 78", "Not enough readable bytes"),
          Arguments.of(
              "an unknown string mode",
              "02 00 01 01 02 00 01 78 05 07 00",
              "Unknown brigadier:string mode"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    @DisplayName("should reject it with the decoder's error, nothing else")
    void reject(String what, String wire, String message) {
      byte[] bytes = HexFormat.of().parseHex(wire.replace(" ", ""));

      DecoderException thrown =
          assertThrows(DecoderException.class, () -> decode(bytes, MINECRAFT_1_21_4), what);

      Rejections.assertRejection(thrown, IndexOutOfBoundsException.class);
      String text = thrown.getMessage() + " " + thrown.getCause();
      assertTrue(text.contains(message), () -> what + ": " + text);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.command.CommandTreeCodecTest$VanillaTrees#captured")
    @DisplayName("should reject every truncation of a vanilla tree with the decoder's error")
    void rejectTruncations(ProtocolVersion version) {
      byte[] captured = CommandFixtures.capture(version);

      for (int length = 0; length < captured.length; length += 1 + length / 64) {
        ByteBuf truncated = Unpooled.wrappedBuffer(captured, 0, length);
        try {
          CommandTreeDecoding decoding = CommandTreeCodec.decode(truncated, version);
          throw new AssertionError(version + " truncated decoded: " + decoding);
        } catch (DecoderException rejected) {
          Rejections.assertRejection(rejected, IndexOutOfBoundsException.class);
        }
      }
    }

    @Test
    @DisplayName("should decode a chain far deeper than any stack, without recursing")
    void deepChain() {
      int depth = 200_000;
      byte[] wire =
          packet(
              depth + 1,
              b -> {
                rootNode(b, 1);
                for (int i = 1; i <= depth; i++) {
                  b.writeByte(0x01);
                  if (i < depth) {
                    VarInt.write(b, 1);
                    VarInt.write(b, i + 1);
                  } else {
                    VarInt.write(b, 0);
                  }
                  McString.write(b, "a");
                }
              });

      CommandTree tree = decoded(wire, MINECRAFT_1_21_4);

      assertEquals(depth + 1, tree.size());
      assertArrayEquals(wire, encode(tree, MINECRAFT_1_21_4));
      assertEquals(tree, tree.compact());
    }

    @Test
    @DisplayName("should refuse versions without a commands packet, both ways")
    void before113() {
      assertThrows(
          IllegalArgumentException.class, () -> decode(new byte[] {1, 0, 0, 0}, MINECRAFT_1_12_2));
      assertThrows(
          IllegalArgumentException.class, () -> encode(CommandTree.empty(), MINECRAFT_1_12_2));
    }
  }

  // ---------------------------------------------------------------------------
  // Flags
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("node flags")
  class Flags {

    @Test
    @DisplayName("should write the restricted flag from 1.21.6 only")
    void restricted() {
      CommandTree tree = tree(b -> b.restricted(b.literal(b.root(), "op")));

      assertWire("02 00 01 01 01 00 02 6f70 00", encode(tree, MINECRAFT_1_21_5), "1.21.5");
      assertWire("02 00 01 01 21 00 02 6f70 00", encode(tree, MINECRAFT_1_21_6), "1.21.6");
      assertEquals(tree, decoded(encode(tree, MINECRAFT_1_21_6), MINECRAFT_1_21_6));
    }

    @Test
    @DisplayName("should write executable, redirect and suggestions flags with their data")
    void executableRedirectSuggestions() {
      CommandTree tree =
          tree(
              b -> {
                int run = b.literal(b.root(), "run");
                b.redirect(run, b.root());
                int name = b.argument(b.root(), "name", ArgumentParser.word());
                b.executable(name);
                b.suggestions(name, Argument.ASK_SERVER);
              });

      byte[] wire = encode(tree, MINECRAFT_1_19);

      assertWire(
          "03 00 02 01 02 09 00 00 03 72756e 16 00 04 6e616d65 05 00"
              + " 14 6d696e6563726166743a61736b5f736572766572 00",
          wire,
          "1.19");
      assertEquals(tree, decoded(wire, MINECRAFT_1_19));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static CommandTreeDecoding decode(byte[] wire, ProtocolVersion version) {
    return CommandTreeCodec.decode(Unpooled.wrappedBuffer(wire), version);
  }

  private static CommandTree decoded(byte[] wire, ProtocolVersion version) {
    CommandTreeDecoding decoding = decode(wire, version);
    return assertInstanceOf(Decoded.class, decoding, decoding::toString).tree();
  }

  private static byte[] encode(CommandTree tree, ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    CommandTreeCodec.encode(tree, buf, version);
    return ByteBufUtil.getBytes(buf);
  }

  private static CommandTree tree(Consumer<CommandTree.Builder> nodes) {
    CommandTree.Builder builder = CommandTree.builder();
    nodes.accept(builder);
    return builder.build();
  }

  private static CommandTree singleArgument(ArgumentParser parser) {
    return tree(b -> b.argument(b.root(), "x", parser));
  }

  /** The expected bytes of {@link #singleArgument}, its parser named as the version names it. */
  private static String argumentBytes(
      ArgumentParser parser, String properties, ProtocolVersion version) {
    ByteBuf id = Unpooled.buffer();
    if (version.isAtLeast(MINECRAFT_1_19)) {
      VarInt.write(id, ArgumentTypes.registryId(parser.identifier(), version).orElseThrow());
    } else {
      McString.write(id, parser.identifier());
    }
    String parserBytes = HexFormat.of().formatHex(ByteBufUtil.getBytes(id));
    return "02 00 01 01 02 00 01 78 " + parserBytes + " " + properties + " 00";
  }

  /** A packet of two nodes: the bytes {@code nodes} writes after the count, then root index 0. */
  private static byte[] packet(Consumer<ByteBuf> nodes) {
    return packet(2, nodes);
  }

  private static byte[] packet(int count, Consumer<ByteBuf> nodes) {
    ByteBuf buf = Unpooled.buffer();
    VarInt.write(buf, count);
    nodes.accept(buf);
    VarInt.write(buf, 0);
    return ByteBufUtil.getBytes(buf);
  }

  /** Asserts that {@code actual} is the bytes {@code expected} spells, in hex, spaces aside. */
  private static void assertWire(String expected, byte[] actual, String what) {
    assertEquals(expected.replace(" ", ""), hex(actual), what);
  }

  private static void rootNode(ByteBuf buf, int child) {
    buf.writeByte(0x00);
    VarInt.write(buf, 1);
    VarInt.write(buf, child);
  }

  private static void literal(ByteBuf buf, int flags, String name) {
    buf.writeByte(flags);
    VarInt.write(buf, 0);
    McString.write(buf, name);
  }

  private static void argumentHeader(ByteBuf buf, String name) {
    buf.writeByte(0x02);
    VarInt.write(buf, 0);
    McString.write(buf, name);
  }

  /** Bytes as hex, a space between each node's fields as the expectations write them. */
  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
