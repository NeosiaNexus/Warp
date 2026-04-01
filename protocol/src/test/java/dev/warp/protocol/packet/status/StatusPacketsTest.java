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
package dev.warp.protocol.packet.status;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.warp.protocol.ProtocolVersion;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Status packet codecs")
class StatusPacketsTest {

  @Nested
  @DisplayName("StatusRequest")
  class StatusRequestCodec {

    @Test
    @DisplayName("should roundtrip empty status request")
    void roundtrip() {
      ByteBuf buf = Unpooled.buffer();
      try {
        StatusRequest.CODEC.encode(new StatusRequest(), buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(0, buf.readableBytes());
        StatusRequest decoded = StatusRequest.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(new StatusRequest(), decoded);
      } finally {
        buf.release();
      }
    }
  }

  @Nested
  @DisplayName("StatusResponse")
  class StatusResponseCodec {

    @Test
    @DisplayName("should roundtrip JSON status response")
    void roundtrip() {
      String json =
          "{\"version\":{\"name\":\"1.21.4\",\"protocol\":769},\"players\":{\"max\":100,\"online\":42}}";
      StatusResponse original = new StatusResponse(json);
      ByteBuf buf = Unpooled.buffer();
      try {
        StatusResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        StatusResponse decoded = StatusResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(json, decoded.json());
        assertEquals(0, buf.readableBytes());
      } finally {
        buf.release();
      }
    }
  }

  @Nested
  @DisplayName("PingRequest / PongResponse")
  class PingPong {

    @Test
    @DisplayName("should roundtrip ping request")
    void pingRoundtrip() {
      PingRequest original = new PingRequest(1234567890L);
      ByteBuf buf = Unpooled.buffer();
      try {
        PingRequest.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        PingRequest decoded = PingRequest.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(1234567890L, decoded.payload());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should roundtrip pong response")
    void pongRoundtrip() {
      PongResponse original = new PongResponse(9876543210L);
      ByteBuf buf = Unpooled.buffer();
      try {
        PongResponse.CODEC.encode(original, buf, ProtocolVersion.MINECRAFT_1_21_4);
        PongResponse decoded = PongResponse.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
        assertEquals(9876543210L, decoded.payload());
      } finally {
        buf.release();
      }
    }
  }
}
