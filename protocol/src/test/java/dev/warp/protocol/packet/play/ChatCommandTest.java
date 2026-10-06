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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.netty.handler.codec.DecoderException;
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
      ChatCommand decoded = roundtrip(wire, version);

      assertEquals(command, decoded.command());
      assertEquals(0, decoded.lastSeenOffset(), "the last seen messages are sent in full");
    }
  }

  // ---------------------------------------------------------------------------
  // Session commands (1.19.3 to 1.20.4)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("session commands (1.19.3 to 1.20.4)")
  class Session {

    /** Every protocol whose commands end with an offset-based last-seen update. */
    static Stream<ProtocolVersion> versions() {
      return Stream.of(
          ProtocolVersion.MINECRAFT_1_19_3,
          ProtocolVersion.MINECRAFT_1_19_4,
          ProtocolVersion.MINECRAFT_1_20_1,
          ProtocolVersion.MINECRAFT_1_20_2,
          ProtocolVersion.MINECRAFT_1_20_4);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    @DisplayName("should read the last-seen offset of a command and write its bytes back unchanged")
    void readsLastSeenOffset(ProtocolVersion version) {
      ChatCommand decoded = roundtrip(sessionCommand("server", 0, 1), version);

      assertEquals("server", decoded.command());
      assertEquals(1, decoded.lastSeenOffset());
    }

    @Test
    @DisplayName("should read the offset of /server acknowledging one message, byte for byte")
    void exactLayout() {
      ByteBuf wire =
          Unpooled.wrappedBuffer(
              ByteBufUtil.decodeHexDump(
                  "06736572766572" // "server"
                      + "0000018bcfe56800" // timestamp: 1700000000000
                      + "5a175a175a175a17" // salt
                      + "00" // no argument signatures
                      + "01" // last-seen offset
                      + "000008")); // acknowledged: the newest of the last 20

      ChatCommand decoded = roundtrip(wire, ProtocolVersion.MINECRAFT_1_20_4);

      assertEquals(1, decoded.lastSeenOffset());
      assertEquals(8 + 8 + 1 + 1 + 3, decoded.rawSignatureData().length);
    }

    @Test
    @DisplayName("should read the offset after argument signatures, and a multi-byte offset")
    void signedCommand() {
      ChatCommand decoded =
          roundtrip(sessionCommand("msg Steve hello", 2, 300), ProtocolVersion.MINECRAFT_1_20_1);

      assertEquals("msg Steve hello", decoded.command());
      assertEquals(300, decoded.lastSeenOffset());
    }

    @Test
    @DisplayName("should read a zero offset when the client saw nothing new")
    void zeroOffset() {
      ChatCommand decoded =
          roundtrip(sessionCommand("server lobby", 0, 0), ProtocolVersion.MINECRAFT_1_19_4);

      assertEquals(0, decoded.lastSeenOffset());
    }

    @Test
    @DisplayName("should reject more argument signatures than a client sends")
    void tooManySignatures() {
      assertRejected(sessionCommand("msg Steve hello", 9, 0), "argument signature count: 9");
    }

    @Test
    @DisplayName("should reject a negative last-seen offset, which a server refuses")
    void negativeOffset() {
      assertRejected(sessionCommand("server", 0, -1), "Negative last-seen offset");
    }

    @Test
    @DisplayName("should reject a command cut short in its last-seen update")
    void truncated() {
      ByteBuf wire = sessionCommand("server", 0, 1);
      ByteBuf cut = wire.retainedSlice(0, wire.writerIndex() - 1);
      wire.release();

      assertThrows(
          IndexOutOfBoundsException.class,
          () -> ChatCommand.CODEC.decode(cut, ProtocolVersion.MINECRAFT_1_20_4));
      cut.release();
    }

    @Test
    @DisplayName("should reject bytes after the last-seen update")
    void trailingBytes() {
      ByteBuf wire = sessionCommand("server", 0, 1).writeByte(0);

      assertRejected(wire, "Unexpected bytes after the last-seen update: 1");
    }

    @Test
    @DisplayName("should read no offset from 1.20.5, whose unsigned commands carry none")
    void noOffsetFrom1205() {
      ByteBuf wire = Unpooled.buffer();
      McString.write(wire, "server");

      ChatCommand decoded = roundtrip(wire, ProtocolVersion.MINECRAFT_1_20_5);

      assertEquals(0, decoded.lastSeenOffset());
      assertEquals(0, decoded.rawSignatureData().length);
    }

    /**
     * A command as a 1.19.3 to 1.20.4 client sends it: {@code signatures} 256-byte argument
     * signatures, then the last-seen update.
     */
    private static ByteBuf sessionCommand(String command, int signatures, int offset) {
      ByteBuf wire = Unpooled.buffer();
      McString.write(wire, command);
      wire.writeLong(1_700_000_000_000L); // timestamp
      wire.writeLong(0x5A17_5A17_5A17_5A17L); // salt
      VarInt.write(wire, signatures);
      for (int i = 0; i < signatures; i++) {
        McString.write(wire, "message");
        for (int b = 0; b < 256; b++) {
          wire.writeByte(i + 1);
        }
      }
      VarInt.write(wire, offset);
      wire.writeMedium(0x0F_00_08); // acknowledged
      return wire;
    }

    private static void assertRejected(ByteBuf wire, String reason) {
      try {
        DecoderException e =
            assertThrows(
                DecoderException.class,
                () -> ChatCommand.CODEC.decode(wire, ProtocolVersion.MINECRAFT_1_20_4));
        String message = String.valueOf(e.getMessage());
        assertTrue(message.contains(reason), message);
      } finally {
        wire.release();
      }
    }
  }

  /** Decodes {@code wire} to its last byte and checks that encoding gives the same bytes back. */
  private static ChatCommand roundtrip(ByteBuf wire, ProtocolVersion version) {
    byte[] sent = ByteBufUtil.getBytes(wire);
    ByteBuf out = Unpooled.buffer();
    try {
      ChatCommand decoded = ChatCommand.CODEC.decode(wire, version);
      ChatCommand.CODEC.encode(decoded, out, version);

      assertFalse(wire.isReadable(), "unread bytes");
      assertArrayEquals(sent, ByteBufUtil.getBytes(out));
      return decoded;
    } finally {
      wire.release();
      out.release();
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
