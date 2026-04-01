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
package dev.warp.protocol.packet.handshake;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.warp.protocol.ProtocolVersion;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Handshake packet codec")
class HandshakeTest {

  @Test
  @DisplayName("should roundtrip a login handshake")
  void roundtripLogin() {
    Handshake original = new Handshake(769, "mc.example.com", 25565, 2);
    ByteBuf buf = Unpooled.buffer();
    try {
      Handshake.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
      Handshake decoded = Handshake.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);

      assertEquals(769, decoded.protocolVersion());
      assertEquals("mc.example.com", decoded.serverAddress());
      assertEquals(25565, decoded.serverPort());
      assertEquals(2, decoded.nextState());
      assertEquals(0, buf.readableBytes());
    } finally {
      buf.release();
    }
  }

  @Test
  @DisplayName("should roundtrip a status handshake")
  void roundtripStatus() {
    Handshake original = new Handshake(47, "localhost", 25565, 1);
    ByteBuf buf = Unpooled.buffer();
    try {
      Handshake.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_8);
      Handshake decoded = Handshake.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_8);

      assertEquals(47, decoded.protocolVersion());
      assertEquals("localhost", decoded.serverAddress());
      assertEquals(25565, decoded.serverPort());
      assertEquals(1, decoded.nextState());
    } finally {
      buf.release();
    }
  }

  @Test
  @DisplayName("should handle FML marker in server address")
  void fmlMarker() {
    Handshake original = new Handshake(769, "mc.example.com\0FML2\0", 25565, 2);
    ByteBuf buf = Unpooled.buffer();
    try {
      Handshake.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
      Handshake decoded = Handshake.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);

      assertEquals("mc.example.com\0FML2\0", decoded.serverAddress());
    } finally {
      buf.release();
    }
  }
}
