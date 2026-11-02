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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.command.ArgumentParser.WrappedParser;
import dev.warp.protocol.command.CommandNode.Argument;
import dev.warp.protocol.command.CommandTreeDecoding.Decoded;
import dev.warp.protocol.command.CommandTreeDecoding.Opaque;
import dev.warp.protocol.fuzz.FuzzSeeds;
import dev.warp.protocol.fuzz.Rejections;

import java.io.IOException;
import java.util.function.Consumer;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fuzzes {@link CommandTreeCodec#decode}, the one parser in the proxy that reads a whole graph a
 * backend sends, at every version from 1.13: whatever the bytes, it decodes them, reports them
 * opaque, or rejects them with a {@link DecoderException}, never anything else, and never moves the
 * payload's indices. A decoded tree always encodes again, at the same version, to bytes that decode
 * to the same tree, and survives every editor.
 */
@DisplayName("CommandTreeCodec fuzzing")
class CommandTreeCodecFuzzTest {

  /** The highest protocol an input can choose: two bytes, the high one first in a seed. */
  private static final int MAX_PROTOCOL = 0xFFFF;

  @FuzzTest
  @DisplayName("should decode, report opaque or reject any payload, and re-encode what it decodes")
  void decode(FuzzedDataProvider data) {
    ProtocolVersion version = versionOf(data.consumeInt(0, MAX_PROTOCOL));
    byte[] bytes = data.consumeRemainingAsBytes();
    ByteBuf payload = Unpooled.wrappedBuffer(bytes);

    CommandTreeDecoding decoding;
    try {
      decoding = CommandTreeCodec.decode(payload, version);
    } catch (DecoderException rejected) {
      Rejections.assertRejection(rejected, IndexOutOfBoundsException.class);
      return;
    } finally {
      assertEquals(0, payload.readerIndex(), "decoding moved the payload's reader index");
    }

    switch (decoding) {
      case Opaque opaque -> assertEquals(version, opaque.version());
      case Decoded(CommandTree tree) -> checkDecoded(tree, version);
    }
  }

  private static void checkDecoded(CommandTree tree, ProtocolVersion version) {
    byte[] encoded = encode(tree, version);
    if (tree.nodes().stream()
        .noneMatch(node -> node instanceof Argument a && a.parser() instanceof WrappedParser)) {
      // Unwrapping CrossStitch changes what the bytes say; anything else must survive.
      CommandTreeDecoding again = CommandTreeCodec.decode(Unpooled.wrappedBuffer(encoded), version);
      assertEquals(tree, assertInstanceOf(Decoded.class, again).tree());
    }
    CommandTree compacted = tree.compact();
    encode(compacted, version);
    encode(tree.filter(node -> node.executable()), version);
    encode(tree.withoutTopLevel(node -> node.redirect().isPresent()), version);
    encode(tree.merge(compacted, NameConflict.PROXY_WINS), version);
    encode(tree.merge(compacted, NameConflict.BACKEND_WINS), version);
  }

  @Test
  @DisplayName("should keep its checked-in seeds up to date")
  void seeds() throws IOException {
    new FuzzSeeds(CommandTreeCodecFuzzTest.class, "decode")
        .add("vanilla-1.13", CommandFixtures.capture(ProtocolVersion.MINECRAFT_1_13), protocol(393))
        .add(
            "vanilla-1.21.6",
            CommandFixtures.capture(ProtocolVersion.MINECRAFT_1_21_6),
            protocol(771))
        .add(
            "redirect-to-root",
            payload(
                3,
                b -> {
                  node(b, 0x00, 1);
                  node(b, 0x01, 2);
                  McString.write(b, "execute");
                  node(b, 0x08 | 0x01);
                  VarInt.write(b, 0);
                  McString.write(b, "run");
                  VarInt.write(b, 0);
                }),
            protocol(777))
        .add(
            "crossstitch-by-id",
            payload(
                2,
                b -> {
                  node(b, 0x00, 1);
                  node(b, 0x02);
                  McString.write(b, "x");
                  VarInt.write(b, WrappedParser.REGISTRY_ID);
                  VarInt.write(b, 70);
                  VarInt.write(b, 1);
                  b.writeByte(0);
                  VarInt.write(b, 0);
                }),
            protocol(767))
        .add(
            "modded-identifier",
            payload(
                2,
                b -> {
                  node(b, 0x00, 1);
                  node(b, 0x02);
                  McString.write(b, "x");
                  McString.write(b, "example:thing");
                  VarInt.write(b, 0);
                }),
            protocol(758))
        .add(
            "children-loop",
            payload(
                3,
                b -> {
                  node(b, 0x00, 1);
                  node(b, 0x01, 2);
                  McString.write(b, "a");
                  node(b, 0x01, 1);
                  McString.write(b, "b");
                  VarInt.write(b, 0);
                }),
            protocol(769))
        .verify();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** The version an input chooses: the newest at or below the protocol, 1.13 at the least. */
  private static ProtocolVersion versionOf(int protocol) {
    ProtocolVersion chosen = CommandTreeCodec.FIRST;
    for (ProtocolVersion version : ProtocolVersion.values()) {
      if (version.protocol() <= protocol && version.isAtLeast(chosen)) {
        chosen = version;
      }
    }
    return chosen;
  }

  /** The two choice bytes of a protocol, high byte first. */
  private static int[] protocol(int protocol) {
    return new int[] {protocol >> 8, protocol & 0xFF};
  }

  private static byte[] encode(CommandTree tree, ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    CommandTreeCodec.encode(tree, buf, version);
    return ByteBufUtil.getBytes(buf);
  }

  private static byte[] payload(int count, Consumer<ByteBuf> nodes) {
    ByteBuf buf = Unpooled.buffer();
    VarInt.write(buf, count);
    nodes.accept(buf);
    return ByteBufUtil.getBytes(buf);
  }

  private static void node(ByteBuf buf, int flags, int... children) {
    buf.writeByte(flags);
    VarInt.write(buf, children.length);
    for (int child : children) {
      VarInt.write(buf, child);
    }
  }
}
