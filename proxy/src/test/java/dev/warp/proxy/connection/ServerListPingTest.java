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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.event.EventBus;
import dev.warp.api.event.Subscribe;
import dev.warp.api.event.Subscription;
import dev.warp.api.event.proxy.ProxyPingEvent;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.event.proxy.ProxyPingEventImpl;
import dev.warp.proxy.status.ServerList;
import dev.warp.proxy.status.StatusSettings;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
      assertTrue(json.contains("\"protocol\":" + ProtocolVersion.MINECRAFT_1_21_4.protocol()));

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
  // Advertised version
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("advertised version")
  class AdvertisedVersion {

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedVersions")
    @DisplayName("should advertise the client's own protocol when Warp supports it")
    void clientProtocol(ProtocolVersion version) {
      EmbeddedChannel ch = createFullPipeline();

      JsonObject advertised = requestStatusVersion(ch, version.protocol());

      assertEquals(version.protocol(), advertised.get("protocol").getAsInt());
      assertEquals(StatusSettings.defaultVersionName(), advertised.get("name").getAsString());
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should advertise the latest protocol and the supported range to another client")
    void unsupportedClient() {
      EmbeddedChannel ch = createFullPipeline();

      JsonObject advertised = requestStatusVersion(ch, 99999);

      assertEquals(ProtocolVersion.latest().protocol(), advertised.get("protocol").getAsInt());
      assertEquals(StatusSettings.defaultVersionName(), advertised.get("name").getAsString());
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should name the range of supported versions, from 1.7.2 to the latest")
    void versionName() {
      String name = StatusSettings.defaultVersionName();

      assertEquals("Warp 1.7.2-" + ProtocolVersion.latest().name(), name);
    }

    @Test
    @DisplayName("should reuse the answer of a protocol while the players do not change")
    void cachedJson() {
      ServerList serverList = TestLoginContexts.offline().serverList();
      String first = serverList.json(ProtocolVersion.MINECRAFT_1_20_5.protocol());

      String again = serverList.json(ProtocolVersion.MINECRAFT_1_20_6.protocol()); // also 766

      assertSame(first, again);
    }

    /** One version per protocol Warp supports. */
    static Stream<ProtocolVersion> supportedVersions() {
      return ProtocolVersion.values().stream()
          .filter(v -> Objects.equals(ProtocolVersion.byProtocolId(v.protocol()), v));
    }

    /** Sends a handshake and a status request, and returns the "version" of the answer. */
    private JsonObject requestStatusVersion(EmbeddedChannel ch, int protocol) {
      writeFramedHandshake(ch, protocol, "localhost", 25577, 1);
      writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);
      ByteBuf responseFrame = ch.readOutbound();
      assertNotNull(responseFrame, "Expected StatusResponse frame");
      try {
        VarInt.read(responseFrame); // frame length
        assertEquals(0x00, VarInt.read(responseFrame), "StatusResponse packet ID should be 0x00");
        String json = McString.read(responseFrame);
        assertFalse(responseFrame.isReadable(), "StatusResponse should consume all bytes");
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("version");
      } finally {
        responseFrame.release();
      }
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
      assertNotNull(ch.pipeline().get(ServerChannelInitializer.LEGACY_PING_DECODER));
      assertNotNull(ch.pipeline().get(ServerChannelInitializer.LEGACY_PING_ENCODER));
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
  // Configured entry
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("configured entry")
  class ConfiguredEntry {

    @Test
    @DisplayName("should answer with the configured message, limit and live player count")
    void configured() {
      StatusSettings settings =
          new StatusSettings(
              Component.text("Hello", NamedTextColor.GOLD), null, 42, true, 12, "Custom");
      EmbeddedChannel ch =
          createFullPipeline(TestLoginContexts.offline(TestLoginContexts.EVENTS, settings));

      JsonObject status = requestStatus(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol());

      assertEquals("Hello", status.getAsJsonObject("description").get("text").getAsString());
      assertEquals("gold", status.getAsJsonObject("description").get("color").getAsString());
      assertEquals(42, status.getAsJsonObject("players").get("max").getAsInt());
      assertEquals(0, status.getAsJsonObject("players").get("online").getAsInt());
      assertEquals("Custom", status.getAsJsonObject("version").get("name").getAsString());
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should answer a ping that arrives before the entry right after it")
    void pingWhileAnswering() {
      WarpEventBus bus = new WarpEventBus(Runnable::run);
      EventBus events = new PluginEventBus(bus, "test", null, s -> {});
      CompletableFuture<Void> hold = new CompletableFuture<>();
      events.subscribeAsync(ProxyPingEvent.class, Subscribe.NORMAL, 30, event -> hold);
      EmbeddedChannel ch = createFullPipeline(TestLoginContexts.offline(bus));
      try {
        writeFramedHandshake(
            ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), "localhost", 25577, 1);
        writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);
        ByteBuf ping = Unpooled.buffer(8);
        ping.writeLong(7L);
        writeFramedPacket(ch, 0x01, ping);
        assertNull(ch.readOutbound(), "nothing before the handlers are done");

        hold.complete(null);
        ch.runPendingTasks();

        assertNotNull(readStatusJson(ch));
        ByteBuf pong = ch.readOutbound();
        assertNotNull(pong, "the pong follows the entry");
        VarInt.read(pong);
        assertEquals(0x01, VarInt.read(pong));
        assertEquals(7L, pong.readLong());
        pong.release();
      } finally {
        ch.finishAndReleaseAll();
        bus.close();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // ProxyPingEvent
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ProxyPingEvent")
  class PingEvents {

    private final WarpEventBus bus = new WarpEventBus(Runnable::run);
    private final EventBus events = new PluginEventBus(bus, "test", null, s -> {});
    private final ServerLoginContext context = TestLoginContexts.offline(bus);

    @AfterEach
    void closeBus() {
      bus.close();
    }

    @Test
    @DisplayName("should answer with the entry a handler set, seeing the proposal and connection")
    void replaced() {
      List<ProxyPingEvent> fired = new ArrayList<>();
      events.subscribe(
          ProxyPingEvent.class,
          event -> {
            fired.add(event);
            event.setPing(event.ping().withDescription(Component.text("Maintenance")));
          });
      EmbeddedChannel ch = createFullPipeline(context);

      JsonObject status = requestStatus(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol());

      assertEquals("Maintenance", plainDescription(status));
      ProxyPingEvent event = fired.getFirst();
      assertEquals(
          ProtocolVersion.MINECRAFT_1_21_4.protocol(), event.connection().protocolVersion());
      assertEquals("play.example.net", event.connection().virtualHost().orElseThrow());
      assertEquals(CLIENT_ADDRESS, event.connection().remoteAddress());
      assertEquals(500, event.ping().playerInfo().orElseThrow().max());
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should report the protocol of a client Warp does not support, as it announced it")
    void unsupportedProtocol() {
      List<ProxyPingEvent> fired = new ArrayList<>();
      events.subscribe(ProxyPingEvent.class, fired::add);
      EmbeddedChannel ch = createFullPipeline(context);

      JsonObject status = requestStatus(ch, 99999);

      assertEquals(99999, fired.getFirst().connection().protocolVersion());
      assertEquals(
          ProtocolVersion.latest().protocol(), fired.getFirst().ping().version().protocol());
      assertEquals(
          ProtocolVersion.latest().protocol(),
          status.getAsJsonObject("version").get("protocol").getAsInt());
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should wait for an asynchronous handler before answering")
    void async() {
      CompletableFuture<Void> lookup = new CompletableFuture<>();
      events.subscribeAsync(
          ProxyPingEvent.class,
          Subscribe.NORMAL,
          30,
          event ->
              lookup.thenRun(
                  () -> event.setPing(event.ping().withDescription(Component.text("Later")))));
      EmbeddedChannel ch = createFullPipeline(context);
      writeFramedHandshake(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), "localhost", 25577, 1);
      writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);
      ch.runPendingTasks();
      assertNull(ch.readOutbound(), "no answer while the handler holds the ping");

      lookup.complete(null);
      ch.runPendingTasks();

      assertEquals(
          "Later", plainDescription(JsonParser.parseString(readStatusJson(ch)).getAsJsonObject()));
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should close without answering when a handler chooses not to answer")
    void ignored() {
      events.subscribe(ProxyPingEvent.class, event -> event.setResult(new ProxyPingEvent.Ignore()));
      EmbeddedChannel ch = createFullPipeline(context);
      writeFramedHandshake(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), "localhost", 25577, 1);

      writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);
      ch.runPendingTasks();

      assertNull(ch.readOutbound());
      assertFalse(ch.isOpen());
    }

    @Test
    @DisplayName("should answer from the cached entry, building no event, when nobody listens")
    void notFiredWithoutHandlers() {
      Subscription subscription = events.subscribe(ProxyPingEvent.class, event -> {});
      subscription.unsubscribe();
      EmbeddedChannel ch = createFullPipeline(context);
      int protocol = ProtocolVersion.MINECRAFT_1_21_4.protocol();
      String cached = context.serverList().json(protocol);

      writeFramedHandshake(ch, protocol, "localhost", 25577, 1);
      writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);

      assertEquals(cached, readStatusJson(ch));
      assertFalse(bus.hasHandlers(ProxyPingEventImpl.class));
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should ignore and log a change made after the answer")
    void lateChange() {
      List<ProxyPingEvent> fired = new ArrayList<>();
      events.subscribe(ProxyPingEvent.class, fired::add);
      EmbeddedChannel ch = createFullPipeline(context);
      requestStatus(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol());

      try (LogCapture log = new LogCapture(ProxyPingEventImpl.class)) {
        ProxyPingEvent event = fired.getFirst();
        event.setPing(event.ping().withDescription(Component.text("Too late")));

        assertNotEquals("Too late", plainText(event.ping().description()));
        assertFalse(log.events().isEmpty(), "the late change is logged");
      }
      ch.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // Legacy ping
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("legacy ping (1.6 and older)")
  class LegacyPings {

    @Test
    @DisplayName("should answer a lone 0xFE, as Minecraft 1.3 and health checks send it")
    void minecraft13() {
      EmbeddedChannel ch = createFullPipeline();

      ch.writeInbound(Unpooled.wrappedBuffer(new byte[] {(byte) 0xFE}));
      waitForPingFragment(ch);

      assertEquals("A Warp Proxy§0§500", readLegacyKick(ch));
      ch.runPendingTasks();
      assertFalse(ch.isOpen(), "closed after the answer");
    }

    @Test
    @DisplayName("should answer 0xFE 0x01 with the protocol vanilla announces, 127")
    void minecraft14() {
      EmbeddedChannel ch = createFullPipeline();

      ch.writeInbound(Unpooled.wrappedBuffer(new byte[] {(byte) 0xFE, 0x01}));
      waitForPingFragment(ch);

      assertEquals(
          "§1\u0000127\u0000"
              + StatusSettings.defaultVersionName()
              + "\u0000§bA Warp Proxy\u00000\u0000500",
          readLegacyKick(ch));
    }

    @Test
    @DisplayName("should fire the ping event with the host a 1.6 ping carries and protocol -1")
    void minecraft16() {
      WarpEventBus bus = new WarpEventBus(Runnable::run);
      EventBus events = new PluginEventBus(bus, "test", null, s -> {});
      List<ProxyPingEvent> fired = new ArrayList<>();
      events.subscribe(
          ProxyPingEvent.class,
          event -> {
            fired.add(event);
            event.setPing(event.ping().withDescription(Component.text("Legacy")));
          });
      EmbeddedChannel ch = createFullPipeline(TestLoginContexts.offline(bus));
      try {
        ch.writeInbound(Unpooled.wrappedBuffer(pingHost("mc.example.net", 25565)));
        ch.runPendingTasks();

        assertTrue(readLegacyKick(ch).contains("\u0000Legacy\u0000"));
        assertEquals(-1, fired.getFirst().connection().protocolVersion());
        assertEquals("mc.example.net", fired.getFirst().connection().virtualHost().orElseThrow());
      } finally {
        ch.finishAndReleaseAll();
        bus.close();
      }
    }

    @Test
    @DisplayName("should tell a 1.6 client trying to join which versions Warp supports")
    void legacyLogin() {
      EmbeddedChannel ch = createFullPipeline();

      ch.writeInbound(Unpooled.wrappedBuffer(new byte[] {0x02, 0x4E, 0x00, 0x05}));

      assertEquals(HandshakeSessionHandler.OUTDATED_CLIENT, readLegacyKick(ch));
    }

    @Test
    @DisplayName("should leave the pipeline once a connection speaks the framed protocol")
    void leavesModernConnections() {
      EmbeddedChannel ch = createFullPipeline();

      writeFramedHandshake(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), "localhost", 25577, 1);

      assertNull(ch.pipeline().get(ServerChannelInitializer.LEGACY_PING_DECODER));
      assertNull(ch.pipeline().get(ServerChannelInitializer.LEGACY_PING_ENCODER));
      ch.finishAndReleaseAll();
    }

    @Test
    @DisplayName("should not mistake a 254-byte handshake, whose length starts with 0xFE, for one")
    void longHandshake() {
      EmbeddedChannel ch = createFullPipeline();
      // A handshake of 254 bytes: its length prefix is 0xFE 0x01, then its packet id 0x00.
      String host = "h".repeat(254 - 1 - 2 - 2 - 2 - 1);

      writeFramedHandshake(ch, ProtocolVersion.MINECRAFT_1_21_4.protocol(), host, 25577, 1);
      writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);

      assertNotNull(readStatusJson(ch));
      ch.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** The address the client channels connect from. */
  private static final InetSocketAddress CLIENT_ADDRESS =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 50_000);

  /** Creates a full Netty pipeline using {@link ServerChannelInitializer}. */
  private static EmbeddedChannel createFullPipeline() {
    return createFullPipeline(TestLoginContexts.offline());
  }

  /** Creates a full Netty pipeline for a proxy configured by {@code context}. */
  private static EmbeddedChannel createFullPipeline(ServerLoginContext context) {
    ServerChannelInitializer init = new ServerChannelInitializer(context);
    EmbeddedChannel ch =
        new EmbeddedChannel() {
          @Override
          protected SocketAddress remoteAddress0() {
            return CLIENT_ADDRESS;
          }
        };
    // Manually trigger initChannel since EmbeddedChannel doesn't invoke ChannelInitializer
    // the same way as a real ServerBootstrap.
    ch.pipeline().addLast(init);
    // After initChannel runs, the initializer removes itself. Flush pending tasks.
    ch.runPendingTasks();
    return ch;
  }

  /** Lets the wait for the rest of a ping of one or two bytes elapse, as if nothing followed. */
  private static void waitForPingFragment(EmbeddedChannel ch) {
    ch.advanceTimeBy(100, TimeUnit.MILLISECONDS);
    ch.runScheduledPendingTasks();
  }

  /** Sends a handshake to play.example.net and a status request, and returns the answer. */
  private static JsonObject requestStatus(EmbeddedChannel ch, int protocol) {
    writeFramedHandshake(ch, protocol, "play.example.net", 25577, 1);
    writeFramedPacket(ch, 0x00, Unpooled.EMPTY_BUFFER);
    ch.runPendingTasks();
    return JsonParser.parseString(readStatusJson(ch)).getAsJsonObject();
  }

  /** Reads the next frame, which must be a status response, and returns its JSON. */
  private static String readStatusJson(EmbeddedChannel ch) {
    ByteBuf frame = ch.readOutbound();
    assertNotNull(frame, "Expected StatusResponse frame");
    try {
      VarInt.read(frame); // frame length
      assertEquals(0x00, VarInt.read(frame), "StatusResponse packet ID should be 0x00");
      String json = McString.read(frame);
      assertFalse(frame.isReadable(), "StatusResponse should consume all bytes");
      return json;
    } finally {
      frame.release();
    }
  }

  /** Reads the next write, which must be a legacy kick packet, and returns its text. */
  private static String readLegacyKick(EmbeddedChannel ch) {
    ByteBuf kick = ch.readOutbound();
    assertNotNull(kick, "Expected a legacy kick packet");
    try {
      assertEquals(0xFF, kick.readUnsignedByte(), "legacy kick packet id");
      int length = kick.readUnsignedShort();
      assertEquals(length * 2, kick.readableBytes(), "UTF-16BE text of the announced length");
      return kick.toString(StandardCharsets.UTF_16BE);
    } finally {
      kick.release();
    }
  }

  /** A 1.6 legacy ping: 0xFE 0x01, then an MC|PingHost plugin message. */
  private static byte[] pingHost(String host, int port) {
    ByteBuf buf = Unpooled.buffer();
    buf.writeByte(0xFE).writeByte(0x01).writeByte(0xFA);
    String channel = "MC|PingHost";
    buf.writeShort(channel.length()).writeCharSequence(channel, StandardCharsets.UTF_16BE);
    buf.writeShort(7 + host.length() * 2);
    buf.writeByte(78); // 1.6.4
    buf.writeShort(host.length()).writeCharSequence(host, StandardCharsets.UTF_16BE);
    buf.writeInt(port);
    byte[] bytes = ByteBufUtil.getBytes(buf);
    buf.release();
    return bytes;
  }

  /** The plain text of a status answer's description. */
  private static String plainDescription(JsonObject status) {
    return plainText(GsonComponentSerializer.gson().deserializeFromTree(status.get("description")));
  }

  private static String plainText(Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
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
