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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameEncoder;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.KnownPacks;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.config.TimeoutsConfig;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.metrics.MetricRegistry;
import dev.warp.proxy.metrics.ProxyMetrics;
import dev.warp.proxy.permission.PermissionRegistry;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A server's Select Known Packs (1.20.5+) during the configuration phase the virtual world asked
 * the client the same question in, and skipped itself on the answer: the proxy answers the server
 * with what the client said, and the client, which may answer once per phase, never sees the second
 * offer. Without such an answer the offer goes on to the client as before.
 */
@DisplayName("a server's known packs after the world asked")
class KnownPacksAnswerTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;
  private static final KnownPacks.Pack CORE = new KnownPacks.Pack("minecraft", "core", "1.21.4");
  private static final KnownPacks.Pack OTHER = new KnownPacks.Pack("minecraft", "core", "1.21.9");
  private static final KnownPacks.Pack DATA = new KnownPacks.Pack("warp", "e2e", "1");

  private EmbeddedChannel clientChannel;
  private EmbeddedChannel backendChannel;
  private ConnectedPlayer player;

  @BeforeEach
  void setUp() {
    clientChannel = channel(PacketDirection.SERVERBOUND);
    backendChannel = channel(PacketDirection.CLIENTBOUND);
    MinecraftConnection clientConn = connection(clientChannel);
    MinecraftConnection backendConn = connection(backendChannel);

    GameProfile profile = new GameProfile(UUID.randomUUID(), "Via", List.of());
    ServerLoginContext loginContext = mock(ServerLoginContext.class);
    when(loginContext.metrics()).thenReturn(new ProxyMetrics(new MetricRegistry(0)));
    when(loginContext.messages()).thenReturn(dev.warp.proxy.i18n.PlayerMessages.bundled());
    when(loginContext.logging()).thenReturn(LoggingConfig.DEFAULTS);
    when(loginContext.timeouts()).thenReturn(TimeoutsConfig.DEFAULTS);
    when(loginContext.antiBot()).thenReturn(TestLoginContexts.calmAntiBot());
    when(loginContext.playerRegistry()).thenReturn(new PlayerRegistry());
    when(loginContext.events()).thenReturn(TestLoginContexts.EVENTS);
    when(loginContext.channels()).thenReturn(new ChannelRegistry());
    when(loginContext.permissions()).thenReturn(new PermissionRegistry());
    when(loginContext.commands())
        .thenReturn(TestLoginContexts.commands(new ServerRegistry(Map.of(), null, List.of())));
    when(loginContext.serverRegistry())
        .thenReturn(
            new ServerRegistry(
                Map.of("lobby", new InetSocketAddress("localhost", 25565)), "lobby", List.of()));
    player =
        new ConnectedPlayer(
            clientConn,
            VERSION,
            profile,
            new InetSocketAddress("127.0.0.1", 25565),
            loginContext,
            new ClientCookies(clientConn, VERSION, clientChannel.eventLoop()));
    player.setBackendConnection(
        new BackendConnection(
            backendConn, new ServerInfo("lobby", new InetSocketAddress("localhost", 25565))));
    backendConn.setSessionHandler(new BackendConfigSessionHandler(player, backendConn));
    clientChannel.runPendingTasks();
    drain(clientChannel);
    drain(backendChannel);
  }

  @AfterEach
  void tearDown() {
    clientChannel.finishAndReleaseAll();
    backendChannel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("should answer the server with what the client knows of its offer")
  void answeredForTheClient() {
    player.worldKnownPacksAnswered(new KnownPacks(List.of(OTHER)));

    offer(List.of(CORE, OTHER, DATA));

    assertEquals(new KnownPacks(List.of(OTHER)), answer());
    assertNull(clientChannel.readOutbound(), "the client never sees the second offer");
    assertEquals(
        new KnownPacks(List.of(OTHER)),
        player.worldKnownPacks(),
        "kept for the phase: a fallback server asks again");
  }

  @Test
  @DisplayName("should forget the answer once the client leaves the configuration phase")
  void forgottenInPlay() {
    player.worldKnownPacksAnswered(new KnownPacks(List.of(OTHER)));

    player.clientStateChanged(ProtocolState.PLAY);

    assertNull(player.worldKnownPacks());
  }

  @Test
  @DisplayName("should answer with nothing when the client knew no pack of the world's offer")
  void answeredEmpty() {
    player.worldKnownPacksAnswered(new KnownPacks(List.of()));

    offer(List.of(CORE));

    assertEquals(new KnownPacks(List.of()), answer());
    assertNull(clientChannel.readOutbound());
  }

  @Test
  @DisplayName("should pass the offer on to a client that answers servers itself")
  void forwarded() {
    offer(List.of(CORE, DATA));

    assertNull(answer(), "nothing answered for the client");
    ByteBuf frame = clientChannel.readOutbound();
    assertNotNull(frame, "the offer reaches the client");
    try {
      VarInt.read(frame);
      int id = VarInt.read(frame);
      assertEquals(
          StateRegistry.get(ProtocolState.CONFIGURATION, PacketDirection.CLIENTBOUND)
              .packetId(VERSION, KnownPacks.class),
          id);
      assertEquals(new KnownPacks(List.of(CORE, DATA)), KnownPacks.CODEC.decode(frame, VERSION));
    } finally {
      frame.release();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** The server offers packs: a frame as the backend sends it, read by the backend's decoder. */
  private void offer(List<KnownPacks.Pack> packs) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(
        body,
        StateRegistry.get(ProtocolState.CONFIGURATION, PacketDirection.CLIENTBOUND)
            .packetId(VERSION, KnownPacks.class));
    KnownPacks.CODEC.encode(new KnownPacks(packs), body, VERSION);
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    backendChannel.writeInbound(frame);
    backendChannel.runPendingTasks();
    clientChannel.runPendingTasks();
    backendChannel.flushOutbound();
    clientChannel.flushOutbound();
  }

  /** What the proxy wrote to the server as the client's answer, if anything. */
  private @Nullable KnownPacks answer() {
    int answerId =
        StateRegistry.world(ProtocolState.CONFIGURATION).packetId(VERSION, KnownPacks.class);
    KnownPacks found = null;
    for (ByteBuf frame = backendChannel.readOutbound();
        frame != null;
        frame = backendChannel.readOutbound()) {
      try {
        VarInt.read(frame);
        if (VarInt.read(frame) == answerId) {
          found = KnownPacks.CODEC.decode(frame, VERSION);
        }
      } finally {
        frame.release();
      }
    }
    return found;
  }

  private static void drain(EmbeddedChannel channel) {
    for (ByteBuf frame = channel.readOutbound(); frame != null; frame = channel.readOutbound()) {
      frame.release();
    }
  }

  private static EmbeddedChannel channel(PacketDirection direction) {
    MinecraftDecoder decoder =
        new MinecraftDecoder(direction, VERSION, ProtocolState.CONFIGURATION);
    MinecraftEncoder encoder =
        new MinecraftEncoder(
            direction == PacketDirection.SERVERBOUND
                ? PacketDirection.CLIENTBOUND
                : PacketDirection.SERVERBOUND,
            VERSION,
            ProtocolState.CONFIGURATION);
    EmbeddedChannel channel = new EmbeddedChannel();
    MinecraftConnection connection = new MinecraftConnection(channel);
    channel
        .pipeline()
        .addLast(
            ServerChannelInitializer.READ_TIMEOUT, new ReadTimeoutHandler(30, TimeUnit.SECONDS))
        .addLast(ServerChannelInitializer.FRAME_DECODER, new FrameDecoder())
        .addLast(ServerChannelInitializer.MINECRAFT_DECODER, decoder)
        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(ServerChannelInitializer.MINECRAFT_ENCODER, encoder)
        .addLast(ServerChannelInitializer.CONNECTION_HANDLER, connection);
    return channel;
  }

  private static MinecraftConnection connection(EmbeddedChannel channel) {
    return (MinecraftConnection)
        channel.pipeline().get(ServerChannelInitializer.CONNECTION_HANDLER);
  }
}
