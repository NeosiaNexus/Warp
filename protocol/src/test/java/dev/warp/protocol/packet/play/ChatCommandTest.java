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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;

import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("ChatCommand")
class ChatCommandTest {

  private static final PacketRegistry SERVERBOUND =
      StateRegistry.get(ProtocolState.PLAY, PacketDirection.SERVERBOUND);

  // ---------------------------------------------------------------------------
  // Keyed commands (1.19 to 1.19.2)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("keyed commands (1.19 to 1.19.2)")
  class Keyed {

    @Test
    @DisplayName("should read an unsigned 1.19 command and write its signature data back")
    void unsignedCommand119() {
      ByteBuf wire = Unpooled.buffer();
      McString.write(wire, "server survival");
      wire.writeLong(1_700_000_000_000L); // timestamp
      wire.writeLong(0L); // salt
      VarInt.write(wire, 0); // argument signatures
      wire.writeBoolean(false); // signed preview

      assertRoundtrip(wire, ProtocolVersion.MINECRAFT_1_19, "server survival");
    }

    @Test
    @DisplayName("should read a signed 1.19 command and write its signature data back")
    void signedCommand119() {
      ByteBuf wire = Unpooled.buffer();
      McString.write(wire, "msg Steve hello");
      wire.writeLong(1_700_000_000_000L); // timestamp
      wire.writeLong(0x5A17_5A17_5A17_5A17L); // salt
      VarInt.write(wire, 1); // argument signatures
      McString.write(wire, "message");
      writeSignature(wire, 0x11);
      wire.writeBoolean(true); // signed preview

      assertRoundtrip(wire, ProtocolVersion.MINECRAFT_1_19, "msg Steve hello");
    }

    @Test
    @DisplayName("should read a signed 1.19.2 command and write its signature data back")
    void signedCommand1192() {
      ByteBuf wire = Unpooled.buffer();
      McString.write(wire, "msg Steve hello");
      wire.writeLong(1_700_000_000_000L); // timestamp
      wire.writeLong(0x5A17_5A17_5A17_5A17L); // salt
      VarInt.write(wire, 1); // argument signatures
      McString.write(wire, "message");
      writeSignature(wire, 0x22);
      wire.writeBoolean(false); // signed preview
      VarInt.write(wire, 1); // last seen messages
      wire.writeLong(0x0123_4567_89AB_CDEFL).writeLong(0x0FED_CBA9_8765_4321L); // sender
      writeSignature(wire, 0x33);
      wire.writeBoolean(true); // last received message
      wire.writeLong(0x0123_4567_89AB_CDEFL).writeLong(0x0FED_CBA9_8765_4321L); // sender
      writeSignature(wire, 0x44);

      assertRoundtrip(wire, ProtocolVersion.MINECRAFT_1_19_1, "msg Steve hello");
    }

    /** A VarInt-prefixed 256-byte RSA signature, every byte set to {@code fill}. */
    private static void writeSignature(ByteBuf wire, int fill) {
      VarInt.write(wire, 256);
      for (int i = 0; i < 256; i++) {
        wire.writeByte(fill);
      }
    }

    private void assertRoundtrip(ByteBuf wire, ProtocolVersion version, String command) {
      byte[] sent = ByteBufUtil.getBytes(wire);
      ByteBuf out = Unpooled.buffer();
      try {
        ChatCommand decoded = ChatCommand.CODEC.decode(wire, version);
        ChatCommand.CODEC.encode(decoded, out, version);

        assertEquals(command, decoded.command());
        assertFalse(wire.isReadable(), "unread bytes");
        assertArrayEquals(sent, ByteBufUtil.getBytes(out));
      } finally {
        wire.release();
        out.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("registration")
  class Registration {

    /** As Velocity's {@code KeyedPlayerCommandPacket} and minecraft-data give them. */
    static Stream<Arguments> ids() {
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_19, 0x03),
          // 1.19.1 and 1.19.2 share protocol 760
          Arguments.of(ProtocolVersion.MINECRAFT_1_19_1, 0x04),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19_3, 0x04));
    }

    @ParameterizedTest(name = "{0}: id {1}")
    @MethodSource("ids")
    @DisplayName("should be decoded from 1.19, where commands left the chat packet")
    void decodedFrom119(ProtocolVersion version, int packetId) {
      assertSame(ChatCommand.CODEC, SERVERBOUND.lookup(version, packetId));
      assertEquals(packetId, SERVERBOUND.packetId(version, ChatCommand.class));
    }

    @Test
    @DisplayName("should not exist before 1.19, where a command is a chat line")
    void absentBefore119() {
      assertThrows(
          IllegalArgumentException.class,
          () -> SERVERBOUND.packetId(ProtocolVersion.MINECRAFT_1_18_2, ChatCommand.class));
    }
  }
}
