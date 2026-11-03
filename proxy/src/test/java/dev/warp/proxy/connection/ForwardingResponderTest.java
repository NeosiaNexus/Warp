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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ForwardingResponder")
class ForwardingResponderTest {

  private static final byte[] PAYLOAD = {1, 2, 3, 4};

  @Test
  @DisplayName("should answer the forwarding query itself, toward the backend, and consume it")
  void answersForwardingQuery() {
    EmbeddedChannel channel = new EmbeddedChannel(new ForwardingResponder(() -> PAYLOAD));

    channel.writeInbound(query(7, PlayerForwarding.VELOCITY_CHANNEL));

    assertNull(channel.readInbound());
    ByteBuf answer = channel.readOutbound();
    assertEquals(0x02, VarInt.read(answer));
    assertEquals(7, VarInt.read(answer));
    assertTrue(answer.readBoolean());
    assertArrayEquals(PAYLOAD, ByteBufUtil.getBytes(answer));
    answer.release();
    assertFalse(channel.finish());
  }

  @Test
  @DisplayName("should pass any other query on, untouched")
  void passesOtherQueries() {
    EmbeddedChannel channel = new EmbeddedChannel(new ForwardingResponder(() -> PAYLOAD));
    ByteBuf other = query(3, "fabric:hello");
    byte[] bytes = ByteBufUtil.getBytes(other);

    channel.writeInbound(other);

    ByteBuf passed = channel.readInbound();
    assertArrayEquals(bytes, ByteBufUtil.getBytes(passed));
    passed.release();
    assertNull(channel.readOutbound());
    assertFalse(channel.finish());
  }

  @Test
  @DisplayName("should pass the other login packets on, and leave at Login Success")
  void leavesAtLoginSuccess() {
    EmbeddedChannel channel = new EmbeddedChannel(new ForwardingResponder(() -> PAYLOAD));
    ByteBuf setCompression = Unpooled.buffer();
    VarInt.write(setCompression, 0x03);
    VarInt.write(setCompression, 256);
    ByteBuf loginSuccess = Unpooled.buffer().writeByte(0x02).writeLong(1).writeLong(2);

    channel.writeInbound(setCompression);
    assertNotNull(channel.pipeline().get(ForwardingResponder.class));
    channel.writeInbound(loginSuccess);

    assertNull(channel.pipeline().get(ForwardingResponder.class));
    ByteBuf first = channel.readInbound();
    assertEquals(0x03, first.getByte(0));
    first.release();
    ByteBuf second = channel.readInbound();
    assertEquals(0x02, second.getByte(0));
    assertEquals(17, second.readableBytes());
    second.release();
    assertFalse(channel.finish());
  }

  private static ByteBuf query(int messageId, String channel) {
    ByteBuf packet = Unpooled.buffer();
    VarInt.write(packet, 0x04);
    VarInt.write(packet, messageId);
    McString.write(packet, channel);
    return packet.writeBytes(new byte[] {9, 9});
  }
}
