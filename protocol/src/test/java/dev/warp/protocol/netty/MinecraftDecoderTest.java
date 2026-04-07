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
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.status.StatusRequest;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MinecraftDecoder")
class MinecraftDecoderTest {

  // ---------------------------------------------------------------------------
  // Known packet decode
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("known packet decode")
  class KnownPacketDecode {

    @Test
    @DisplayName("should decode Handshake packet from raw bytes")
    void handshake() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Build wire bytes: [VarInt: 0x00][VarInt: protocol][String: address][Short: port][VarInt:
      // nextState]
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00); // packet ID
      VarInt.write(buf, 769); // protocol version 1.21.4
      VarInt.write(buf, 9); // string length
      buf.writeBytes("localhost".getBytes(StandardCharsets.UTF_8));
      buf.writeShort(25565); // port
      VarInt.write(buf, 2); // next state = login

      assertTrue(ch.writeInbound(buf));

      Object out = ch.readInbound();
      assertInstanceOf(Handshake.class, out);
      Handshake handshake = (Handshake) out;
      assertEquals(769, handshake.protocolVersion());
      assertEquals("localhost", handshake.serverAddress());
      assertEquals(25565, handshake.serverPort());
      assertEquals(2, handshake.nextState());

      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode StatusRequest (empty payload)")
    void statusRequest() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.STATUS);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // StatusRequest: packet ID 0x00, no payload
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);

      assertTrue(ch.writeInbound(buf));

      Object out = ch.readInbound();
      assertInstanceOf(StatusRequest.class, out);

      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode KeepAlive with version-dependent encoding (1.21.4 — long)")
    void keepAliveLong() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // KeepAlive clientbound in 1.21.4: ID 0x27, payload = long
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x27);
      buf.writeLong(0xDEADBEEFCAFEBABEL);

      assertTrue(ch.writeInbound(buf));

      Object out = ch.readInbound();
      assertInstanceOf(KeepAlive.class, out);
      assertEquals(0xDEADBEEFCAFEBABEL, ((KeepAlive) out).id());

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode KeepAlive with version-dependent encoding (1.8 — VarInt)")
    void keepAliveVarInt() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_8, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // KeepAlive clientbound in 1.8: ID 0x00, payload = VarInt
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);
      VarInt.write(buf, 42);

      assertTrue(ch.writeInbound(buf));

      Object out = ch.readInbound();
      assertInstanceOf(KeepAlive.class, out);
      assertEquals(42L, ((KeepAlive) out).id());

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode BundleDelimiter (empty payload, PLAY clientbound)")
    void bundleDelimiter() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // BundleDelimiter: ID 0x00 in 1.19.4+, no payload
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);

      assertTrue(ch.writeInbound(buf));

      Object out = ch.readInbound();
      assertInstanceOf(BundleDelimiter.class, out);

      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Blind forwarding
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("blind forwarding")
  class BlindForwarding {

    @Test
    @DisplayName("should forward unregistered packet ID as raw ByteBuf in PLAY state")
    void unregisteredPacketId() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Packet ID 0x7F — not registered for PLAY serverbound
      byte[] payload = {0x01, 0x02, 0x03, 0x04};
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x7F);
      buf.writeBytes(payload);

      int expectedSize = buf.readableBytes();

      assertTrue(ch.writeInbound(buf));

      // Output should be a raw ByteBuf, NOT a Packet
      Object out = ch.readInbound();
      assertInstanceOf(ByteBuf.class, out);
      assertFalse(out instanceof Packet);

      ByteBuf forwarded = (ByteBuf) out;
      // Should contain the full buffer: packet ID VarInt + payload
      assertEquals(expectedSize, forwarded.readableBytes());

      // Verify contents: packet ID 0x7F is 1 byte, then the payload
      int packetId = VarInt.read(forwarded);
      assertEquals(0x7F, packetId);

      byte[] forwardedPayload = new byte[forwarded.readableBytes()];
      forwarded.readBytes(forwardedPayload);
      assertArrayEquals(payload, forwardedPayload);
      forwarded.release();

      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should forward packet with multi-byte VarInt ID as raw ByteBuf")
    void multiBytPacketId() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Packet ID 0x100 (2-byte VarInt) — not registered
      byte[] payload = {(byte) 0xAA, (byte) 0xBB};
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x100);
      buf.writeBytes(payload);

      int expectedSize = buf.readableBytes();

      assertTrue(ch.writeInbound(buf));

      Object out = ch.readInbound();
      assertInstanceOf(ByteBuf.class, out);

      ByteBuf forwarded = (ByteBuf) out;
      assertEquals(expectedSize, forwarded.readableBytes());
      forwarded.release();

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should forward unknown packet with empty payload")
    void emptyPayload() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Packet ID 0x7E — unknown, no payload
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x7E);

      assertTrue(ch.writeInbound(buf));

      Object out = ch.readInbound();
      assertInstanceOf(ByteBuf.class, out);

      ByteBuf forwarded = (ByteBuf) out;
      assertEquals(1, forwarded.readableBytes()); // just the VarInt packet ID
      forwarded.release();

      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Boundary validation
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("boundary validation")
  class BoundaryValidation {

    @Test
    @DisplayName("should reject packet with trailing bytes after decode")
    void trailingBytes() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.STATUS);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // StatusRequest (ID 0x00) has empty payload, but we add trailing garbage
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);
      buf.writeByte(0xFF); // trailing byte — should trigger validation failure

      DecoderException ex = assertThrows(DecoderException.class, () -> ch.writeInbound(buf));
      String msg = Objects.requireNonNull(ex.getMessage());
      assertTrue(msg.contains("trailing bytes"));
      assertTrue(msg.contains("StatusRequest"));

      ch.finish();
    }

    @Test
    @DisplayName("should accept packet that consumes all bytes exactly")
    void exactConsumption() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.STATUS);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // StatusRequest (ID 0x00) has empty payload — exactly 0 bytes after ID
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);

      assertTrue(ch.writeInbound(buf));
      assertInstanceOf(StatusRequest.class, ch.readInbound());

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
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // In HANDSHAKE state, packet ID 0x00 is Handshake
      ByteBuf handshakeBuf = Unpooled.buffer();
      VarInt.write(handshakeBuf, 0x00);
      VarInt.write(handshakeBuf, 769);
      VarInt.write(handshakeBuf, 9);
      handshakeBuf.writeBytes("localhost".getBytes(StandardCharsets.UTF_8));
      handshakeBuf.writeShort(25565);
      VarInt.write(handshakeBuf, 1); // next state = status

      assertTrue(ch.writeInbound(handshakeBuf));
      assertInstanceOf(Handshake.class, ch.readInbound());

      // Switch to STATUS state
      decoder.setState(ProtocolState.STATUS);
      assertEquals(ProtocolState.STATUS, decoder.state());

      // Now packet ID 0x00 is StatusRequest (empty payload)
      ByteBuf statusBuf = Unpooled.buffer();
      VarInt.write(statusBuf, 0x00);

      assertTrue(ch.writeInbound(statusBuf));
      assertInstanceOf(StatusRequest.class, ch.readInbound());

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should update version via setVersion")
    void versionChange() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_7_2,
              ProtocolState.HANDSHAKE);

      assertEquals(ProtocolVersion.MINECRAFT_1_7_2, decoder.version());

      decoder.setVersion(ProtocolVersion.MINECRAFT_1_21_4);
      assertEquals(ProtocolVersion.MINECRAFT_1_21_4, decoder.version());
    }
  }

  // ---------------------------------------------------------------------------
  // Dead channel
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("dead channel")
  class DeadChannel {

    @Test
    @DisplayName("should produce no output on inactive channel")
    void inactiveChannel() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Close the channel, then write data
      ch.close().awaitUninterruptibly();

      // Build a valid Handshake packet
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);
      VarInt.write(buf, 769);
      VarInt.write(buf, 9);
      buf.writeBytes("localhost".getBytes(StandardCharsets.UTF_8));
      buf.writeShort(25565);
      VarInt.write(buf, 2);

      // writeInbound may return false or throw depending on Netty version,
      // but no Packet should appear in the inbound queue.
      try {
        ch.writeInbound(buf);
      } catch (Exception ignored) {
        // Channel is closed — write may fail. Ensure the buffer is released to
        // prevent leaks if the pipeline did not process it.
        if (buf.refCnt() > 0) {
          buf.release();
        }
      }

      assertNull(ch.readInbound());
    }
  }

  // ---------------------------------------------------------------------------
  // Error handling
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("error handling")
  class ErrorHandling {

    @Test
    @DisplayName("should reject negative packet ID")
    void negativePacketId() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND, ProtocolVersion.MINECRAFT_1_21_4, ProtocolState.PLAY);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Write a VarInt encoding of -1 (5 bytes, all continuation bits + sign)
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, -1);

      assertThrows(DecoderException.class, () -> ch.writeInbound(buf));
      ch.finish();
    }

    @Test
    @DisplayName("should wrap codec exception with packet context")
    void codecException() {
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel ch = new EmbeddedChannel(decoder);

      // Handshake (ID 0x00) needs at least a VarInt + String + Short + VarInt.
      // Send only the packet ID — the codec will fail reading the first field.
      ByteBuf buf = Unpooled.buffer();
      VarInt.write(buf, 0x00);
      // no payload — truncated

      DecoderException ex = assertThrows(DecoderException.class, () -> ch.writeInbound(buf));
      String msg = Objects.requireNonNull(ex.getMessage());
      assertTrue(msg.contains("0x0"));
      assertTrue(msg.contains("HANDSHAKE"));

      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Roundtrip with encoder
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("roundtrip")
  class Roundtrip {

    @Test
    @DisplayName("should survive encode → decode roundtrip for Handshake")
    void handshakeRoundtrip() {
      Handshake original = new Handshake(769, "mc.example.com", 25565, 2);

      // Encode
      MinecraftEncoder encoder =
          new MinecraftEncoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel encoderCh = new EmbeddedChannel(encoder);
      assertTrue(encoderCh.writeOutbound(original));
      ByteBuf wire = encoderCh.readOutbound();
      assertNotNull(wire);

      // Decode
      MinecraftDecoder decoder =
          new MinecraftDecoder(
              PacketDirection.SERVERBOUND,
              ProtocolVersion.MINECRAFT_1_21_4,
              ProtocolState.HANDSHAKE);
      EmbeddedChannel decoderCh = new EmbeddedChannel(decoder);
      assertTrue(decoderCh.writeInbound(wire));

      Object out = decoderCh.readInbound();
      assertInstanceOf(Handshake.class, out);
      Handshake decoded = (Handshake) out;
      assertEquals(original.protocolVersion(), decoded.protocolVersion());
      assertEquals(original.serverAddress(), decoded.serverAddress());
      assertEquals(original.serverPort(), decoded.serverPort());
      assertEquals(original.nextState(), decoded.nextState());

      assertFalse(encoderCh.finish());
      assertFalse(decoderCh.finish());
    }

    @Test
    @DisplayName("should survive encode → decode roundtrip for KeepAlive across versions")
    void keepAliveRoundtrip() {
      KeepAlive original = new KeepAlive(123456789L);

      // Test with 1.21.4 (long encoding)
      roundtripKeepAlive(original, ProtocolVersion.MINECRAFT_1_21_4);

      // Test with 1.8 (VarInt encoding — value must fit in int)
      roundtripKeepAlive(new KeepAlive(42), ProtocolVersion.MINECRAFT_1_8);
    }

    private void roundtripKeepAlive(KeepAlive original, ProtocolVersion version) {
      // Use PLAY clientbound — KeepAlive is registered in both directions
      MinecraftEncoder encoder =
          new MinecraftEncoder(PacketDirection.CLIENTBOUND, version, ProtocolState.PLAY);
      EmbeddedChannel encoderCh = new EmbeddedChannel(encoder);
      assertTrue(encoderCh.writeOutbound(original));
      ByteBuf wire = encoderCh.readOutbound();
      assertNotNull(wire);

      MinecraftDecoder decoder =
          new MinecraftDecoder(PacketDirection.CLIENTBOUND, version, ProtocolState.PLAY);
      EmbeddedChannel decoderCh = new EmbeddedChannel(decoder);
      assertTrue(decoderCh.writeInbound(wire));

      Object out = decoderCh.readInbound();
      assertInstanceOf(KeepAlive.class, out);
      assertEquals(original.id(), ((KeepAlive) out).id());

      assertFalse(encoderCh.finish());
      assertFalse(decoderCh.finish());
    }
  }
}
