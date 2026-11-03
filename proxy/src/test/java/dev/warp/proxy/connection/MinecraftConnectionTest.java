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
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration;
import dev.warp.protocol.packet.cookie.CookieRequest;
import dev.warp.protocol.packet.cookie.StoreCookie;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.PlayPluginMessage;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.status.StatusRequest;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.jspecify.annotations.Nullable;
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
  // Writes across a state change
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("writes across a state change")
  class StateChangeWrites {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

    @Test
    @DisplayName("should write the proxy's own packets at once when no state change is pending")
    void writesAtOnce() {
      EmbeddedChannel ch = createChannel();
      MinecraftConnection conn = getConnection(ch);
      conn.setState(ProtocolState.PLAY);

      conn.writeWhenSettled(new CookieRequest("primemc:session"));

      assertEquals(packetId(ProtocolState.PLAY, CookieRequest.class), nextPacketId(ch));
      assertFalse(conn.isStateChangePending());
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should hold them after a terminal packet, and write them in the next state")
    void holdsUntilStateChange() {
      EmbeddedChannel ch = createChannel();
      MinecraftConnection conn = getConnection(ch);
      conn.setState(ProtocolState.PLAY);

      conn.writeTerminal(new StartConfiguration());
      conn.writeWhenSettled(new CookieRequest("primemc:session"));
      conn.writeWhenSettled(new StoreCookie("primemc:session", new byte[] {1}));
      int startConfiguration = nextPacketId(ch);
      boolean heldWhilePending = ch.outboundMessages().isEmpty();
      conn.setState(ProtocolState.CONFIGURATION);

      assertEquals(packetId(ProtocolState.PLAY, StartConfiguration.class), startConfiguration);
      assertTrue(heldWhilePending);
      assertEquals(packetId(ProtocolState.CONFIGURATION, CookieRequest.class), nextPacketId(ch));
      assertEquals(packetId(ProtocolState.CONFIGURATION, StoreCookie.class), nextPacketId(ch));
      assertFalse(conn.isStateChangePending());
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should drop a held packet that does not exist in the state the peer moved to")
    void dropsWhatTheNextStateLacks() {
      EmbeddedChannel ch = createChannel();
      MinecraftConnection conn = getConnection(ch);
      conn.setState(ProtocolState.PLAY);
      conn.writeTerminal(new StartConfiguration());
      var _ = nextPacketId(ch);
      conn.writeWhenSettled(new StoreCookie("primemc:session", new byte[] {1}));

      try (LogCapture log = new LogCapture(MinecraftConnection.class)) {
        conn.setState(ProtocolState.STATUS);

        assertTrue(ch.outboundMessages().isEmpty());
        assertTrue(ch.isOpen(), "a packet it cannot encode must not fail the connection");
        assertEquals(Level.WARN, assertOne(log.events()).getLevel());
      }
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should forget held packets when the connection closes")
    void forgetsOnClose() {
      EmbeddedChannel ch = createChannel();
      MinecraftConnection conn = getConnection(ch);
      conn.setState(ProtocolState.PLAY);
      conn.writeTerminal(new StartConfiguration());
      var _ = nextPacketId(ch);
      conn.writeWhenSettled(new CookieRequest("primemc:session"));

      ch.close();
      conn.setState(ProtocolState.CONFIGURATION);

      assertTrue(ch.outboundMessages().isEmpty());
      ch.finishAndReleaseAll();
    }

    private static int nextPacketId(EmbeddedChannel ch) {
      ByteBuf packet = ch.readOutbound();
      assertNotNull(packet, "expected a packet");
      try {
        return VarInt.read(packet);
      } finally {
        packet.release();
      }
    }

    private static int packetId(ProtocolState state, Class<? extends Packet> type) {
      return StateRegistry.get(state, PacketDirection.CLIENTBOUND).packetId(VERSION, type);
    }
  }

  @Nested
  @DisplayName("acknowledging a state change the peer announced")
  class AcknowledgedStateChanges {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

    @Test
    @DisplayName(
        "should hand what a handler answers during the acknowledgement, in the new state, to the"
            + " next session handler once active")
    void answersDuringTheAcknowledgement() {
      // A handler in the pipeline that plays the backend, as ViaVersion's configuration bridge
      // for a backend before 1.20.2 does: it takes the acknowledgement and passes up, before the
      // write returns, the play packets it held. Play 0x00 is a Bundle Delimiter; read as
      // configuration, it would be a Cookie Request and fail.
      List<String> events = new ArrayList<>();
      SynchronousPeer peer =
          new SynchronousPeer(
              events, encode(ProtocolState.PLAY, new BundleDelimiter(), new KeepAlive(42)));
      EmbeddedChannel ch = backendChannel(peer);
      MinecraftConnection conn = getConnection(ch);
      RecordingHandler configuration = new RecordingHandler();
      conn.setSessionHandler(configuration);
      OrderedHandler play = new OrderedHandler(events, conn);

      conn.acknowledgeStateChange(new AcknowledgeFinishConfiguration(), ProtocolState.PLAY, play);

      assertEquals(
          List.of(
              "acknowledgement written",
              "activated",
              "handled BundleDelimiter",
              "handled KeepAlive"),
          events);
      assertEquals(List.of(new BundleDelimiter(), new KeepAlive(42)), play.packets);
      assertTrue(configuration.packets().isEmpty());
      assertTrue(configuration.isDeactivated());
      assertTrue(ch.isOpen());
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName(
        "should encode the acknowledgement in the state the peer leaves, and what follows it in the"
            + " new one")
    void encodesAcrossTheChange() {
      List<String> events = new ArrayList<>();
      EmbeddedChannel ch = backendChannel(new SynchronousPeer(events, List.of()));
      MinecraftConnection conn = getConnection(ch);
      OrderedHandler play = new OrderedHandler(events, conn);
      play.writeOnActivation = new PlayPluginMessage("minecraft:register", new byte[] {'a'});

      conn.acknowledgeStateChange(new AcknowledgeFinishConfiguration(), ProtocolState.PLAY, play);

      assertEquals(
          serverboundId(ProtocolState.CONFIGURATION, AcknowledgeFinishConfiguration.class),
          nextPacketId(ch));
      assertEquals(serverboundId(ProtocolState.PLAY, PlayPluginMessage.class), nextPacketId(ch));
      assertEquals(ProtocolState.PLAY, conn.decoder().state());
      assertEquals(ProtocolState.PLAY, conn.encoder().state());
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should read what a server sends after the acknowledgement in the new state")
    void serverAnswersLater() {
      EmbeddedChannel ch = backendChannel(new SynchronousPeer(new ArrayList<>(), List.of()));
      MinecraftConnection conn = getConnection(ch);
      RecordingHandler play = new RecordingHandler();

      conn.acknowledgeStateChange(new AcknowledgeFinishConfiguration(), ProtocolState.PLAY, play);
      for (ByteBuf packet : encode(ProtocolState.PLAY, new BundleDelimiter())) {
        ch.writeInbound(packet);
      }

      assertEquals(List.of(new BundleDelimiter()), play.packets());
      ch.finishAndReleaseAll();
    }

    /** A backend connection: clientbound decoder, serverbound encoder, in CONFIGURATION. */
    private static EmbeddedChannel backendChannel(SynchronousPeer peer) {
      EmbeddedChannel ch =
          new EmbeddedChannel(
              peer,
              new MinecraftDecoder(
                  PacketDirection.CLIENTBOUND, VERSION, ProtocolState.CONFIGURATION),
              new MinecraftEncoder(
                  PacketDirection.SERVERBOUND, VERSION, ProtocolState.CONFIGURATION));
      ch.pipeline().addLast(new MinecraftConnection(ch));
      return ch;
    }

    /** The packets, encoded clientbound in {@code state}, each in its frame (uncompressed). */
    private static List<ByteBuf> encode(ProtocolState state, Packet... packets) {
      EmbeddedChannel encoder =
          new EmbeddedChannel(new MinecraftEncoder(PacketDirection.CLIENTBOUND, VERSION, state));
      List<ByteBuf> encoded = new ArrayList<>();
      for (Packet packet : packets) {
        encoder.writeOutbound(packet);
        ByteBuf body = encoder.readOutbound();
        ByteBuf frame = Unpooled.buffer();
        VarInt.write(frame, body.readableBytes());
        frame.writeBytes(body);
        body.release();
        encoded.add(frame);
      }
      encoder.finishAndReleaseAll();
      return encoded;
    }

    private static int nextPacketId(EmbeddedChannel ch) {
      ByteBuf packet = ch.readOutbound();
      assertNotNull(packet, "expected a packet");
      try {
        return VarInt.read(packet);
      } finally {
        packet.release();
      }
    }

    private static int serverboundId(ProtocolState state, Class<? extends Packet> type) {
      return StateRegistry.get(state, PacketDirection.SERVERBOUND).packetId(VERSION, type);
    }
  }

  /**
   * Plays a peer that answers the first packet written to it before the write returns, by passing
   * {@code answers} up the pipeline; the packet itself goes on.
   */
  private static final class SynchronousPeer extends ChannelDuplexHandler {
    private final List<String> events;
    private final List<ByteBuf> answers;

    SynchronousPeer(List<String> events, List<ByteBuf> answers) {
      this.events = events;
      this.answers = new ArrayList<>(answers);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
      if (events.isEmpty()) {
        events.add("acknowledgement written");
        for (ByteBuf answer : answers) {
          ctx.fireChannelRead(answer);
        }
        answers.clear();
      }
      ctx.write(msg, promise);
    }
  }

  /** A session handler that records when it is activated and what it handles, in order. */
  private static final class OrderedHandler implements SessionHandler {
    private final List<String> events;
    private final MinecraftConnection connection;
    private final List<Packet> packets = new ArrayList<>();
    private @Nullable Packet writeOnActivation;

    OrderedHandler(List<String> events, MinecraftConnection connection) {
      this.events = events;
      this.connection = connection;
    }

    @Override
    public void activated() {
      events.add("activated");
      if (writeOnActivation != null) {
        connection.writeAndFlush(writeOnActivation);
      }
    }

    @Override
    public void handle(Packet packet) {
      events.add("handled " + packet.getClass().getSimpleName());
      packets.add(packet);
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

    @Test
    @DisplayName("should log a read time-out as one line at INFO, and close the connection")
    void readTimeout() {
      EmbeddedChannel ch = createChannel();
      try (LogCapture log = new LogCapture(MinecraftConnection.class)) {
        ch.pipeline().fireExceptionCaught(ReadTimeoutException.INSTANCE);

        LogEvent event = assertOne(log.events());
        assertEquals(Level.INFO, event.getLevel());
        assertEquals(
            "Connection embedded timed out in STATUS: the client sent nothing for 30 s (reads on, 0"
                + " bytes queued to send)",
            event.getMessage().getFormattedMessage());
        assertNull(event.getThrown(), "no stack trace for a quiet peer");
        assertFalse(ch.isOpen());
      } finally {
        ch.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should still log a lost connection at DEBUG and a bug at ERROR with its trace")
    void otherLevels() {
      EmbeddedChannel lost = createChannel();
      EmbeddedChannel faulty = createChannel();
      try (LogCapture log = new LogCapture(MinecraftConnection.class)) {
        IllegalStateException bug = new IllegalStateException("bug");

        lost.pipeline().fireExceptionCaught(new IOException("Connection reset by peer"));
        faulty.pipeline().fireExceptionCaught(bug);

        List<LogEvent> events = log.events();
        assertEquals(
            List.of(Level.DEBUG, Level.ERROR), events.stream().map(LogEvent::getLevel).toList());
        assertEquals(bug, events.get(1).getThrown());
      } finally {
        lost.finishAndReleaseAll();
        faulty.finishAndReleaseAll();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Read time-out description
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("read time-out description")
  class TimeoutDescription {

    private static MinecraftDecoder decoder(PacketDirection direction, ProtocolState state) {
      return new MinecraftDecoder(direction, ProtocolVersion.MINECRAFT_1_21_4, state);
    }

    @Test
    @DisplayName("should name a quiet backend, its state, and that the proxy had paused reading it")
    void readsPaused() {
      EmbeddedChannel ch = new EmbeddedChannel();
      try {
        ch.config().setAutoRead(false);

        assertEquals(
            "in PLAY: the backend sent nothing for 30 s (reads paused, 0 bytes queued to send)",
            MinecraftConnection.describeTimeout(
                ch, decoder(PacketDirection.CLIENTBOUND, ProtocolState.PLAY)));
      } finally {
        ch.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should count the bytes the peer has not taken yet")
    void queuedBytes() {
      EmbeddedChannel ch = new EmbeddedChannel();
      try {
        // Written but not flushed: still in the channel's outbound buffer.
        var _ = ch.write(Unpooled.wrappedBuffer(new byte[1500]));

        String description =
            MinecraftConnection.describeTimeout(
                ch, decoder(PacketDirection.SERVERBOUND, ProtocolState.LOGIN));

        // The packet, and Netty's bookkeeping per queued message.
        long queued = ch.unsafe().outboundBuffer().totalPendingWriteBytes();
        assertTrue(queued >= 1500 && queued < 1500 + 256, "queued " + queued);
        assertEquals(
            "in LOGIN: the client sent nothing for 30 s (reads on, "
                + queued
                + " bytes queued to"
                + " send)",
            description);
      } finally {
        ch.finishAndReleaseAll();
      }
    }
  }

  private static LogEvent assertOne(List<LogEvent> events) {
    assertEquals(1, events.size(), () -> "events: " + events);
    return events.getFirst();
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
