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
package dev.warp.via;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.Warp;
import dev.warp.api.command.CommandManager;
import dev.warp.api.event.EventBus;
import dev.warp.api.event.Subscription;
import dev.warp.api.messaging.Messaging;
import dev.warp.api.network.PipelinePosition;
import dev.warp.api.player.Player;
import dev.warp.api.plugin.PluginContext;
import dev.warp.api.plugin.PluginManager;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerInfo;
import dev.warp.api.server.ServerRegistry;
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
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration;
import dev.warp.protocol.packet.config.FinishConfiguration;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginStart;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.protocol.packet.login.SetCompression;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.proxy.connection.MinecraftConnection;
import dev.warp.proxy.connection.ServerChannelInitializer;
import dev.warp.proxy.pipeline.PipelineRegistry;
import dev.warp.proxy.pipeline.PluginNetworkPipelines;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The plugin, booted for real, translating a backend connection built as Warp builds it: Warp's
 * codecs speak the player's version, ViaVersion rewrites the handshake and every packet to the
 * backend's version, and compression is switched on halfway, as a real login does.
 */
@DisplayName("ViaVersion behind Warp's pipeline")
class ViaTranslationTest {

  /** A 1.21.4 backend, set in servers.yml. */
  private static final ServerInfo LOBBY =
      new ServerInfo("lobby", new InetSocketAddress(InetAddress.getLoopbackAddress(), 25566));

  private static final ProtocolVersion BACKEND = ProtocolVersion.MINECRAFT_1_21_4;
  private static final ProtocolVersion CLIENT = ProtocolVersion.MINECRAFT_1_21_7;
  private static final int THRESHOLD = 256;

  /** A 1.20.1 backend, set in servers.yml: no configuration phase, which ViaVersion emulates. */
  private static final ServerInfo LEGACY =
      new ServerInfo("legacy", new InetSocketAddress(InetAddress.getLoopbackAddress(), 25567));

  private static final ProtocolVersion LEGACY_VERSION = ProtocolVersion.MINECRAFT_1_20_1;

  private static final PipelineRegistry PIPELINES = new PipelineRegistry();
  private static final RegisteredServer LOBBY_SERVER = mock(RegisteredServer.class);
  private static final RegisteredServer LEGACY_SERVER = mock(RegisteredServer.class);

  private final Player player = mock(Player.class);
  private final List<EmbeddedChannel> channels = new ArrayList<>();

  @BeforeAll
  static void bootVia() throws IOException {
    Path data = Files.createTempDirectory("warp-via-it");
    Files.writeString(
        data.resolve("servers.yml"),
        "servers:\n  lobby: \"1.21.4\"\n  legacy: \"1.20.1\"\n",
        StandardCharsets.UTF_8);
    for (RegisteredServer server : List.of(LOBBY_SERVER, LEGACY_SERVER)) {
      ServerInfo info = server == LOBBY_SERVER ? LOBBY : LEGACY;
      when(server.name()).thenReturn(info.name());
      when(server.info()).thenReturn(info);
      when(server.ping(anyInt())).thenReturn(new CompletableFuture<>());
    }

    ServerRegistry servers = mock(ServerRegistry.class);
    when(servers.servers()).thenReturn(List.of(LOBBY_SERVER, LEGACY_SERVER));
    Warp proxy = mock(Warp.class);
    when(proxy.version()).thenReturn("test");
    when(proxy.servers()).thenReturn(servers);
    when(proxy.plugins()).thenReturn(mock(PluginManager.class));
    when(proxy.player(any(UUID.class))).thenReturn(Optional.empty());
    EventBus events = mock(EventBus.class);
    when(events.subscribe(any(), anyInt(), any())).thenReturn(mock(Subscription.class));
    when(events.subscribeAsync(any(), anyInt(), anyInt(), any()))
        .thenReturn(mock(Subscription.class));

    PluginContext context = mock(PluginContext.class);
    when(context.proxy()).thenReturn(proxy);
    when(context.logger()).thenReturn(LoggerFactory.getLogger("viaversion"));
    when(context.dataDirectory()).thenReturn(data);
    when(context.events()).thenReturn(events);
    when(context.messaging()).thenReturn(mock(Messaging.class));
    when(context.commands()).thenReturn(mock(CommandManager.class));
    when(context.network())
        .thenReturn(new PluginNetworkPipelines(PIPELINES, WarpVia.ID, subscription -> {}));

    new WarpVia(context).enable();
  }

  @AfterEach
  void tearDown() {
    for (EmbeddedChannel channel : channels) {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  @DisplayName("should leave a player of the backend's own version untouched: passthrough kept")
  void sameVersionUntouched() {
    MinecraftConnection backend = backend(BACKEND);

    PIPELINES.initializeBackend(backend, BACKEND.protocol(), player, LOBBY_SERVER);

    assertFalse(backend.isPacketLevel());
    assertEquals(List.of(), backend.pluginHandlers(PipelinePosition.PACKET));
  }

  @Test
  @DisplayName("should translate a newer player's login and play packets to the backend's version")
  void translatesNewerPlayer() {
    MinecraftConnection backend = backend(CLIENT);
    EmbeddedChannel channel = (EmbeddedChannel) backend.channel();
    Recorder received = new Recorder(backend);

    PIPELINES.initializeBackend(backend, CLIENT.protocol(), player, LOBBY_SERVER);
    backend.setSessionHandler(received);

    // The plugin's handlers, at the packet position, name ViaVersion's injector names.
    assertTrue(backend.isPacketLevel());
    assertEquals(
        List.of("viaversion:decoder", "viaversion:encoder"),
        backend.pluginHandlers(PipelinePosition.PACKET));

    // HANDSHAKE: Warp announces the player's version, the backend reads its own.
    backend.setState(ProtocolState.HANDSHAKE);
    backend.writeAndFlush(new Handshake(CLIENT.protocol(), "lobby", 25566, 2));
    backend.setState(ProtocolState.LOGIN);
    backend.writeAndFlush(new LoginStart("Steve", UUID.randomUUID()));
    Handshake handshake =
        sentToBackend(channel, ProtocolState.HANDSHAKE, Handshake.class, false).getFirst();
    assertEquals(BACKEND.protocol(), handshake.protocolVersion());

    // LOGIN: compression starts, then the login succeeds, read in the player's version.
    receive(channel, ProtocolState.LOGIN, new SetCompression(THRESHOLD), false);
    UUID uuid = UUID.randomUUID();
    receive(
        channel,
        ProtocolState.LOGIN,
        new LoginSuccess(uuid, "Steve", List.of(), false, null),
        true);
    assertEquals("Steve", assertInstanceOf(LoginSuccess.class, received.packets.get(1)).username());

    // CONFIGURATION, then PLAY, as the proxy's session handlers would move them.
    backend.writeAndFlush(new LoginAcknowledged());
    assertFalse(
        sentToBackend(channel, ProtocolState.LOGIN, LoginAcknowledged.class, true).isEmpty());
    backend.setState(ProtocolState.CONFIGURATION);
    receive(channel, ProtocolState.CONFIGURATION, new FinishConfiguration(), true);
    assertTrue(received.packets.stream().anyMatch(FinishConfiguration.class::isInstance));
    backend.writeAndFlush(new AcknowledgeFinishConfiguration());
    backend.setState(ProtocolState.PLAY);
    drain(channel);

    // PLAY: the Keep Alive ids moved in 1.21.5; each side reads its own.
    int backendId =
        packetId(ProtocolState.PLAY, PacketDirection.CLIENTBOUND, KeepAlive.class, BACKEND);
    int clientId =
        packetId(ProtocolState.PLAY, PacketDirection.CLIENTBOUND, KeepAlive.class, CLIENT);
    assertNotEquals(backendId, clientId);
    receive(channel, ProtocolState.PLAY, new KeepAlive(42), true);
    assertEquals(new KeepAlive(42), received.packets.getLast());
    backend.writeAndFlush(new KeepAlive(43));
    KeepAlive answer = sentToBackend(channel, ProtocolState.PLAY, KeepAlive.class, true).getLast();
    assertEquals(new KeepAlive(43), answer);
  }

  @Test
  @DisplayName("should let ViaBackwards answer the configuration phase an older player lacks")
  void olderPlayerConfigurationEmulated() {
    ProtocolVersion older = ProtocolVersion.MINECRAFT_1_20;
    MinecraftConnection backend = backend(older);
    EmbeddedChannel channel = (EmbeddedChannel) backend.channel();
    Recorder received = new Recorder(backend);
    PIPELINES.initializeBackend(backend, older.protocol(), player, LOBBY_SERVER);
    backend.setSessionHandler(received);

    backend.setState(ProtocolState.HANDSHAKE);
    backend.writeAndFlush(new Handshake(older.protocol(), "lobby", 25566, 2));
    backend.setState(ProtocolState.LOGIN);
    backend.writeAndFlush(new LoginStart("Alex", UUID.randomUUID()));
    assertEquals(
        BACKEND.protocol(),
        sentToBackend(channel, ProtocolState.HANDSHAKE, Handshake.class, false)
            .getFirst()
            .protocolVersion());
    receive(channel, ProtocolState.LOGIN, new SetCompression(THRESHOLD), false);
    receive(
        channel,
        ProtocolState.LOGIN,
        new LoginSuccess(UUID.randomUUID(), "Alex", List.of(), false, null),
        true);

    // The proxy reads a 1.20 login, which goes straight to PLAY; ViaBackwards acknowledges it to
    // the 1.21.4 backend itself, through the raw packet path that goes past its own encoder.
    assertEquals("Alex", assertInstanceOf(LoginSuccess.class, received.packets.get(1)).username());
    assertFalse(
        sentToBackend(channel, ProtocolState.LOGIN, LoginAcknowledged.class, true).isEmpty());
  }

  @Test
  @DisplayName(
      "should read, in the configuration state, what ViaVersion held for a pre-1.20.2 backend until"
          + " the proxy acknowledged its login")
  void heldPacketsAfterTheLoginAcknowledgement() {
    // A switch acknowledges the new server's login only once the client is in the configuration
    // phase. Meanwhile the 1.20.1 server plays already, and ViaVersion holds its packets; the
    // acknowledgement makes it pass them up, translated into the configuration state, before the
    // write returns.
    MinecraftConnection backend = backend(CLIENT);
    EmbeddedChannel channel = (EmbeddedChannel) backend.channel();
    Recorder login = new Recorder(backend);
    PIPELINES.initializeBackend(backend, CLIENT.protocol(), player, LEGACY_SERVER);
    backend.setSessionHandler(login);
    backend.setState(ProtocolState.HANDSHAKE);
    backend.writeAndFlush(new Handshake(CLIENT.protocol(), "legacy", 25567, 2));
    backend.setState(ProtocolState.LOGIN);
    backend.writeAndFlush(new LoginStart("Steve", UUID.randomUUID()));
    assertEquals(
        LEGACY_VERSION.protocol(),
        sentToBackend(channel, ProtocolState.HANDSHAKE, Handshake.class, false, LEGACY_VERSION)
            .getFirst()
            .protocolVersion());
    receive(channel, ProtocolState.LOGIN, new SetCompression(THRESHOLD), false, LEGACY_VERSION);
    receive(
        channel,
        ProtocolState.LOGIN,
        new LoginSuccess(UUID.randomUUID(), "Steve", List.of(), false, null),
        true,
        LEGACY_VERSION);
    receive(channel, ProtocolState.PLAY, new KeepAlive(7), true, LEGACY_VERSION);
    Recorder configuration = new Recorder(backend);

    backend.acknowledgeStateChange(
        new LoginAcknowledged(), ProtocolState.CONFIGURATION, configuration);

    assertEquals(List.of(new KeepAlive(7)), configuration.packets);
    assertTrue(channel.isOpen());
    assertFalse(login.packets.contains(new KeepAlive(7)));
  }

  @Test
  @DisplayName("should let ViaRewind log a 1.8 player in to a newer backend")
  void legacyPlayerTranslated() {
    ProtocolVersion legacy = ProtocolVersion.MINECRAFT_1_8;
    MinecraftConnection backend = backend(legacy);
    EmbeddedChannel channel = (EmbeddedChannel) backend.channel();
    Recorder received = new Recorder(backend);

    boolean set = PIPELINES.initializeBackend(backend, legacy.protocol(), player, LOBBY_SERVER);
    backend.setSessionHandler(received);
    backend.setState(ProtocolState.HANDSHAKE);
    backend.writeAndFlush(new Handshake(legacy.protocol(), "lobby", 25566, 2));
    backend.setState(ProtocolState.LOGIN);
    backend.writeAndFlush(new LoginStart("Notch", UUID.randomUUID()));
    Handshake handshake =
        sentToBackend(channel, ProtocolState.HANDSHAKE, Handshake.class, false).getFirst();
    receive(channel, ProtocolState.LOGIN, new SetCompression(THRESHOLD), false);
    receive(
        channel,
        ProtocolState.LOGIN,
        new LoginSuccess(UUID.randomUUID(), "Notch", List.of(), false, null),
        true);

    // Set up within the contract, the handshake announces the backend's version, and the 1.8
    // login (Set Compression included, 1.8 has it) goes straight to PLAY: ViaBackwards
    // acknowledges it to the backend itself.
    assertTrue(set);
    assertEquals(BACKEND.protocol(), handshake.protocolVersion());
    assertEquals(new SetCompression(THRESHOLD), received.packets.getFirst());
    assertEquals("Notch", assertInstanceOf(LoginSuccess.class, received.packets.get(1)).username());
    assertFalse(
        sentToBackend(channel, ProtocolState.LOGIN, LoginAcknowledged.class, true).isEmpty());
  }

  @Test
  @DisplayName("should let ViaRewind compress for a 1.7 player, who has no compression")
  void compressionlessPlayerTranslated() {
    ProtocolVersion legacy = ProtocolVersion.MINECRAFT_1_7_6;
    MinecraftConnection backend = backend(legacy);
    EmbeddedChannel channel = (EmbeddedChannel) backend.channel();
    Recorder received = new Recorder(backend);

    PIPELINES.initializeBackend(backend, legacy.protocol(), player, LOBBY_SERVER);
    backend.setSessionHandler(received);
    backend.setState(ProtocolState.HANDSHAKE);
    backend.writeAndFlush(new Handshake(legacy.protocol(), "lobby", 25566, 2));
    backend.setState(ProtocolState.LOGIN);
    backend.writeAndFlush(new LoginStart("Herobrine", UUID.randomUUID()));
    drain(channel);
    receive(channel, ProtocolState.LOGIN, new SetCompression(THRESHOLD), false);
    receive(
        channel,
        ProtocolState.LOGIN,
        new LoginSuccess(UUID.randomUUID(), "Herobrine", List.of(), false, null),
        true);

    // ViaRewind drops Set Compression and compresses below ViaVersion with handlers of its own,
    // named after the plugin, never Warp's: Warp's layers stay uncompressed, as for any 1.7 player.
    LoginSuccess success = assertInstanceOf(LoginSuccess.class, received.packets.getFirst());
    assertEquals("Herobrine", success.username());
    assertFalse(backend.isCompressed());
    assertEquals(
        List.of(
            "viaversion:rewind-decompressor",
            "viaversion:decoder",
            "viaversion:rewind-compressor",
            "viaversion:encoder"),
        backend.pluginHandlers(PipelinePosition.PACKET));
    assertFalse(
        sentToBackend(channel, ProtocolState.LOGIN, LoginAcknowledged.class, true).isEmpty());
  }

  @Test
  @DisplayName("should give each backend connection of a switch its own translation")
  void switchTranslatesEachConnection() {
    MinecraftConnection first = backend(CLIENT);
    Recorder firstReceived = new Recorder(first);
    play(first, firstReceived);
    MinecraftConnection second = backend(CLIENT);
    Recorder secondReceived = new Recorder(second);

    // The switch opens the second connection while the player still plays on the first.
    play(second, secondReceived);
    receive((EmbeddedChannel) first.channel(), ProtocolState.PLAY, new KeepAlive(1), true);
    first.close();
    receive((EmbeddedChannel) second.channel(), ProtocolState.PLAY, new KeepAlive(2), true);

    assertEquals(new KeepAlive(1), firstReceived.packets.getLast());
    assertEquals(new KeepAlive(2), secondReceived.packets.getLast());
  }

  /** Logs a newer player in on a translated connection and brings it to PLAY. */
  private void play(MinecraftConnection backend, Recorder received) {
    EmbeddedChannel channel = (EmbeddedChannel) backend.channel();
    PIPELINES.initializeBackend(backend, CLIENT.protocol(), player, LOBBY_SERVER);
    backend.setSessionHandler(received);
    backend.setState(ProtocolState.HANDSHAKE);
    backend.writeAndFlush(new Handshake(CLIENT.protocol(), "lobby", 25566, 2));
    backend.setState(ProtocolState.LOGIN);
    backend.writeAndFlush(new LoginStart("Steve", UUID.randomUUID()));
    receive(channel, ProtocolState.LOGIN, new SetCompression(THRESHOLD), false);
    receive(
        channel,
        ProtocolState.LOGIN,
        new LoginSuccess(UUID.randomUUID(), "Steve", List.of(), false, null),
        true);
    backend.writeAndFlush(new LoginAcknowledged());
    backend.setState(ProtocolState.CONFIGURATION);
    receive(channel, ProtocolState.CONFIGURATION, new FinishConfiguration(), true);
    backend.writeAndFlush(new AcknowledgeFinishConfiguration());
    backend.setState(ProtocolState.PLAY);
    drain(channel);
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  /** Records what the proxy's session would receive; switches compression on as it would. */
  private static final class Recorder implements SessionHandler {
    final List<Packet> packets = new ArrayList<>();
    private final MinecraftConnection connection;

    Recorder(MinecraftConnection connection) {
      this.connection = connection;
    }

    @Override
    public void handle(Packet packet) {
      packets.add(packet);
      if (packet instanceof SetCompression(int threshold)) {
        connection.enableCompression(threshold, Deflater.DEFAULT_COMPRESSION, true, true);
      }
    }
  }

  /** A backend connection laid out as Warp's BackendChannelInitializer lays it out. */
  private MinecraftConnection backend(ProtocolVersion version) {
    EmbeddedChannel channel = new EmbeddedChannel();
    MinecraftConnection connection = new MinecraftConnection(channel);
    channel
        .pipeline()
        .addLast(ServerChannelInitializer.FRAME_DECODER, new FrameDecoder())
        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(
            ServerChannelInitializer.MINECRAFT_DECODER,
            new MinecraftDecoder(PacketDirection.CLIENTBOUND, version, ProtocolState.LOGIN))
        .addLast(
            ServerChannelInitializer.MINECRAFT_ENCODER,
            new MinecraftEncoder(PacketDirection.SERVERBOUND, version, ProtocolState.LOGIN))
        .addLast(ServerChannelInitializer.CONNECTION_HANDLER, connection);
    channels.add(channel);
    return connection;
  }

  /** The 1.21.4 backend sends a packet, encoded in its own version. */
  private static void receive(
      EmbeddedChannel channel, ProtocolState state, Packet packet, boolean compressed) {
    receive(channel, state, packet, compressed, BACKEND);
  }

  /** A backend of {@code version} sends a packet, encoded in that version. */
  private static void receive(
      EmbeddedChannel channel,
      ProtocolState state,
      Packet packet,
      boolean compressed,
      ProtocolVersion version) {
    ByteBuf body = Unpooled.buffer();
    PacketDirection direction = PacketDirection.CLIENTBOUND;
    var encoding = StateRegistry.get(state, direction).encoding(version, packet.getClass());
    VarInt.write(body, encoding.packetId());
    @SuppressWarnings("unchecked")
    PacketCodec<Packet> codec = (PacketCodec<Packet>) encoding.codec();
    codec.encode(packet, body, version);
    ByteBuf frame = Unpooled.buffer();
    if (compressed) {
      // Below the threshold: sent uncompressed, with a Data Length of 0.
      VarInt.write(frame, body.readableBytes() + 1);
      frame.writeByte(0);
    } else {
      VarInt.write(frame, body.readableBytes());
    }
    frame.writeBytes(body);
    body.release();
    channel.writeInbound(frame);
    channel.runPendingTasks();
  }

  /**
   * Decodes, in the backend's version, the packets of a type the proxy sent it so far, in order.
   * Packets of other types (other ids) are skipped.
   */
  private static <T extends Packet> List<T> sentToBackend(
      EmbeddedChannel channel, ProtocolState state, Class<T> type, boolean compressed) {
    return sentToBackend(channel, state, type, compressed, BACKEND);
  }

  /**
   * As {@link #sentToBackend(EmbeddedChannel, ProtocolState, Class, boolean)}, in {@code version}.
   */
  private static <T extends Packet> List<T> sentToBackend(
      EmbeddedChannel channel,
      ProtocolState state,
      Class<T> type,
      boolean compressed,
      ProtocolVersion version) {
    channel.runPendingTasks();
    EmbeddedChannel reader =
        new EmbeddedChannel(
            new FrameDecoder(),
            new PacketDecompressor(
                compressed
                    ? new FrameDecompressor(
                        THRESHOLD,
                        true,
                        FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
                        new JavaCompressor(Deflater.DEFAULT_COMPRESSION))
                    : null));
    for (Object out = channel.readOutbound(); out != null; out = channel.readOutbound()) {
      reader.writeInbound(out);
    }
    int id = packetId(state, PacketDirection.SERVERBOUND, type, version);
    @SuppressWarnings("unchecked")
    PacketCodec<T> codec =
        (PacketCodec<T>)
            StateRegistry.get(state, PacketDirection.SERVERBOUND).encoding(version, type).codec();
    List<T> found = new ArrayList<>();
    for (ByteBuf packet = reader.readInbound(); packet != null; packet = reader.readInbound()) {
      try {
        // In the handshake state only the first packet is the handshake: the login follows.
        if (VarInt.read(packet) == id && (state != ProtocolState.HANDSHAKE || found.isEmpty())) {
          found.add(codec.decode(packet, version));
        }
      } finally {
        packet.release();
      }
      if (state == ProtocolState.HANDSHAKE) {
        break;
      }
    }
    reader.finishAndReleaseAll();
    assertFalse(found.isEmpty(), "the backend received no " + type.getSimpleName());
    return found;
  }

  private static int packetId(
      ProtocolState state,
      PacketDirection direction,
      Class<? extends Packet> type,
      ProtocolVersion version) {
    return StateRegistry.get(state, direction).packetId(version, type);
  }

  /** Discards what the proxy sent the backend so far. */
  private static void drain(EmbeddedChannel channel) {
    channel.runPendingTasks();
    for (ByteBuf out = channel.readOutbound(); out != null; out = channel.readOutbound()) {
      out.release();
    }
  }
}
