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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
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

@DisplayName("ChatAcknowledgement")
class ChatAcknowledgementTest {

  private static final PacketRegistry SERVERBOUND =
      StateRegistry.get(ProtocolState.PLAY, PacketDirection.SERVERBOUND);

  // ---------------------------------------------------------------------------
  // Codec
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("codec")
  class Codec {

    /** The offset as a VarInt, the packet's only field. */
    static Stream<Arguments> offsets() {
      return Stream.of(
          Arguments.of(1, new byte[] {0x01}),
          Arguments.of(64, new byte[] {0x40}),
          Arguments.of(300, new byte[] {(byte) 0xAC, 0x02}));
    }

    @ParameterizedTest(name = "offset {0}")
    @MethodSource("offsets")
    @DisplayName("should write the offset as a VarInt and read it back")
    void writesOffset(int offset, byte[] expected) {
      ByteBuf buf = Unpooled.buffer();
      try {
        ChatAcknowledgement.CODEC.encode(
            new ChatAcknowledgement(offset), buf, ProtocolVersion.MINECRAFT_1_20_4);

        assertArrayEquals(expected, ByteBufUtil.getBytes(buf));
        assertEquals(
            new ChatAcknowledgement(offset),
            ChatAcknowledgement.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_20_4));
        assertFalse(buf.isReadable(), "unread bytes");
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should refuse a negative offset, which a server kicks the player for")
    void negativeOffset() {
      assertThrows(IllegalArgumentException.class, () -> new ChatAcknowledgement(-1));
    }
  }

  // ---------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("registration")
  class Registration {

    /** One version per protocol from 1.19.3 to 1.20.4. */
    static Stream<ProtocolVersion> versions() {
      return Stream.of(
          ProtocolVersion.MINECRAFT_1_19_3,
          ProtocolVersion.MINECRAFT_1_19_4,
          ProtocolVersion.MINECRAFT_1_20_1,
          ProtocolVersion.MINECRAFT_1_20_2,
          ProtocolVersion.MINECRAFT_1_20_4);
    }

    /** As Velocity's StateRegistry and minecraft-data ({@code message_acknowledgement}) give it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    @DisplayName("should be sent at 0x03 from 1.19.3 to 1.20.4")
    void sentAt0x03(ProtocolVersion version) {
      assertEquals(0x03, SERVERBOUND.packetId(version, ChatAcknowledgement.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    @DisplayName("should not be decoded: a client's own acknowledgements are forwarded untouched")
    void notDecoded(ProtocolVersion version) {
      assertNull(SERVERBOUND.lookup(version, 0x03));
    }

    @Test
    @DisplayName("should not be sent before 1.19.3, whose commands carry no last-seen offset")
    void absentBefore1193() {
      assertThrows(
          IllegalArgumentException.class,
          () -> SERVERBOUND.packetId(ProtocolVersion.MINECRAFT_1_19_2, ChatAcknowledgement.class));
    }

    @Test
    @DisplayName("should not be sent from 1.20.5, whose commands the proxy decodes carry none")
    void absentFrom1205() {
      assertThrows(
          IllegalArgumentException.class,
          () -> SERVERBOUND.packetId(ProtocolVersion.MINECRAFT_1_20_5, ChatAcknowledgement.class));
    }
  }
}
