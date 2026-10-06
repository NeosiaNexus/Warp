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
package dev.warp.proxy.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.login.LoginStart;

import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Drives the client login through the pipeline that {@link ServerChannelInitializer} builds. */
@DisplayName("LoginSessionHandler")
class LoginSessionHandlerTest {

  private static final int LOGIN_NEXT_STATE = 2;

  // ---------------------------------------------------------------------------
  // Disconnect reasons
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("disconnect reasons")
  class DisconnectReasons {

    /** The oldest version, the last before NBT text components, the first with them, the latest. */
    static Stream<ProtocolVersion> versions() {
      return Stream.of(
          ProtocolVersion.oldest(),
          ProtocolVersion.MINECRAFT_1_20_2,
          ProtocolVersion.MINECRAFT_1_20_3,
          ProtocolVersion.latest());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    @DisplayName("should send the login disconnect reason as a JSON string the client can read")
    void loginReasonIsJsonString(ProtocolVersion version) {
      EmbeddedChannel channel = loginChannel(version);

      // "x" is shorter than any valid username.
      writePacket(channel, LoginStart.CODEC, new LoginStart("x", UUID.randomUUID()), version);

      ByteBuf frame = channel.readOutbound();
      assertNotNull(frame, "expected a LoginDisconnect frame");
      try {
        int length = VarInt.read(frame);
        assertEquals(length, frame.readableBytes(), "frame length");
        assertEquals(0x00, VarInt.read(frame), "LoginDisconnect packet id");
        assertEquals("{\"text\":\"Invalid username\"}", McString.read(frame));
        assertFalse(frame.isReadable(), "nothing may follow the reason");
      } finally {
        frame.release();
      }
      channel.runPendingTasks();
      assertFalse(channel.isActive(), "the connection must close after the reason");
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** A client pipeline that has completed the handshake into the LOGIN state. */
  private static EmbeddedChannel loginChannel(ProtocolVersion version) {
    EmbeddedChannel channel = new EmbeddedChannel();
    channel.pipeline().addLast(new ServerChannelInitializer(TestLoginContexts.offline()));
    channel.runPendingTasks();
    writePacket(
        channel,
        Handshake.CODEC,
        new Handshake(version.protocol(), "localhost", 25577, LOGIN_NEXT_STATE),
        version);
    return channel;
  }

  /** Writes {@code packet} (packet id 0x00) to the channel's inbound as one frame. */
  private static <T extends Packet> void writePacket(
      EmbeddedChannel channel, PacketCodec<T> codec, T packet, ProtocolVersion version) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    codec.encode(packet, body, version);
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    channel.writeInbound(frame);
  }
}
