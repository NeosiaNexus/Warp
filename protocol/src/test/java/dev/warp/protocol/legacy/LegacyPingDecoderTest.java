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
package dev.warp.protocol.legacy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("LegacyPingDecoder")
class LegacyPingDecoderTest {

  @Nested
  @DisplayName("legacy pings")
  class Pings {

    @Test
    @DisplayName("should read a lone 0xFE as a Minecraft 1.3 ping")
    void minecraft13() {
      EmbeddedChannel channel = channel();

      channel.writeInbound(bytes("fe"));

      assertEquals(new LegacyPing(LegacyPing.Format.MINECRAFT_1_3, null, 0), channel.readInbound());
    }

    @Test
    @DisplayName("should read 0xFE 0x01 as a Minecraft 1.4 ping")
    void minecraft14() {
      EmbeddedChannel channel = channel();

      channel.writeInbound(bytes("fe01"));

      assertEquals(new LegacyPing(LegacyPing.Format.MINECRAFT_1_4, null, 0), channel.readInbound());
    }

    @Test
    @DisplayName("should read the host name and port of a Minecraft 1.6 ping")
    void minecraft16() {
      EmbeddedChannel channel = channel();

      channel.writeInbound(
          Unpooled.wrappedBuffer(pingHost("MC|PingHost", "mc.example.net", 25565)));

      assertEquals(
          new LegacyPing(LegacyPing.Format.MINECRAFT_1_6, "mc.example.net", 25565),
          channel.readInbound());
    }

    @Test
    @DisplayName("should wait for the rest of a 1.6 ping split across reads")
    void fragmented() {
      EmbeddedChannel channel = channel();
      byte[] ping = pingHost("MC|PingHost", "mc.example.net", 25565);

      channel.writeInbound(Unpooled.wrappedBuffer(ping, 0, 10));
      assertNull(channel.readInbound(), "incomplete: nothing yet");
      channel.writeInbound(Unpooled.wrappedBuffer(ping, 10, ping.length - 10));

      assertEquals(
          new LegacyPing(LegacyPing.Format.MINECRAFT_1_6, "mc.example.net", 25565),
          channel.readInbound());
    }

    @Test
    @DisplayName("should answer a 1.6 ping with another channel as a ping without host")
    void otherChannel() {
      EmbeddedChannel channel = channel();

      channel.writeInbound(Unpooled.wrappedBuffer(pingHost("MC|Other", "mc.example.net", 1)));

      assertEquals(new LegacyPing(LegacyPing.Format.MINECRAFT_1_6, null, 0), channel.readInbound());
    }

    @Test
    @DisplayName("should not wait for a 1.6 ping announcing more data than one can hold")
    void oversizedData() {
      EmbeddedChannel channel = channel();
      ByteBuf ping = Unpooled.buffer();
      ping.writeBytes(bytes("fe01fa"));
      writeLegacyString(ping, "MC|PingHost");
      ping.writeShort(0xFFFF);

      channel.writeInbound(ping);

      assertEquals(new LegacyPing(LegacyPing.Format.MINECRAFT_1_6, null, 0), channel.readInbound());
    }

    @Test
    @DisplayName("should discard what the client sends after a ping")
    void discardsAfter() {
      EmbeddedChannel channel = channel();
      channel.writeInbound(bytes("fe"));
      channel.readInbound();

      channel.writeInbound(bytes("01fa0000"));

      assertNull(channel.readInbound());
      assertNotNull(channel.pipeline().get(LegacyPingDecoder.class), "still discarding");
    }
  }

  @Test
  @DisplayName("should read 0x02 followed by more bytes as a legacy login")
  void legacyLogin() {
    EmbeddedChannel channel = channel();

    channel.writeInbound(bytes("024e0005"));

    assertEquals(new LegacyLogin(), channel.readInbound());
  }

  @Nested
  @DisplayName("framed connections")
  class Framed {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "1000fd0509", // a handshake: length 16, packet id 0
          "fe0100fd05", // a 254-byte handshake: length FE 01, packet id 0
          "fe0200fd05", // a 382-byte handshake: length FE 02
          "0100" // a frame of one byte
        })
    @DisplayName("should hand every byte to the next handler and leave the pipeline")
    void passesThrough(String hex) {
      EmbeddedChannel channel = channel();

      channel.writeInbound(bytes(hex));

      ByteBuf passed = channel.readInbound();
      assertArrayEquals(HexFormat.of().parseHex(hex), ByteBufUtil.getBytes(passed));
      passed.release();
      assertNull(channel.pipeline().get(LegacyPingDecoder.class));
      assertNull(channel.pipeline().get(LegacyDisconnectEncoder.class));
    }

    @Test
    @DisplayName("should pass later reads through untouched once gone")
    void laterReads() {
      EmbeddedChannel channel = channel();
      channel.writeInbound(bytes("1000"));
      ByteBuf first = channel.readInbound();
      first.release();

      channel.writeInbound(bytes("fe"));

      ByteBuf second = channel.readInbound();
      assertArrayEquals(new byte[] {(byte) 0xFE}, ByteBufUtil.getBytes(second));
      second.release();
    }
  }

  @Nested
  @DisplayName("LegacyDisconnectEncoder")
  class Encoder {

    @Test
    @DisplayName("should write 0xFF, the length in characters and the UTF-16BE text")
    void encodes() {
      EmbeddedChannel channel = channel();

      channel.writeOutbound(new LegacyDisconnect("Hé"));

      ByteBuf written = channel.readOutbound();
      assertArrayEquals(HexFormat.of().parseHex("ff0002004800e9"), ByteBufUtil.getBytes(written));
      written.release();
    }
  }

  @Test
  @DisplayName("should keep a channel that sent nothing waiting")
  void nothingYet() {
    EmbeddedChannel channel = channel();

    channel.writeInbound(Unpooled.EMPTY_BUFFER);

    assertNull(channel.readInbound());
    assertTrue(channel.isActive());
    assertFalse(channel.finish());
  }

  private static EmbeddedChannel channel() {
    return new EmbeddedChannel(LegacyDisconnectEncoder.INSTANCE, new LegacyPingDecoder());
  }

  private static ByteBuf bytes(String hex) {
    return Unpooled.wrappedBuffer(HexFormat.of().parseHex(hex));
  }

  /** A 1.6 legacy ping: 0xFE 0x01, then a plugin message on {@code channel}. */
  static byte[] pingHost(String channel, String host, int port) {
    ByteBuf buf = Unpooled.buffer();
    buf.writeBytes(HexFormat.of().parseHex("fe01fa"));
    writeLegacyString(buf, channel);
    buf.writeShort(7 + host.length() * 2);
    buf.writeByte(78); // 1.6.4
    writeLegacyString(buf, host);
    buf.writeInt(port);
    byte[] bytes = ByteBufUtil.getBytes(buf);
    buf.release();
    return bytes;
  }

  private static void writeLegacyString(ByteBuf buf, String text) {
    buf.writeShort(text.length());
    buf.writeCharSequence(text, StandardCharsets.UTF_16BE);
  }
}
