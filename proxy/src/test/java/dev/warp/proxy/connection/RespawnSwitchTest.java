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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.ChatMessage;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.PlayerInfo;
import dev.warp.protocol.packet.play.PlayerInfoRemove;
import dev.warp.protocol.packet.play.PlayerInfoUpdate;
import dev.warp.protocol.packet.play.Respawn;
import dev.warp.protocol.packet.play.SpawnInfo;
import dev.warp.protocol.packet.play.TabListHeaderFooter;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Server switches before 1.20.2: the packets the client receives, per version, and how failures
 * keep the player where it is or send it down the fallback order.
 *
 * <p>Backends are embedded channels handed to the strategy as if their connection had just opened.
 * Real connections cannot be opened from an embedded event loop: every one the player tries fails
 * at once, like an unreachable server.
 */
@DisplayName("Respawn server switch (before 1.20.2)")
class RespawnSwitchTest {

  private static final ServerInfo LOBBY = server("lobby", 25001);
  private static final ServerInfo SURVIVAL = server("survival", 25002);
  private static final ServerInfo CREATIVE = server("creative", 25003);
  private static final UUID ALICE = UUID.fromString("5c39a8cb-1a3a-4c86-9a47-0f0aaf0e3a01");
  private static final UUID BOSS = UUID.fromString("0f3e1b7c-7b5d-4b55-8e0a-2c3d4e5f6a7b");

  private final List<EmbeddedChannel> channels = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (EmbeddedChannel channel : channels) {
      channel.finishAndReleaseAll();
    }
  }

  static Stream<ProtocolVersion> versions() {
    return Stream.of(
        ProtocolVersion.MINECRAFT_1_7_6,
        ProtocolVersion.MINECRAFT_1_8,
        ProtocolVersion.MINECRAFT_1_9,
        ProtocolVersion.MINECRAFT_1_12_2,
        ProtocolVersion.MINECRAFT_1_15_2,
        ProtocolVersion.MINECRAFT_1_16_1,
        ProtocolVersion.MINECRAFT_1_16_4,
        ProtocolVersion.MINECRAFT_1_18_2,
        ProtocolVersion.MINECRAFT_1_19_2,
        ProtocolVersion.MINECRAFT_1_19_4,
        ProtocolVersion.MINECRAFT_1_20_1);
  }

  // ---------------------------------------------------------------------------
  // Strategy choice
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("strategy")
  class Strategy {

    @Test
    @DisplayName("should switch through the configuration phase from 1.20.2 and respawn before")
    void choice() {
      assertInstanceOf(
          RespawnSwitch.class, new Setup(ProtocolVersion.MINECRAFT_1_20_1).player.serverSwitch());
      assertInstanceOf(
          ReconfigurationSwitch.class,
          new Setup(ProtocolVersion.MINECRAFT_1_20_2).player.serverSwitch());
    }
  }

  // ---------------------------------------------------------------------------
  // Joining and switching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("first server")
  class FirstServer {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.RespawnSwitchTest#versions")
    @DisplayName("should forward the first Join Game as it is and start playing")
    void firstJoinGame(ProtocolVersion version) {
      Setup setup = new Setup(version);

      MinecraftConnection lobby = setup.spawnOn(LOBBY);

      List<Sent> sent = setup.sentToClient();
      assertEquals(List.of(JoinGame.class), types(sent));
      assertEquals(0, dimensionOf(sent.getFirst().as(JoinGame.class)));
      assertInstanceOf(ClientPlaySessionHandler.class, setup.client.sessionHandler());
      assertSame(lobby, setup.current());
      assertTrue(setup.player.isPlayingOn(lobby));
      assertEquals("lobby", setup.player.currentServerName());
    }

    @Test
    @DisplayName("should keep the backend's packets from the client until its Join Game")
    void nothingBeforeJoinGame() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_12_2);
      MinecraftConnection lobby = setup.login(LOBBY);

      setup.receive(lobby, opaqueFrame(0x22));

      assertEquals(List.of(), setup.sentToClient());
      assertInstanceOf(BackendJoinSessionHandler.class, lobby.sessionHandler());
    }
  }

  @Nested
  @DisplayName("switch")
  class Switch {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.RespawnSwitchTest#versions")
    @DisplayName("should send the leftovers' removal, the new Join Game, then a Respawn")
    void packetSequence(ProtocolVersion version) {
      Setup setup = new Setup(version);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.leaveLeftoversOn(lobby);
      setup.sentToClient();

      setup.switchTo(SURVIVAL);
      List<Sent> sent = setup.sentToClient();

      List<Class<? extends Packet>> expected = new ArrayList<>();
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
        expected.add(
            version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3)
                ? PlayerInfoRemove.class
                : PlayerInfo.class);
      }
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)) {
        expected.add(BossBar.class);
      }
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
        expected.add(TabListHeaderFooter.class);
        expected.add(ClearTitles.class);
      }
      expected.add(JoinGame.class);
      expected.add(Respawn.class);
      assertEquals(expected, types(sent));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.RespawnSwitchTest#versions")
    @DisplayName("should join another dimension first before 1.16, then respawn in the real one")
    void dimensions(ProtocolVersion version) {
      Setup setup = new Setup(version);
      setup.spawnOn(LOBBY);
      setup.sentToClient();

      setup.switchTo(SURVIVAL);
      List<Sent> sent = setup.sentToClient();

      JoinGame joinGame = sent.get(sent.size() - 2).as(JoinGame.class);
      Respawn respawn = sent.getLast().as(Respawn.class);
      assertEquals(7, joinGame.entityId(), "the new server's entity ID");
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_16)) {
        assertEquals(-1, dimensionOf(joinGame), "the nether, to force a world reload");
        assertEquals(0, respawn.spawn().dimension(), "back to the overworld");
      } else {
        assertEquals("minecraft:overworld", respawn.spawn().worldName());
        assertEquals(
            ((JoinGame.Decoded) joinGame.body()).spawn().worldName(), respawn.spawn().worldName());
      }
      assertEquals(0, respawn.dataKept());
    }

    @Test
    @DisplayName("should remove exactly the tab list entries and boss bars the server left")
    void leftovers() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_12_2);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.leaveLeftoversOn(lobby);
      UUID gone = UUID.randomUUID();
      setup.receive(
          lobby,
          frame(setup.version, PlayerInfo.remove(List.of(gone)), PacketDirection.CLIENTBOUND));
      setup.sentToClient();

      setup.switchTo(SURVIVAL);
      List<Sent> sent = setup.sentToClient();

      assertEquals(List.of(ALICE), sent.get(0).as(PlayerInfo.class).profileIds());
      assertEquals(PlayerInfo.REMOVE_PLAYER, sent.get(0).as(PlayerInfo.class).action());
      assertEquals(BOSS, sent.get(1).as(BossBar.class).uuid());
      assertEquals(BossBar.REMOVE, sent.get(1).as(BossBar.class).action());
      assertTrue(sent.get(3).as(ClearTitles.class).reset());
    }

    @Test
    @DisplayName("should move the player to the new backend and close the previous one")
    void backends() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_16_4);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);

      MinecraftConnection survival = setup.switchTo(SURVIVAL);

      assertSame(survival, setup.current());
      assertTrue(setup.player.isPlayingOn(survival));
      assertFalse(lobby.channel().isOpen());
      assertEquals("survival", setup.player.currentServerName());
      assertFalse(setup.player.isSwitching());
      assertEquals(7, setup.player.entityId());
    }

    @Test
    @DisplayName("should keep the player on its server while the new one logs in")
    void stayDuringLogin() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_18_2);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.sentToClient();

      setup.player.beginSwitch(SURVIVAL);
      MinecraftConnection survival = setup.login(SURVIVAL);
      setup.receive(lobby, opaqueFrame(0x22));
      setup.receive(survival, opaqueFrame(0x22));

      assertEquals(1, setup.sentToClient().size(), "only the lobby's packet");
      assertTrue(setup.player.isPlayingOn(lobby));
      assertSame(lobby, setup.current());
    }

    @Test
    @DisplayName("should drop what the previous backend still sends after the switch")
    void previousBackendSilenced() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_19_4);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.switchTo(SURVIVAL);
      setup.sentToClient();

      handlerOf(lobby).handleBlind(opaqueFrame(0x22));
      setup.runPendingTasks();

      assertEquals(List.of(), setup.sentToClient());
      assertFalse(setup.player.isSwitching(), "its closed connection is not a failure");
      assertTrue(setup.client.channel().isActive());
    }
  }

  // ---------------------------------------------------------------------------
  // Failures
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("failures")
  class Failures {

    @Test
    @DisplayName("should keep the player on its server when the new one refuses it")
    void refusedSwitch() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_12_2);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.player.beginSwitch(SURVIVAL);
      MinecraftConnection survival = setup.login(SURVIVAL);

      setup.player.scheduleBackendFailure(survival);
      setup.runPendingTasks();

      assertFalse(setup.player.isSwitching());
      assertSame(lobby, setup.current());
      assertTrue(setup.player.isPlayingOn(lobby));
      assertFalse(survival.channel().isOpen());
      assertTrue(setup.client.channel().isActive());
    }

    @Test
    @DisplayName("should keep the player on its server when the new one cannot be reached")
    void unreachableSwitch() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_8);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);

      setup.player.switchServer(SURVIVAL);

      assertFalse(setup.player.isSwitching());
      assertSame(lobby, setup.current());
      assertTrue(setup.client.channel().isActive());
    }

    @Test
    @DisplayName("should keep the player on its server when the switch times out")
    void timedOutSwitch() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_16_4);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.player.beginSwitch(SURVIVAL);
      MinecraftConnection survival = setup.login(SURVIVAL);

      setup.player.serverSwitch().timedOut();

      assertFalse(setup.player.isSwitching());
      assertFalse(survival.channel().isOpen());
      assertSame(lobby, setup.current());
    }

    @Test
    @DisplayName("should go down the fallback order when the first server refuses the player")
    void refusedFirstServer() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_18_2, List.of("survival"));
      MinecraftConnection lobby = setup.login(LOBBY);

      setup.player.scheduleBackendFailure(lobby);
      setup.runPendingTasks();

      // survival is tried (it cannot be reached from an embedded channel), then nothing is left.
      assertDisconnectedWith(setup, "Could not connect to any available server.");
    }

    @Test
    @DisplayName("should go down the fallback order when the player's server goes down")
    void serverDown() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_20_1, List.of("survival"));
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.sentToClient();

      lobby.close();
      setup.runPendingTasks();

      assertNull(setup.player.backendConnection());
      assertDisconnectedWith(setup, "Could not connect to any available server.");
    }

    @Test
    @DisplayName("should let a switch in progress take a player whose server goes down")
    void serverDownDuringSwitch() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_12_2, List.of("creative"));
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.player.beginSwitch(SURVIVAL);
      MinecraftConnection survival = setup.login(SURVIVAL);

      lobby.close();
      setup.runPendingTasks();
      setup.joinGame(survival);

      assertSame(survival, setup.current());
      assertEquals("survival", setup.player.currentServerName());
      assertTrue(setup.client.channel().isActive());
    }

    @Test
    @DisplayName(
        "should send the player down the fallback order when the switch fails after its server went down")
    void switchFailsAfterServerDown() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_12_2, List.of("creative"));
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.player.beginSwitch(SURVIVAL);
      MinecraftConnection survival = setup.login(SURVIVAL);
      setup.sentToClient();

      lobby.close();
      setup.player.scheduleBackendFailure(survival);
      setup.runPendingTasks();

      // creative is tried next (unreachable here), then nothing is left.
      assertDisconnectedWith(setup, "Could not connect to any available server.");
    }

    private static void assertDisconnectedWith(Setup setup, String reason) {
      List<Sent> sent = setup.sentToClient();
      assertEquals(PlayDisconnect.class, sent.getLast().type());
      String json =
          new String(sent.getLast().as(PlayDisconnect.class).rawReason(), StandardCharsets.UTF_8);
      assertTrue(json.contains(reason), json);
      assertFalse(setup.client.channel().isOpen());
    }
  }

  // ---------------------------------------------------------------------------
  // Commands before 1.19
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("/server before 1.19")
  class ServerCommand {

    @Test
    @DisplayName("should switch on /server typed in chat and keep it from the backend")
    void intercepted() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_8);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.sentToBackend(lobby);

      handlerOf(setup.client).handle(new ChatMessage("/server survival"));

      // survival cannot be reached from an embedded channel: the switch was attempted and given up.
      assertFalse(setup.player.isSwitching());
      assertEquals(0, setup.sentToBackend(lobby));
    }

    @Test
    @DisplayName("should forward chat and other commands to the backend")
    void forwarded() {
      Setup setup = new Setup(ProtocolVersion.MINECRAFT_1_12_2);
      MinecraftConnection lobby = setup.spawnOn(LOBBY);
      setup.sentToBackend(lobby);

      handlerOf(setup.client).handle(new ChatMessage("hello"));
      handlerOf(setup.client).handle(new ChatMessage("/servers"));
      handlerOf(setup.client).readComplete();

      assertEquals(2, setup.sentToBackend(lobby));
    }
  }

  // ---------------------------------------------------------------------------
  // Harness
  // ---------------------------------------------------------------------------

  /** A sent packet: its class and its encoded body. */
  @SuppressWarnings("ArrayRecordComponent") // test data, never mutated
  private record Sent(ProtocolVersion version, Class<? extends Packet> type, byte[] body) {

    <T extends Packet> T as(Class<T> expected) {
      assertEquals(expected, type);
      @SuppressWarnings("unchecked")
      PacketCodec<T> codec =
          (PacketCodec<T>)
              StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND)
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

  /** A player, its client connection, and the backends it is handed. */
  private final class Setup {

    final ProtocolVersion version;
    final ConnectedPlayer player;
    final MinecraftConnection client;
    private int nextEntityId = 1;

    Setup(ProtocolVersion version) {
      this(version, List.of());
    }

    Setup(ProtocolVersion version, List<String> fallbackOrder) {
      this.version = version;
      this.client = connection(PacketDirection.SERVERBOUND, ProtocolState.PLAY);
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

    /** The backend connection the player is on. */
    MinecraftConnection current() {
      return Objects.requireNonNull(player.backendConnection(), "no backend").connection();
    }

    /** Hands the player a backend whose login just succeeded. */
    MinecraftConnection login(ServerInfo server) {
      MinecraftConnection backend = connection(PacketDirection.CLIENTBOUND, ProtocolState.LOGIN);
      player.serverSwitch().connected(new BackendConnection(backend, server));
      player.serverSwitch().loggedIn(backend);
      return backend;
    }

    /** Logs in to {@code server} and plays there. */
    MinecraftConnection spawnOn(ServerInfo server) {
      MinecraftConnection backend = login(server);
      joinGame(backend);
      return backend;
    }

    /** Moves the player to {@code server}, from its login to its Join Game. */
    MinecraftConnection switchTo(ServerInfo server) {
      player.beginSwitch(server);
      MinecraftConnection backend = login(server);
      joinGame(backend);
      return backend;
    }

    void joinGame(MinecraftConnection backend) {
      receive(
          backend, frame(version, joinGameFor(version, nextEntityId), PacketDirection.CLIENTBOUND));
      nextEntityId += 6;
    }

    /** The server adds a player to the tab list and shows a boss bar. */
    void leaveLeftoversOn(MinecraftConnection backend) {
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3)) {
        receive(backend, frame(version, addPlayerUpdate(), PacketDirection.CLIENTBOUND));
      } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
        receive(backend, frame(version, addPlayer(version), PacketDirection.CLIENTBOUND));
      }
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)) {
        receive(
            backend,
            frame(
                version,
                new BossBar(BOSS, BossBar.ADD, bossBarAdd()),
                PacketDirection.CLIENTBOUND));
      }
    }

    void receive(MinecraftConnection backend, ByteBuf frame) {
      ((EmbeddedChannel) backend.channel()).writeInbound(frame);
      runPendingTasks();
    }

    void runPendingTasks() {
      for (EmbeddedChannel channel : channels) {
        channel.runPendingTasks();
      }
    }

    /** Drains what the client received, decoding each packet ID with the client's version. */
    List<Sent> sentToClient() {
      EmbeddedChannel channel = (EmbeddedChannel) client.channel();
      List<Sent> sent = new ArrayList<>();
      PacketRegistry registry = StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);
      for (ByteBuf frame = channel.readOutbound(); frame != null; frame = channel.readOutbound()) {
        try {
          VarInt.read(frame);
          int packetId = VarInt.read(frame);
          byte[] body = new byte[frame.readableBytes()];
          frame.readBytes(body);
          sent.add(new Sent(version, typeOf(registry, packetId), body));
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
              PlayDisconnect.class)) {
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

  // ---------------------------------------------------------------------------
  // Packets
  // ---------------------------------------------------------------------------

  private static ServerInfo server(String name, int port) {
    return new ServerInfo(name, new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
  }

  /** An empty named root compound: {@code TAG_Compound}, empty name, {@code TAG_End}. */
  private static final byte[] EMPTY_COMPOUND = {0x0A, 0x00, 0x00, 0x00};

  /** A Join Game into the overworld, with every field of the version set. */
  private static JoinGame joinGameFor(ProtocolVersion version, int entityId) {
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

  private static PlayerInfo addPlayer(ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    try {
      VarInt.write(buf, 1);
      buf.writeLong(ALICE.getMostSignificantBits());
      buf.writeLong(ALICE.getLeastSignificantBits());
      writeString(buf, "Alice");
      VarInt.write(buf, 0); // properties
      VarInt.write(buf, 0); // game mode
      VarInt.write(buf, 5); // latency
      buf.writeBoolean(false); // display name
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
        buf.writeBoolean(false); // public key
      }
      byte[] entries = new byte[buf.readableBytes()];
      buf.readBytes(entries);
      return new PlayerInfo(PlayerInfo.ADD_PLAYER, List.of(ALICE), entries);
    } finally {
      buf.release();
    }
  }

  private static PlayerInfoUpdate addPlayerUpdate() {
    ByteBuf buf = Unpooled.buffer();
    try {
      VarInt.write(buf, 1);
      buf.writeLong(ALICE.getMostSignificantBits());
      buf.writeLong(ALICE.getLeastSignificantBits());
      writeString(buf, "Alice");
      VarInt.write(buf, 0); // properties
      buf.writeBoolean(true); // listed
      byte[] entries = new byte[buf.readableBytes()];
      buf.readBytes(entries);
      return new PlayerInfoUpdate(
          PlayerInfoUpdate.ADD_PLAYER | PlayerInfoUpdate.UPDATE_LISTED, List.of(ALICE), entries);
    } finally {
      buf.release();
    }
  }

  /** The fields of a boss bar's ADD action: title, health, color, division, flags. */
  private static byte[] bossBarAdd() {
    ByteBuf buf = Unpooled.buffer();
    try {
      writeString(buf, "{\"text\":\"Boss\"}");
      buf.writeFloat(1);
      VarInt.write(buf, 0);
      VarInt.write(buf, 0);
      buf.writeByte(0);
      byte[] fields = new byte[buf.readableBytes()];
      buf.readBytes(fields);
      return fields;
    } finally {
      buf.release();
    }
  }

  private static void writeString(ByteBuf buf, String value) {
    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
    VarInt.write(buf, utf8.length);
    buf.writeBytes(utf8);
  }

  /** A frame the proxy forwards without decoding it. */
  private static ByteBuf opaqueFrame(int packetId) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, 3);
    VarInt.write(frame, packetId);
    frame.writeShort(0x1234);
    return frame;
  }

  /** Frames {@code packet}: length, packet ID, body. */
  private static ByteBuf frame(ProtocolVersion version, Packet packet, PacketDirection direction) {
    PacketRegistry.Encoding encoding =
        StateRegistry.get(ProtocolState.PLAY, direction).encoding(version, packet.getClass());
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

  private static SessionHandler handlerOf(MinecraftConnection connection) {
    return Objects.requireNonNull(connection.sessionHandler(), "no session handler");
  }

  private static List<Class<? extends Packet>> types(List<Sent> sent) {
    return sent.stream().<Class<? extends Packet>>map(Sent::type).toList();
  }

  private static int dimensionOf(JoinGame joinGame) {
    return ((JoinGame.Decoded) joinGame.body()).spawn().dimension();
  }
}
