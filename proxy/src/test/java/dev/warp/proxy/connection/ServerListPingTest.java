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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the complete Server List Ping flow.
 *
 * <p>Each test builds a full pipeline (frame + decode + encode + connection handler) and simulates
 * the Minecraft SLP protocol:
 *
 * <pre>{@code
 * C → S: Handshake(nextState=1)
 * C → S: StatusRequest
 * S → C: StatusResponse (JSON)
 * C → S: PingRequest(payload)
 * S → C: PongResponse(same payload)
 *        [connection closes]
 * }</pre>
 */
@DisplayName("Server List Ping integration")
class ServerListPingTest {

  // ---------------------------------------------------------------------------
  // Full SLP flow
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("complete SLP flow")
  class FullFlow {

    @Test
    @DisplayName("should complete handshake → status request → ping → close")
    void fullPingFlow() {
      EmbeddedChannel ch = createFullPipeline();

      // 1. Send Handshake(nextState=1)
      writeFramedHandshake(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), "localhost", 25577, 1);

      // No outbound response expected for handshake.
      assertNull(ch.readOutbound());

      // 2. Send StatusRequest (empty payload, packet ID 0x00)
      writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);

      // 3. Expect StatusResponse (packet ID 0x00, JSON string)
      ByteBuf responseFrame = ch.readOutbound();
      assertNotNull(responseFrame, "Expected StatusResponse frame");
      // Unframe: read VarInt length, then the packet data.
      int responseLength = VarInt.read(responseFrame);
      assertTrue(responseLength > 0, "Response frame should have content");
      int packetId = VarInt.read(responseFrame);
      assertEquals(0x00, packetId, "StatusResponse packet ID should be 0x00");
      // Read JSON string.
      String json = McString.read(responseFrame);
      assertFalse(responseFrame.isReadable(), "StatusResponse should consume all bytes");
      responseFrame.release();

      // Validate JSON structure.
      assertTrue(json.contains("\"version\""), "JSON should contain version");
      assertTrue(json.contains("\"players\""), "JSON should contain players");
      assertTrue(json.contains("\"description\""), "JSON should contain description");
      assertTrue(json.contains("\"protocol\":" + ProtocolVersion.latest().protocol()));

      // 4. Send PingRequest (packet ID 0x01, long payload)
      long pingPayload = 0xDEADBEEFCAFEBABEL;
      ByteBuf pingBody = Unpooled.buffer(8);
      pingBody.writeLong(pingPayload);
      writeFramedPacket(ch, 0x01, pingBody);

      // 5. Expect PongResponse (packet ID 0x01, same long payload)
      ByteBuf pongFrame = ch.readOutbound();
      assertNotNull(pongFrame, "Expected PongResponse frame");
      VarInt.read(pongFrame); // frame length
      assertEquals(0x01, VarInt.read(pongFrame), "PongResponse packet ID should be 0x01");
      assertEquals(pingPayload, pongFrame.readLong(), "Pong payload should echo ping payload");
      assertFalse(pongFrame.isReadable());
      pongFrame.release();

      // 6. Channel should be closed after pong.
      ch.runPendingTasks();
      assertFalse(ch.isActive(), "Channel should close after pong");
    }
  }

  // ---------------------------------------------------------------------------
  // Handshake edge cases
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("handshake edge cases")
  class HandshakeEdgeCases {

    @Test
    @DisplayName("should transition to LOGIN state on login attempt (nextState=2)")
    void loginAccepted() {
      EmbeddedChannel ch = createFullPipeline();

      writeFramedHandshake(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), "localhost", 25577, 2);

      ch.runPendingTasks();
      assertTrue(ch.isActive(), "Channel should remain open for login");

      // Verify the decoder transitioned to LOGIN state.
      MinecraftDecoder decoder = ch.pipeline().get(MinecraftDecoder.class);
      assertNotNull(decoder);
      assertEquals(ProtocolState.LOGIN, decoder.state());
    }

    @Test
    @DisplayName("should close connection on unknown nextState")
    void unknownNextState() {
      EmbeddedChannel ch = createFullPipeline();

      writeFramedHandshake(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), "localhost", 25577, 99);

      ch.runPendingTasks();
      assertFalse(ch.isActive(), "Channel should close on unknown nextState");
    }

    @Test
    @DisplayName("should handle unknown protocol version gracefully for status")
    void unknownProtocolVersion() {
      EmbeddedChannel ch = createFullPipeline();

      // Protocol 99999 doesn't exist, but status should still work.
      writeFramedHandshake(ch, 99999, "localhost", 25577, 1);
      assertNull(ch.readOutbound());

      // Send StatusRequest — should still get a response.
      writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);
      ByteBuf responseFrame = ch.readOutbound();
      assertNotNull(responseFrame, "Should respond even with unknown protocol version");
      responseFrame.release();

      ch.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // Status edge cases
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("status edge cases")
  class StatusEdgeCases {

    @Test
    @DisplayName("should ignore duplicate StatusRequest")
    void duplicateStatusRequest() {
      EmbeddedChannel ch = createFullPipeline();
      writeFramedHandshake(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), "localhost", 25577, 1);

      // First StatusRequest → response.
      writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);
      ByteBuf first = ch.readOutbound();
      assertNotNull(first);
      first.release();

      // Second StatusRequest → no response.
      writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);
      assertNull(ch.readOutbound(), "Duplicate StatusRequest should be ignored");

      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should close connection on PingRequest without prior StatusRequest")
    void pingWithoutStatus() {
      EmbeddedChannel ch = createFullPipeline();
      writeFramedHandshake(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), "localhost", 25577, 1);

      // Send PingRequest directly without StatusRequest — protocol violation.
      ByteBuf pingBody = Unpooled.buffer(8);
      pingBody.writeLong(42L);
      writeFramedPacket(ch, 0x01, pingBody);

      // No pong response — connection should be closed.
      assertNull(ch.readOutbound(), "Should not respond to PingRequest without StatusRequest");
      ch.runPendingTasks();
      assertFalse(ch.isActive(), "Channel should close on protocol violation");
    }

    @Test
    @DisplayName("should echo different ping payloads correctly")
    void differentPingPayloads() {
      EmbeddedChannel ch = createFullPipeline();
      writeFramedHandshake(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), "localhost", 25577, 1);
      writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);
      ByteBuf statusResp = ch.readOutbound();
      assertNotNull(statusResp);
      statusResp.release();

      // Send ping with zero payload.
      ByteBuf pingBody = Unpooled.buffer(8);
      pingBody.writeLong(0L);
      writeFramedPacket(ch, 0x01, pingBody);

      ByteBuf pongFrame = ch.readOutbound();
      assertNotNull(pongFrame);
      VarInt.read(pongFrame); // length
      VarInt.read(pongFrame); // packet ID
      assertEquals(0L, pongFrame.readLong(), "Should echo zero payload");
      pongFrame.release();

      ch.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // Pipeline setup verification
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("pipeline setup")
  class PipelineSetup {

    @Test
    @DisplayName("should have all expected handlers in the pipeline")
    void handlerNames() {
      EmbeddedChannel ch = createFullPipeline();

      assertNotNull(ch.pipeline().get(ServerChannelInitializer.READ_TIMEOUT));
      assertNotNull(ch.pipeline().get(ServerChannelInitializer.FRAME_DECODER));
      assertNotNull(ch.pipeline().get(ServerChannelInitializer.FRAME_ENCODER));
      assertNotNull(ch.pipeline().get(ServerChannelInitializer.MINECRAFT_DECODER));
      assertNotNull(ch.pipeline().get(ServerChannelInitializer.MINECRAFT_ENCODER));
      assertNotNull(ch.pipeline().get(ServerChannelInitializer.CONNECTION_HANDLER));

      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should start in HANDSHAKE state")
    void initialState() {
      EmbeddedChannel ch = createFullPipeline();

      MinecraftDecoder decoder = ch.pipeline().get(MinecraftDecoder.class);
      MinecraftEncoder encoder = ch.pipeline().get(MinecraftEncoder.class);
      assertNotNull(decoder);
      assertNotNull(encoder);
      assertEquals(ProtocolState.HANDSHAKE, decoder.state());
      assertEquals(ProtocolState.HANDSHAKE, encoder.state());

      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should transition to STATUS state after handshake with nextState=1")
    void transitionToStatus() {
      EmbeddedChannel ch = createFullPipeline();
      writeFramedHandshake(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), "localhost", 25577, 1);

      MinecraftDecoder decoder = ch.pipeline().get(MinecraftDecoder.class);
      MinecraftEncoder encoder = ch.pipeline().get(MinecraftEncoder.class);
      assertNotNull(decoder);
      assertNotNull(encoder);
      assertEquals(ProtocolState.STATUS, decoder.state());
      assertEquals(ProtocolState.STATUS, encoder.state());

      MinecraftConnection conn =
          (MinecraftConnection) ch.pipeline().get(ServerChannelInitializer.CONNECTION_HANDLER);
      assertNotNull(conn);
      assertInstanceOf(StatusSessionHandler.class, conn.sessionHandler());

      ch.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Creates a full Netty pipeline using {@link ServerChannelInitializer}. */
  private static EmbeddedChannel createFullPipeline() {
    ServerChannelInitializer init = new ServerChannelInitializer(TestLoginContexts.offline());
    EmbeddedChannel ch = new EmbeddedChannel();
    // Manually trigger initChannel since EmbeddedChannel doesn't invoke ChannelInitializer
    // the same way as a real ServerBootstrap.
    ch.pipeline().addLast(init);
    // After initChannel runs, the initializer removes itself. Flush pending tasks.
    ch.runPendingTasks();
    return ch;
  }

  /** Writes a framed Handshake packet to the channel's inbound. */
  private static void writeFramedHandshake(
      EmbeddedChannel ch, int protocolVersion, String address, int port, int nextState) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00); // packet ID
    VarInt.write(body, protocolVersion);
    VarInt.write(body, address.length());
    body.writeBytes(address.getBytes(StandardCharsets.UTF_8));
    body.writeShort(port);
    VarInt.write(body, nextState);

    writeFramed(ch, body);
  }

  /** Writes a framed packet with the given packet ID and body to the channel's inbound. */
  private static void writeFramedPacket(EmbeddedChannel ch, int packetId, ByteBuf body) {
    ByteBuf packet = Unpooled.buffer();
    VarInt.write(packet, packetId);
    packet.writeBytes(body);
    body.release();

    writeFramed(ch, packet);
  }

  /** Wraps raw packet bytes in a VarInt-length frame and writes to inbound. */
  private static void writeFramed(EmbeddedChannel ch, ByteBuf packetData) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, packetData.readableBytes());
    frame.writeBytes(packetData);
    packetData.release();

    ch.writeInbound(frame);
  }
}
