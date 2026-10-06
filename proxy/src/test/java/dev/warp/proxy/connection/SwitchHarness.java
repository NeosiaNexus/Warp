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
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayerInfo;
import dev.warp.protocol.packet.play.PlayerInfoRemove;
import dev.warp.protocol.packet.play.PlayerInfoUpdate;
import dev.warp.protocol.packet.play.Respawn;
import dev.warp.protocol.packet.play.SpawnInfo;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.protocol.packet.play.TabListHeaderFooter;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;

/**
 * A player, its client connection and the backends it is handed, all on embedded channels, to test
 * server switches without servers.
 *
 * <p>Backends are handed to the player's {@link ServerSwitch} as if their connection had just
 * opened. Real connections cannot be opened from an embedded event loop: every one the player tries
 * fails at once, like an unreachable server.
 */
final class SwitchHarness implements AutoCloseable {

  static final ServerInfo LOBBY = server("lobby", 25001);
  static final ServerInfo SURVIVAL = server("survival", 25002);
  static final ServerInfo CREATIVE = server("creative", 25003);

  /** An empty named root compound: {@code TAG_Compound}, empty name, {@code TAG_End}. */
  private static final byte[] EMPTY_COMPOUND = {0x0A, 0x00, 0x00, 0x00};

  final ProtocolVersion version;
  final ConnectedPlayer player;
  final MinecraftConnection client;
  private final List<EmbeddedChannel> channels = new ArrayList<>();

  /** The client's encoder, kept to know its state once the connection is closed. */
  private final MinecraftEncoder clientEncoder;

  private int nextEntityId = 1;

  /**
   * Creates a player of {@code version} whose client is in {@code clientState}.
   *
   * @param fallbackOrder the server names of the fallback order
   */
  SwitchHarness(ProtocolVersion version, List<String> fallbackOrder, ProtocolState clientState) {
    this.version = version;
    this.client = connection(PacketDirection.SERVERBOUND, clientState);
    this.clientEncoder = client.encoder();
    Map<String, InetSocketAddress> servers = new LinkedHashMap<>();
    for (ServerInfo server : List.of(LOBBY, SURVIVAL, CREATIVE)) {
      servers.put(server.name(), server.address());
    }
    ServerLoginContext loginContext = mock(ServerLoginContext.class);
    when(loginContext.serverRegistry())
        .thenReturn(new ServerRegistry(servers, "lobby", fallbackOrder));
    // Cannot be registered on an embedded event loop: every connection fails at once.
    when(loginContext.channelClass()).thenAnswer(invocation -> LocalChannel.class);
    when(loginContext.forwardingSecret()).thenReturn(new byte[0]);
    this.player =
        new ConnectedPlayer(
            client,
            version,
            new GameProfile(UUID.randomUUID(), "Steve", List.of()),
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000),
            loginContext);
  }

  @Override
  public void close() {
    for (EmbeddedChannel channel : channels) {
      channel.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // Backends
  // ---------------------------------------------------------------------------

  /** Returns the backend connection the player is on. */
  MinecraftConnection current() {
    return Objects.requireNonNull(player.backendConnection(), "no backend").connection();
  }

  /** Creates a backend connection in {@code state}, not handed to the player. */
  MinecraftConnection backend(ProtocolState state) {
    return connection(PacketDirection.CLIENTBOUND, state);
  }

  /** Hands the player a backend whose login just succeeded. */
  MinecraftConnection login(ServerInfo server) {
    MinecraftConnection backend = backend(ProtocolState.LOGIN);
    player.serverSwitch().connected(new BackendConnection(backend, server));
    player.serverSwitch().loggedIn(backend);
    return backend;
  }

  /** Logs in to {@code server} and plays there (before 1.20.2). */
  MinecraftConnection spawnOn(ServerInfo server) {
    MinecraftConnection backend = login(server);
    joinGame(backend);
    return backend;
  }

  /** Moves the player to {@code server}, from its login to its Join Game (before 1.20.2). */
  MinecraftConnection switchTo(ServerInfo server) {
    player.beginSwitch(server);
    MinecraftConnection backend = login(server);
    joinGame(backend);
    return backend;
  }

  /** The backend sends its Join Game: entity IDs 1, 7, 13, ... in order. */
  void joinGame(MinecraftConnection backend) {
    receive(backend, frame(version, joinGameFor(version, nextEntityId)));
    nextEntityId += 6;
  }

  /** Feeds a frame to a backend connection, then runs what it scheduled. */
  void receive(MinecraftConnection backend, ByteBuf frame) {
    ((EmbeddedChannel) backend.channel()).writeInbound(frame);
    runPendingTasks();
  }

  void runPendingTasks() {
    for (EmbeddedChannel channel : channels) {
      channel.runPendingTasks();
    }
  }

  // ---------------------------------------------------------------------------
  // What was sent
  // ---------------------------------------------------------------------------

  /** A packet the client received: its class and its encoded body. */
  @SuppressWarnings("ArrayRecordComponent") // test data, never mutated
  record Sent(
      ProtocolVersion version, ProtocolState state, Class<? extends Packet> type, byte[] body) {

    <T extends Packet> T as(Class<T> expected) {
      assertEquals(expected, type);
      @SuppressWarnings("unchecked")
      PacketCodec<T> codec =
          (PacketCodec<T>)
              StateRegistry.get(state, PacketDirection.CLIENTBOUND)
                  .encoding(version, expected)
                  .codec();
      ByteBuf buf = Unpooled.wrappedBuffer(body);
      try {
        return codec.decode(buf, version);
      } finally {
        buf.release();
      }
    }
  }

  /** Drains what the client received, naming each packet with the client's current state. */
  List<Sent> sentToClient() {
    EmbeddedChannel channel = (EmbeddedChannel) client.channel();
    ProtocolState state = clientEncoder.state();
    PacketRegistry registry = StateRegistry.get(state, PacketDirection.CLIENTBOUND);
    List<Sent> sent = new ArrayList<>();
    for (ByteBuf frame = channel.readOutbound(); frame != null; frame = channel.readOutbound()) {
      try {
        VarInt.read(frame);
        int packetId = VarInt.read(frame);
        byte[] body = new byte[frame.readableBytes()];
        frame.readBytes(body);
        sent.add(new Sent(version, state, typeOf(registry, packetId), body));
      } finally {
        frame.release();
      }
    }
    return sent;
  }

  /** Drains what a backend received, and returns how many packets it was. */
  int sentToBackend(MinecraftConnection backend) {
    EmbeddedChannel channel = (EmbeddedChannel) backend.channel();
    int packets = 0;
    for (ByteBuf frame = channel.readOutbound(); frame != null; frame = channel.readOutbound()) {
      packets++;
      frame.release();
    }
    return packets;
  }

  static List<Class<? extends Packet>> types(List<Sent> sent) {
    return sent.stream().<Class<? extends Packet>>map(Sent::type).toList();
  }

  private Class<? extends Packet> typeOf(PacketRegistry registry, int packetId) {
    for (Class<? extends Packet> type :
        List.of(
            JoinGame.class,
            Respawn.class,
            PlayerInfo.class,
            PlayerInfoUpdate.class,
            PlayerInfoRemove.class,
            BossBar.class,
            TabListHeaderFooter.class,
            ClearTitles.class,
            PlayDisconnect.class,
            SystemChatMessage.class,
            StartConfiguration.class,
            BundleDelimiter.class,
            ConfigDisconnect.class)) {
      try {
        if (registry.packetId(version, type) == packetId) {
          return type;
        }
      } catch (IllegalArgumentException notInThisVersion) {
        // try the next type
      }
    }
    return Packet.class; // forwarded verbatim
  }

  // ---------------------------------------------------------------------------
  // Packets
  // ---------------------------------------------------------------------------

  static SessionHandler handlerOf(MinecraftConnection connection) {
    return Objects.requireNonNull(connection.sessionHandler(), "no session handler");
  }

  /** A frame the proxy forwards without decoding it. */
  static ByteBuf opaqueFrame(int packetId) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, 3);
    VarInt.write(frame, packetId);
    frame.writeShort(0x1234);
    return frame;
  }

  /** Frames a clientbound PLAY packet: length, packet ID, body. */
  static ByteBuf frame(ProtocolVersion version, Packet packet) {
    PacketRegistry.Encoding encoding =
        StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND)
            .encoding(version, packet.getClass());
    @SuppressWarnings("unchecked")
    PacketCodec<Packet> codec = (PacketCodec<Packet>) encoding.codec();
    ByteBuf body = Unpooled.buffer();
    try {
      VarInt.write(body, encoding.packetId());
      codec.encode(packet, body, version);
      ByteBuf frame = Unpooled.buffer();
      VarInt.write(frame, body.readableBytes());
      frame.writeBytes(body);
      return frame;
    } finally {
      body.release();
    }
  }

  /** A Join Game into the overworld, with every field of the version set (before 1.20.2). */
  static JoinGame joinGameFor(ProtocolVersion version, int entityId) {
    boolean modern = version.isAtLeast(ProtocolVersion.MINECRAFT_1_16);
    boolean nbtDimension =
        version.isAtLeast(ProtocolVersion.MINECRAFT_1_16_2)
            && version.isOlderThan(ProtocolVersion.MINECRAFT_1_19);
    SpawnInfo spawn =
        new SpawnInfo(
            0,
            modern && !nbtDimension ? "minecraft:overworld" : "",
            nbtDimension ? EMPTY_COMPOUND : new byte[0],
            modern ? "minecraft:overworld" : "",
            0x0102030405060708L,
            modern ? 0 : 1,
            2,
            modern ? -1 : 0,
            modern ? "" : "default",
            false,
            false,
            null,
            0);
    return new JoinGame(
        entityId,
        false,
        new JoinGame.Decoded(
            20,
            modern ? List.of("minecraft:overworld") : List.of(),
            modern ? EMPTY_COMPOUND : new byte[0],
            10,
            8,
            false,
            true,
            spawn));
  }

  private static ServerInfo server(String name, int port) {
    return new ServerInfo(name, new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
  }

  private MinecraftConnection connection(PacketDirection inbound, ProtocolState state) {
    EmbeddedChannel channel = new EmbeddedChannel();
    MinecraftConnection connection = new MinecraftConnection(channel);
    channel
        .pipeline()
        .addLast(ServerChannelInitializer.FRAME_DECODER, new FrameDecoder())
        .addLast(
            ServerChannelInitializer.MINECRAFT_DECODER,
            new MinecraftDecoder(inbound, version, state))
        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(
            ServerChannelInitializer.MINECRAFT_ENCODER,
            new MinecraftEncoder(
                inbound == PacketDirection.SERVERBOUND
                    ? PacketDirection.CLIENTBOUND
                    : PacketDirection.SERVERBOUND,
                version,
                state))
        .addLast(ServerChannelInitializer.CONNECTION_HANDLER, connection);
    channels.add(channel);
    return connection;
  }
}
