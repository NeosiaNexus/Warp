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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.event.Subscription;
import dev.warp.api.network.BackendPipeline;
import dev.warp.api.network.ConnectionSide;
import dev.warp.api.network.PipelinePosition;
import dev.warp.api.player.InboundConnection;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameEncoder;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.netty.PacketDecompressor;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.proxy.network.PipelineRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Plugin pipeline handlers")
class PluginHandlersTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;
  private static final int THRESHOLD = 256;

  /** A clientbound and serverbound PLAY packet id the proxy never reads in 1.21.4. */
  private static final int OPAQUE_ID = 0x7F;

  private static final ServerInfo LOBBY =
      new ServerInfo("lobby", new InetSocketAddress(InetAddress.getLoopbackAddress(), 25566));

  private final List<EmbeddedChannel> channels = new ArrayList<>();
  private final PipelineRegistry registry = new PipelineRegistry();
  private final Player player = mock(Player.class);
  private final RegisteredServer server = mock(RegisteredServer.class);

  PluginHandlersTest() {
    when(player.username()).thenReturn("Steve");
    when(server.name()).thenReturn(LOBBY.name());
    when(server.info()).thenReturn(LOBBY);
  }

  @AfterEach
  void tearDown() {
    for (EmbeddedChannel channel : channels) {
      channel.finishAndReleaseAll();
    }
  }

  @Nested
  @DisplayName("initializers")
  class Initializers {

    @Test
    @DisplayName("should describe the backend connection: side, version, player and server")
    void describeBackend() {
      MinecraftConnection backend = backend();
      List<BackendPipeline> seen = new ArrayList<>();
      var _ = registry.addBackendInitializer("probe", seen::add);

      assertTrue(registry.initializeBackend(backend, VERSION.protocol(), player, server));

      BackendPipeline pipeline = seen.getFirst();
      assertEquals(ConnectionSide.BACKEND, pipeline.side());
      assertEquals(769, pipeline.protocolVersion());
      assertSame(player, pipeline.player());
      assertSame(server, pipeline.server());
      assertEquals(LOBBY.address(), pipeline.remoteAddress());
      assertSame(backend.channel(), pipeline.channel());
      assertFalse(pipeline.decompressesPackets());
    }

    @Test
    @DisplayName("should describe the client connection once its handshake was read")
    void describeClient() {
      MinecraftConnection client = client();
      InboundConnection inbound = mock(InboundConnection.class);
      InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000);
      when(inbound.remoteAddress()).thenReturn(address);
      List<ConnectionSide> sides = new ArrayList<>();
      var _ =
          registry.addClientInitializer(
              "probe",
              pipeline -> {
                sides.add(pipeline.side());
                assertSame(inbound, pipeline.connection());
                assertEquals(address, pipeline.remoteAddress());
              });

      assertTrue(registry.initializeClient(client, VERSION.protocol(), inbound));

      assertEquals(List.of(ConnectionSide.CLIENT), sides);
    }

    @Test
    @DisplayName("should name handlers after their plugin, and nest them in registration order")
    void namesAndNests() {
      MinecraftConnection backend = backend();
      var _ =
          registry.addBackendInitializer(
              "outer", p -> p.addHandler(PipelinePosition.PACKET, "codec", new Recorder()));
      var _ =
          registry.addBackendInitializer(
              "inner",
              p -> {
                p.addHandler(PipelinePosition.PACKET, "codec", new Recorder());
                assertEquals(
                    List.of("outer:codec", "inner:codec"), p.handlers(PipelinePosition.PACKET));
              });

      assertTrue(registry.initializeBackend(backend, VERSION.protocol(), player, server));

      List<String> names = backend.channel().pipeline().names();
      assertTrue(names.indexOf("outer:codec") < names.indexOf("inner:codec"));
      assertTrue(
          names.indexOf("inner:codec") < names.indexOf(ServerChannelInitializer.MINECRAFT_DECODER));
    }

    @Test
    @DisplayName("should close the connection when an initializer throws, and run no other")
    void closesOnFailure() {
      MinecraftConnection backend = backend();
      List<String> ran = new ArrayList<>();
      var _ =
          registry.addBackendInitializer(
              "broken",
              p -> {
                throw new IllegalStateException("boom");
              });
      var _ = registry.addBackendInitializer("next", p -> ran.add("next"));

      assertFalse(registry.initializeBackend(backend, VERSION.protocol(), player, server));

      assertFalse(backend.channel().isOpen());
      assertEquals(List.of(), ran);
    }

    @Test
    @DisplayName("should refuse handlers added after the initializer returned")
    void refusesLateHandlers() {
      MinecraftConnection backend = backend();
      List<BackendPipeline> kept = new ArrayList<>();
      var _ = registry.addBackendInitializer("late", kept::add);
      registry.initializeBackend(backend, VERSION.protocol(), player, server);

      assertThrows(
          IllegalStateException.class,
          () -> kept.getFirst().addHandler(PipelinePosition.FRAME, "late", new Recorder()));
    }

    @Test
    @DisplayName("should refuse a second handler of the same name from the same plugin")
    void refusesDuplicates() {
      MinecraftConnection backend = backend();
      var _ =
          registry.addBackendInitializer(
              "twice",
              p -> {
                p.addHandler(PipelinePosition.FRAME, "h", new Recorder());
                assertThrows(
                    IllegalArgumentException.class,
                    () -> p.addHandler(PipelinePosition.PACKET, "h", new Recorder()));
              });

      assertTrue(registry.initializeBackend(backend, VERSION.protocol(), player, server));
    }

    @Test
    @DisplayName("should stop running an initializer once its subscription is closed")
    void stopsAfterUnsubscribe() {
      List<String> ran = new ArrayList<>();
      Subscription subscription = registry.addBackendInitializer("gone", p -> ran.add("ran"));

      subscription.unsubscribe();
      registry.initializeBackend(backend(), VERSION.protocol(), player, server);

      assertFalse(subscription.isActive());
      assertEquals(List.of(), ran);
    }
  }

  @Nested
  @DisplayName("FRAME position")
  class FramePosition {

    @Test
    @DisplayName("should see frames both ways and keep compression passthrough")
    void seesFramesKeepsPassthrough() {
      MinecraftConnection backend = backend();
      Recorder recorder = new Recorder();
      install(backend, PipelinePosition.FRAME, recorder);
      MinecraftConnection client = client();
      backend.enableCompression(THRESHOLD, Deflater.DEFAULT_COMPRESSION, true, true);
      client.enableCompression(THRESHOLD, Deflater.DEFAULT_COMPRESSION, false, true);
      relay(backend, client);
      ByteBuf compressed = compressedFrame(randomPacket(1000));
      byte[] wire = ByteBufUtil.getBytes(compressed);

      inbound(backend).writeInbound(compressed);

      assertFalse(backend.isPacketLevel());
      assertArrayEquals(wire, recorder.inbound.getFirst()); // the frame as received
      assertArrayEquals(wire, sent(client)); // forwarded to the client byte for byte
    }

    @Test
    @DisplayName("should stay between the framing layers when compression replaces the encoder")
    void staysInPlace() {
      MinecraftConnection backend = backend();
      install(backend, PipelinePosition.FRAME, new Recorder());

      backend.enableCompression(THRESHOLD, Deflater.DEFAULT_COMPRESSION, true, true);

      List<String> names = backend.channel().pipeline().names();
      int handler = names.indexOf("plugin:handler");
      assertTrue(names.indexOf(ServerChannelInitializer.FRAME_DECODER) < handler);
      assertTrue(handler < names.indexOf(ServerChannelInitializer.COMPRESSION_ENCODER));
      assertEquals(List.of("plugin:handler"), backend.pluginHandlers(PipelinePosition.FRAME));
    }
  }

  @Nested
  @DisplayName("PACKET position")
  class PacketPosition {

    @Test
    @DisplayName("should switch the connection to packet level, ahead of the decoder only")
    void switchesToPacketLevel() {
      MinecraftConnection backend = backend();

      install(backend, PipelinePosition.PACKET, new Recorder());

      assertTrue(backend.isPacketLevel());
      assertFalse(backend.decoder().emitsFrames());
      List<String> names = backend.channel().pipeline().names();
      assertTrue(
          names.indexOf(ServerChannelInitializer.PACKET_DECOMPRESSOR)
              < names.indexOf("plugin:handler"));
      assertNotNull(backend.channel().pipeline().get(PacketDecompressor.class));
    }

    @Test
    @DisplayName("should receive inbound packets decompressed, and the client gets them re-encoded")
    void inboundPacketsDecompressed() {
      MinecraftConnection backend = backend();
      Recorder recorder = new Recorder();
      install(backend, PipelinePosition.PACKET, recorder);
      MinecraftConnection client = client();
      backend.enableCompression(THRESHOLD, Deflater.DEFAULT_COMPRESSION, true, true);
      client.enableCompression(THRESHOLD, Deflater.DEFAULT_COMPRESSION, false, true);
      relay(backend, client);
      byte[] packet = randomPacket(1000);

      inbound(backend).writeInbound(compressedFrame(packet));

      assertArrayEquals(packet, recorder.inbound.getFirst());
      assertArrayEquals(packet, decompress(sent(client)));
    }

    @Test
    @DisplayName("should see packets forwarded from the client, which are never sent verbatim")
    void outboundForwardedPackets() {
      MinecraftConnection backend = backend();
      Recorder recorder = new Recorder();
      install(backend, PipelinePosition.PACKET, recorder);
      MinecraftConnection client = client();
      backend.enableCompression(THRESHOLD, Deflater.DEFAULT_COMPRESSION, true, true);
      client.enableCompression(THRESHOLD, Deflater.DEFAULT_COMPRESSION, false, true);
      relay(client, backend);
      byte[] packet = randomPacket(1000);

      inbound(client).writeInbound(compressedFrame(packet));

      assertArrayEquals(packet, recorder.outbound.getFirst());
      assertArrayEquals(packet, decompress(sent(backend)));
    }

    @Test
    @DisplayName("should take over a decompressor already running when added late")
    void takesOverDecompressor() {
      MinecraftConnection backend = backend();
      backend.enableCompression(THRESHOLD, Deflater.DEFAULT_COMPRESSION, true, true);
      FrameDecompressor running = backend.decoder().decompressor();
      Recorder recorder = new Recorder();

      install(backend, PipelinePosition.PACKET, recorder);
      byte[] packet = randomPacket(600);
      inbound(backend).writeInbound(compressedFrame(packet));

      assertSame(
          running, backend.channel().pipeline().get(PacketDecompressor.class).decompressor());
      assertNull(backend.decoder().decompressor());
      assertArrayEquals(packet, recorder.inbound.getFirst());
    }
  }

  @Test
  @DisplayName("should name the plugin handlers of a pipeline in error logs")
  void describesPluginHandlers() {
    assertEquals(
        " (plugin handlers: viaversion:decoder, viaversion:encoder)",
        MinecraftConnection.describePluginHandlers(
            List.of(
                "frame-decoder",
                "viaversion:decoder",
                "viaversion:encoder",
                "minecraft-decoder",
                "DefaultChannelPipeline$TailContext#0")));
    assertEquals("", MinecraftConnection.describePluginHandlers(List.of("frame-decoder")));
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  /** Records the bytes of every message it sees, both ways, and passes it on. */
  private static final class Recorder extends ChannelDuplexHandler {
    final List<byte[]> inbound = new ArrayList<>();
    final List<byte[]> outbound = new ArrayList<>();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      if (msg instanceof ByteBuf buf) {
        inbound.add(ByteBufUtil.getBytes(buf));
      }
      ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
      if (msg instanceof ByteBuf buf) {
        outbound.add(ByteBufUtil.getBytes(buf));
      }
      ctx.write(msg, promise);
    }
  }

  private void install(
      MinecraftConnection connection, PipelinePosition position, Recorder handler) {
    var _ =
        registry.addBackendInitializer(
            "plugin", pipeline -> pipeline.addHandler(position, "handler", handler));
    assertTrue(registry.initializeBackend(connection, VERSION.protocol(), player, server));
  }

  /** Makes {@code from} forward every frame it does not read to {@code to}, as a session does. */
  private static void relay(MinecraftConnection from, MinecraftConnection to) {
    from.setSessionHandler(
        new SessionHandler() {
          @Override
          public void handle(Packet packet) {}

          @Override
          public void handleBlind(ByteBuf buf) {
            to.forward(buf, from);
          }
        });
  }

  private MinecraftConnection backend() {
    return connection(PacketDirection.CLIENTBOUND);
  }

  private MinecraftConnection client() {
    return connection(PacketDirection.SERVERBOUND);
  }

  /** A connection in PLAY with the layout of the proxy's channel initializers. */
  private MinecraftConnection connection(PacketDirection inbound) {
    EmbeddedChannel channel = new EmbeddedChannel();
    MinecraftConnection connection = new MinecraftConnection(channel);
    PacketDirection outbound =
        inbound == PacketDirection.SERVERBOUND
            ? PacketDirection.CLIENTBOUND
            : PacketDirection.SERVERBOUND;
    channel
        .pipeline()
        .addLast(ServerChannelInitializer.FRAME_DECODER, new FrameDecoder())
        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(
            ServerChannelInitializer.MINECRAFT_DECODER,
            new MinecraftDecoder(inbound, VERSION, ProtocolState.PLAY))
        .addLast(
            ServerChannelInitializer.MINECRAFT_ENCODER,
            new MinecraftEncoder(outbound, VERSION, ProtocolState.PLAY))
        .addLast(ServerChannelInitializer.CONNECTION_HANDLER, connection);
    channels.add(channel);
    return connection;
  }

  private static EmbeddedChannel inbound(MinecraftConnection connection) {
    return (EmbeddedChannel) connection.channel();
  }

  /** Everything a connection wrote to its socket, as one byte array. */
  private static byte[] sent(MinecraftConnection connection) {
    EmbeddedChannel channel = inbound(connection);
    channel.flushOutbound();
    ByteBuf all = Unpooled.buffer();
    for (ByteBuf out = channel.readOutbound(); out != null; out = channel.readOutbound()) {
      all.writeBytes(out);
      out.release();
    }
    try {
      return ByteBufUtil.getBytes(all);
    } finally {
      all.release();
    }
  }

  private static byte[] randomPacket(int size) {
    byte[] packet = new byte[size];
    new Random(size).nextBytes(packet);
    packet[0] = OPAQUE_ID;
    return packet;
  }

  private static ByteBuf compressedFrame(byte[] packet) {
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    deflater.setInput(packet);
    deflater.finish();
    byte[] buffer = new byte[packet.length + 64];
    byte[] zlib = java.util.Arrays.copyOf(buffer, deflater.deflate(buffer));
    deflater.end();
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, packet.length);
    body.writeBytes(zlib);
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    return frame;
  }

  /** Reads one compressed-connection frame as a vanilla peer would. */
  private static byte[] decompress(byte[] wire) {
    EmbeddedChannel peer =
        new EmbeddedChannel(
            new FrameDecoder(),
            new PacketDecompressor(
                new FrameDecompressor(
                    THRESHOLD,
                    true,
                    FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
                    new JavaCompressor(Deflater.DEFAULT_COMPRESSION))));
    peer.writeInbound(Unpooled.wrappedBuffer(wire));
    ByteBuf packet = peer.readInbound();
    try {
      return ByteBufUtil.getBytes(packet);
    } finally {
      packet.release();
      assertFalse(peer.finish());
    }
  }
}
