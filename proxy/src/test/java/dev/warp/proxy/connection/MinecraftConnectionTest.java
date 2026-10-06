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
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.status.StatusRequest;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MinecraftConnection")
class MinecraftConnectionTest {

  /**
   * Creates a channel with decoder, encoder, and the given connection. The connection wraps the
   * same channel, so pipeline lookups from {@code connection.decoder()} work correctly.
   */
  private static EmbeddedChannel createChannel() {
    EmbeddedChannel ch =
        new EmbeddedChannel(
            new MinecraftDecoder(
                PacketDirection.SERVERBOUND,
                ProtocolVersion.MINECRAFT_1_21_4,
                ProtocolState.STATUS),
            new MinecraftEncoder(
                PacketDirection.CLIENTBOUND,
                ProtocolVersion.MINECRAFT_1_21_4,
                ProtocolState.STATUS));
    MinecraftConnection conn = new MinecraftConnection(ch);
    ch.pipeline().addLast(conn);
    return ch;
  }

  private static MinecraftConnection getConnection(EmbeddedChannel ch) {
    return ch.pipeline().get(MinecraftConnection.class);
  }

  // ---------------------------------------------------------------------------
  // Packet dispatch
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("packet dispatch")
  class PacketDispatch {

    @Test
    @DisplayName("should dispatch decoded Packet to session handler")
    void dispatchPacket() {
      EmbeddedChannel ch = createChannel();
      MinecraftConnection conn = getConnection(ch);
      RecordingHandler handler = new RecordingHandler();
      conn.setSessionHandler(handler);

      StatusRequest request = new StatusRequest();
      ch.pipeline().fireChannelRead(request);

      assertEquals(1, handler.packets().size());
      assertInstanceOf(StatusRequest.class, handler.packets().get(0));
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should dispatch raw ByteBuf to handleBlind")
    void dispatchBlind() {
      // Use a bare channel (no decoder) so the ByteBuf reaches MinecraftConnection directly.
      EmbeddedChannel ch = new EmbeddedChannel();
      MinecraftConnection conn = new MinecraftConnection(ch);
      ch.pipeline().addLast(conn);

      RecordingHandler handler = new RecordingHandler();
      conn.setSessionHandler(handler);

      ByteBuf buf = Unpooled.buffer().writeByte(0xFF);
      ch.pipeline().fireChannelRead(buf);

      assertEquals(1, handler.blindBuffers().size());
      // Handler should have received and released it.
      assertEquals(0, handler.blindBuffers().get(0).refCnt());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should release ByteBuf when no session handler is set")
    void releaseWhenNoHandler() {
      EmbeddedChannel ch = new EmbeddedChannel();
      MinecraftConnection conn = new MinecraftConnection(ch);
      ch.pipeline().addLast(conn);

      ByteBuf buf = Unpooled.buffer().writeByte(0xAA);
      ch.pipeline().fireChannelRead(buf);

      assertEquals(0, buf.refCnt());
      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Session handler lifecycle
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("session handler lifecycle")
  class Lifecycle {

    @Test
    @DisplayName("should call activated() when session handler is set")
    void activated() {
      EmbeddedChannel ch = createChannel();
      MinecraftConnection conn = getConnection(ch);

      RecordingHandler handler = new RecordingHandler();
      conn.setSessionHandler(handler);

      assertTrue(handler.isActivated());
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should call deactivated() on old handler when replacing")
    void deactivatedOnReplace() {
      EmbeddedChannel ch = createChannel();
      MinecraftConnection conn = getConnection(ch);

      RecordingHandler first = new RecordingHandler();
      RecordingHandler second = new RecordingHandler();

      conn.setSessionHandler(first);
      assertFalse(first.isDeactivated());
      assertFalse(first.isDisconnected());

      conn.setSessionHandler(second);
      assertTrue(first.isDeactivated(), "Old handler should be notified of deactivation");
      assertFalse(first.isDisconnected(), "Old handler should NOT be notified of disconnect");
      assertTrue(second.isActivated(), "New handler should be activated");
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should call disconnected() when channel becomes inactive")
    void disconnectedOnClose() {
      EmbeddedChannel ch = createChannel();
      MinecraftConnection conn = getConnection(ch);

      RecordingHandler handler = new RecordingHandler();
      conn.setSessionHandler(handler);

      ch.close().awaitUninterruptibly();
      assertTrue(handler.isDisconnected());
    }
  }

  // ---------------------------------------------------------------------------
  // Protocol state management
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("protocol state management")
  class StateManagement {

    @Test
    @DisplayName("should update decoder and encoder state together")
    void setState() {
      EmbeddedChannel ch = createChannel();
      MinecraftConnection conn = getConnection(ch);

      conn.setState(ProtocolState.PLAY);

      MinecraftDecoder decoder = ch.pipeline().get(MinecraftDecoder.class);
      MinecraftEncoder encoder = ch.pipeline().get(MinecraftEncoder.class);
      assertNotNull(decoder);
      assertNotNull(encoder);
      assertEquals(ProtocolState.PLAY, decoder.state());
      assertEquals(ProtocolState.PLAY, encoder.state());
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should update decoder and encoder version together")
    void setVersion() {
      EmbeddedChannel ch = createChannel();
      MinecraftConnection conn = getConnection(ch);

      conn.setVersion(ProtocolVersion.MINECRAFT_1_8);

      MinecraftDecoder decoder = ch.pipeline().get(MinecraftDecoder.class);
      MinecraftEncoder encoder = ch.pipeline().get(MinecraftEncoder.class);
      assertNotNull(decoder);
      assertNotNull(encoder);
      assertEquals(ProtocolVersion.MINECRAFT_1_8, decoder.version());
      assertEquals(ProtocolVersion.MINECRAFT_1_8, encoder.version());
      ch.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // Pipeline exceptions
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("pipeline exceptions")
  class PipelineExceptions {

    @Test
    @DisplayName("should treat a write racing with a closing peer as routine")
    void closedChannel() {
      assertNotNull(MinecraftConnection.describeRoutine(new ClosedChannelException(), false));
    }

    @Test
    @DisplayName("should treat a vanished peer as routine")
    void connectionReset() {
      assertNotNull(
          MinecraftConnection.describeRoutine(new IOException("Connection reset by peer"), true));
    }

    @Test
    @DisplayName("should treat a read before the connection completes as routine")
    void readBeforeConnect() {
      assertNotNull(MinecraftConnection.describeRoutine(new NotYetConnectedException(), false));
    }

    @Test
    @DisplayName("should report a not-connected error on a connected channel")
    void notConnectedWhileActive() {
      assertNull(MinecraftConnection.describeRoutine(new NotYetConnectedException(), true));
    }

    @Test
    @DisplayName("should report any other exception, whatever the channel state")
    void otherExceptions() {
      assertNull(MinecraftConnection.describeRoutine(new IllegalStateException("bug"), false));
      assertNull(MinecraftConnection.describeRoutine(new DecoderException("bad packet"), true));
    }
  }

  // ---------------------------------------------------------------------------
  // Test helper — recording session handler
  // ---------------------------------------------------------------------------

  private static final class RecordingHandler implements SessionHandler {
    private final List<Packet> packets = new ArrayList<>();
    private final List<ByteBuf> blindBuffers = new ArrayList<>();
    private boolean activated;
    private boolean deactivated;
    private boolean disconnected;

    @Override
    public void handle(Packet packet) {
      packets.add(packet);
    }

    @Override
    public void handleBlind(ByteBuf buf) {
      blindBuffers.add(buf);
      buf.release();
    }

    @Override
    public void activated() {
      activated = true;
    }

    @Override
    public void deactivated() {
      deactivated = true;
    }

    @Override
    public void disconnected() {
      disconnected = true;
    }

    List<Packet> packets() {
      return packets;
    }

    List<ByteBuf> blindBuffers() {
      return blindBuffers;
    }

    boolean isActivated() {
      return activated;
    }

    boolean isDeactivated() {
      return deactivated;
    }

    boolean isDisconnected() {
      return disconnected;
    }
  }
}
