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
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;

import java.nio.charset.StandardCharsets;
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

@DisplayName("LegacyChatMessage")
class LegacyChatMessageTest {

  private static final PacketRegistry SERVERBOUND =
      StateRegistry.get(ProtocolState.PLAY, PacketDirection.SERVERBOUND);

  // ---------------------------------------------------------------------------
  // Codec
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("codec")
  class Codec {

    @Test
    @DisplayName("should read the typed line and write it back byte for byte")
    void roundtrip() {
      String line = "/server survival";
      byte[] wire = new byte[1 + line.length()];
      wire[0] = (byte) line.length();
      System.arraycopy(line.getBytes(StandardCharsets.US_ASCII), 0, wire, 1, line.length());
      ByteBuf in = Unpooled.wrappedBuffer(wire);
      ByteBuf out = Unpooled.buffer();
      try {
        LegacyChatMessage decoded =
            LegacyChatMessage.CODEC.decode(in, ProtocolVersion.MINECRAFT_1_12_2);
        LegacyChatMessage.CODEC.encode(decoded, out, ProtocolVersion.MINECRAFT_1_12_2);

        assertEquals(line, decoded.message());
        assertFalse(in.isReadable(), "unread bytes");
        assertArrayEquals(wire, ByteBufUtil.getBytes(out));
      } finally {
        in.release();
        out.release();
      }
    }

    @Test
    @DisplayName("should accept a 256-character line, the limit since 1.11, from any version")
    void longestLine() {
      LegacyChatMessage line = new LegacyChatMessage("a".repeat(256));
      ByteBuf buf = Unpooled.buffer();
      try {
        LegacyChatMessage.CODEC.encode(line, buf, ProtocolVersion.MINECRAFT_1_8);

        assertEquals(line, LegacyChatMessage.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_8));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should reject a line longer than 256 characters")
    void tooLong() {
      ByteBuf buf = Unpooled.buffer();
      try {
        String line = "a".repeat(257);
        VarInt.write(buf, line.length());
        buf.writeCharSequence(line, StandardCharsets.US_ASCII);

        assertThrows(
            DecoderException.class,
            () -> LegacyChatMessage.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_18_2));
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("registration")
  class Registration {

    /**
     * The serverbound chat id on each side of every change, as Velocity's {@code LegacyChatPacket}
     * and minecraft-data ({@code chat}) give it.
     */
    static Stream<Arguments> ids() {
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_2, 0x01),
          Arguments.of(ProtocolVersion.MINECRAFT_1_8, 0x01),
          Arguments.of(ProtocolVersion.MINECRAFT_1_9, 0x02),
          Arguments.of(ProtocolVersion.MINECRAFT_1_11_1, 0x02),
          Arguments.of(ProtocolVersion.MINECRAFT_1_12, 0x03),
          Arguments.of(ProtocolVersion.MINECRAFT_1_12_1, 0x02),
          Arguments.of(ProtocolVersion.MINECRAFT_1_12_2, 0x02),
          Arguments.of(ProtocolVersion.MINECRAFT_1_13_2, 0x02),
          Arguments.of(ProtocolVersion.MINECRAFT_1_14, 0x03),
          Arguments.of(ProtocolVersion.MINECRAFT_1_16_4, 0x03),
          Arguments.of(ProtocolVersion.MINECRAFT_1_18_2, 0x03));
    }

    @ParameterizedTest(name = "{0}: id {1}")
    @MethodSource("ids")
    @DisplayName("should be decoded at the chat id of each version up to 1.18.2")
    void decodedUpTo1182(ProtocolVersion version, int packetId) {
      assertSame(LegacyChatMessage.CODEC, SERVERBOUND.lookup(version, packetId));
      assertEquals(packetId, SERVERBOUND.packetId(version, LegacyChatMessage.class));
    }

    @Test
    @DisplayName("should not exist from 1.19, where commands have their own packet")
    void goneFrom119() {
      for (ProtocolVersion version :
          new ProtocolVersion[] {
            ProtocolVersion.MINECRAFT_1_19,
            ProtocolVersion.MINECRAFT_1_19_1,
            ProtocolVersion.MINECRAFT_1_19_3,
            ProtocolVersion.latest()
          }) {
        assertThrows(
            IllegalArgumentException.class,
            () -> SERVERBOUND.packetId(version, LegacyChatMessage.class),
            version.toString());
      }
    }
  }
}
