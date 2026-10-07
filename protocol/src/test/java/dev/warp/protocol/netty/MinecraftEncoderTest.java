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
package dev.warp.protocol.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.status.StatusRequest;

import java.util.Objects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MinecraftEncoder")
class MinecraftEncoderTest {

  // ---------------------------------------------------------------------------
  // Packet encoding
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("packet encoding")
  class PacketEncoding {

    @Test
    @DisplayName("should encode packets the proxy sends but never decodes (system chat)")
    void encodeOnlyPacket() {
      MinecraftEncoder encoder =
          new MinecraftEncoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);
      byte[] text = {0x08, 0x00, 0x02, 'h', 'i'};

      assertTrue(ch.writeOutbound(new SystemChatMessage(text, false)));

      ByteBuf out = ch.readOutbound();
      assertEquals(0x73, VarInt.read(out)); // system_chat since 1.21.2
      SystemChatMessage decoded =
          SystemChatMessage.CODEC.decode(out, ProtocolVersion.MINECRAFT_1_21_4);
      assertArrayEquals(text, decoded.rawContent());
      out.release();
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should encode Handshake with correct packet ID and payload")
    void handshake() {
      MinecraftEncoder encoder =
          new MinecraftEncoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      Handshake packet = new Handshake(769, "localhost", 25565, 2);
      assertTrue(ch.writeOutbound(packet));

      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      // Verify the full wire format by decoding through the codec
      int packetId = VarInt.read(out);
      assertEquals(0x00, packetId);

      Handshake decoded = Handshake.CODEC.decode(out, ProtocolVersion.MINECRAFT_1_21_4);
      assertEquals(769, decoded.protocolVersion());
      assertEquals("localhost", decoded.serverAddress());
      assertEquals(25565, decoded.serverPort());
      assertEquals(2, decoded.nextState());

      assertFalse(out.isReadable()); // codec consumed everything
      out.release();

      assertNull(ch.readOutbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should encode StatusRequest with packet ID only (empty payload)")
    void statusRequest() {
      MinecraftEncoder encoder =
          new MinecraftEncoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.STATUS);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      assertTrue(ch.writeOutbound(new StatusRequest()));

      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      int packetId = VarInt.read(out);
      assertEquals(0x00, packetId);

      // Empty payload — no more bytes
      assertFalse(out.isReadable());
      out.release();

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should encode KeepAlive with long payload for 1.21.4")
    void keepAliveLong() {
      MinecraftEncoder encoder =
          new MinecraftEncoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      assertTrue(ch.writeOutbound(new KeepAlive(0xCAFEBABEL)));

      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      int packetId = VarInt.read(out);
      // KeepAlive clientbound in 1.21.2+ is 0x27
      assertEquals(0x27, packetId);

      long id = out.readLong();
      assertEquals(0xCAFEBABEL, id);

      assertFalse(out.isReadable());
      out.release();

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should encode BundleDelimiter with packet ID only")
    void bundleDelimiter() {
      MinecraftEncoder encoder =
          new MinecraftEncoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      assertTrue(ch.writeOutbound(new BundleDelimiter()));

      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      int packetId = VarInt.read(out);
      assertEquals(0x00, packetId);

      assertFalse(out.isReadable());
      out.release();

      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Unregistered packet
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("unregistered packet")
  class UnregisteredPacket {

    @Test
    @DisplayName("should reject encoding a packet not registered in the current state")
    void wrongState() {
      // Handshake packet in STATUS state — not registered
      MinecraftEncoder encoder =
          new MinecraftEncoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.STATUS);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      Handshake packet = new Handshake(769, "localhost", 25565, 2);
      EncoderException ex = assertThrows(EncoderException.class, () -> ch.writeOutbound(packet));
      String msg = Objects.requireNonNull(ex.getMessage());
      assertTrue(msg.contains("Handshake"));
      assertTrue(msg.contains("STATUS"));

      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Blind forwarding bypass
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("blind forwarding bypass")
  class BlindForwardingBypass {

    @Test
    @DisplayName("should pass raw ByteBuf through without encoding")
    void byteBufPassthrough() {
      MinecraftEncoder encoder =
          new MinecraftEncoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      // Write a raw ByteBuf (simulating a blind-forwarded packet from the decoder)
      byte[] rawPacket = {0x7F, 0x01, 0x02, 0x03};
      ByteBuf buf = Unpooled.wrappedBuffer(rawPacket);

      assertTrue(ch.writeOutbound(buf));

      // The encoder should not touch it — output should be the same ByteBuf
      Object out = ch.readOutbound();
      assertInstanceOf(ByteBuf.class, out);

      ByteBuf forwarded = (ByteBuf) out;
      byte[] actual = new byte[forwarded.readableBytes()];
      forwarded.readBytes(actual);
      assertArrayEquals(rawPacket, actual);
      forwarded.release();

      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // State transitions
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("state transitions")
  class StateTransitions {

    @Test
    @DisplayName("should use updated registry after setState")
    void stateChange() {
      MinecraftEncoder encoder =
          new MinecraftEncoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      // In HANDSHAKE, Handshake packet should encode fine
      assertTrue(ch.writeOutbound(new Handshake(769, "localhost", 25565, 1)));
      ByteBuf out1 = ch.readOutbound();
      assertNotNull(out1);
      out1.release();

      // Switch to STATUS
      encoder.setState(ProtocolState.STATUS);
      assertEquals(ProtocolState.STATUS, encoder.state());

      // Now StatusRequest should encode fine
      assertTrue(ch.writeOutbound(new StatusRequest()));
      ByteBuf out2 = ch.readOutbound();
      assertNotNull(out2);
      out2.release();

      // And Handshake should fail
      assertThrows(
          EncoderException.class,
          () -> ch.writeOutbound(new Handshake(769, "localhost", 25565, 1)));

      ch.finish();
    }

    @Test
    @DisplayName("should use updated version after setVersion — different packet ID")
    void versionChange() {
      // KeepAlive clientbound: 0x00 in 1.8, 0x27 in 1.21.2+
      MinecraftEncoder encoder =
          new MinecraftEncoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_8, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      // Encode with 1.8 — expect packet ID 0x00
      assertTrue(ch.writeOutbound(new KeepAlive(1L)));
      ByteBuf out1 = ch.readOutbound();
      assertNotNull(out1);
      assertEquals(0x00, VarInt.read(out1));
      out1.release();

      // Switch to 1.21.4
      encoder.setVersion(ProtocolVersion.MINECRAFT_1_21_4);
      assertEquals(ProtocolVersion.MINECRAFT_1_21_4, encoder.version());
      assertEquals(PacketDirection.CLIENTBOUND, encoder.direction());

      // Encode again — expect packet ID 0x27
      assertTrue(ch.writeOutbound(new KeepAlive(2L)));
      ByteBuf out2 = ch.readOutbound();
      assertNotNull(out2);
      assertEquals(0x27, VarInt.read(out2));
      out2.release();

      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Roundtrip (full pipeline identity)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("roundtrip")
  class Roundtrip {

    @Test
    @DisplayName("should produce identity after encode → decode for StatusRequest")
    void statusRequestRoundtrip() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;

      MinecraftEncoder encoder =
          new MinecraftEncoder(PacketDirection.SERVERBOUND, version, ProtocolState.STATUS);
      EmbeddedChannel encoderCh = new EmbeddedChannel(encoder);

      MinecraftDecoder decoder =
          new MinecraftDecoder(PacketDirection.SERVERBOUND, version, ProtocolState.STATUS);
      EmbeddedChannel decoderCh = new EmbeddedChannel(decoder);

      // Encode
      assertTrue(encoderCh.writeOutbound(new StatusRequest()));
      ByteBuf wire = encoderCh.readOutbound();
      assertNotNull(wire);

      // Decode
      assertTrue(decoderCh.writeInbound(Frames.framed(wire)));
      Object out = decoderCh.readInbound();
      assertInstanceOf(StatusRequest.class, out);

      assertFalse(encoderCh.finish());
      assertFalse(decoderCh.finish());
    }
  }
}
