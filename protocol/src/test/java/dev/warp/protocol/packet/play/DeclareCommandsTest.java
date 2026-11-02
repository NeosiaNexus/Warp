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
package dev.warp.protocol.packet.play;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.command.ArgumentParser;
import dev.warp.protocol.command.CommandTree;
import dev.warp.protocol.command.CommandTreeDecoding;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;

import java.util.HexFormat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeclareCommands")
class DeclareCommandsTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

  /** A root with /friend, then an argument parsed as one word. */
  private static final CommandTree TREE = friendTree();

  private static CommandTree friendTree() {
    CommandTree.Builder builder = CommandTree.builder();
    int friend = builder.literal(builder.root(), "friend");
    builder.executable(builder.argument(friend, "player", ArgumentParser.word()));
    return builder.build();
  }

  @Test
  @DisplayName("should copy the payload on decoding, without parsing it, and write it back")
  void rawPayload() {
    byte[] garbage = HexFormat.of().parseHex("ff00ff00");
    ByteBuf in = Unpooled.wrappedBuffer(garbage);

    DeclareCommands packet = DeclareCommands.CODEC.decode(in, VERSION);
    ByteBuf out = Unpooled.buffer();
    DeclareCommands.CODEC.encode(packet, out, VERSION);

    assertFalse(in.isReadable(), "the whole payload is read");
    assertArrayEquals(garbage, packet.payload());
    assertArrayEquals(garbage, ByteBufUtil.getBytes(out));
  }

  @Test
  @DisplayName("should encode a tree and decode it back on demand")
  void encodeAndDecodeTree() {
    DeclareCommands packet = DeclareCommands.of(TREE, VERSION);

    CommandTreeDecoding decoding = packet.tree(VERSION);

    assertEquals(TREE, assertInstanceOf(CommandTreeDecoding.Decoded.class, decoding).tree());
  }

  @Test
  @DisplayName("should refuse to encode an argument type the version does not know")
  void refuseUnknownType() {
    CommandTree.Builder builder = CommandTree.builder();
    builder.argument(builder.root(), "id", ArgumentParser.of("minecraft:uuid"));
    CommandTree uuid = builder.build();

    assertThrows(
        IllegalArgumentException.class,
        () -> DeclareCommands.of(uuid, ProtocolVersion.MINECRAFT_1_15_2));
  }

  @Test
  @DisplayName("should be registered decoded: its payload is copied, its tree parsed on demand")
  void decoded() {
    PacketRegistry registry = StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);

    PacketRegistry.Encoding encoding = registry.encoding(VERSION, DeclareCommands.class);

    assertEquals(0x11, encoding.packetId());
    assertSame(DeclareCommands.CODEC, encoding.codec());
    assertSame(DeclareCommands.CODEC, registry.lookup(VERSION, encoding.packetId()));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.encoding(ProtocolVersion.MINECRAFT_1_12_2, DeclareCommands.class));
  }
}
