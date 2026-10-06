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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameEncoder;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.TextComponent;
import dev.warp.protocol.packet.play.ChatCommand;
import dev.warp.protocol.packet.play.LegacyChatMessage;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.proxy.server.ServerRegistry;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the proxy's own {@code /server} command: a client types it as a chat line before 1.19
 * and sends it as a chat command from 1.19, and the answer must reach every version in the packet
 * that version expects.
 */
@DisplayName("ClientPlaySessionHandler")
class ClientPlaySessionHandlerTest {

  private static final InetSocketAddress LOBBY = InetSocketAddress.createUnresolved("lobby", 25566);
  private static final InetSocketAddress SURVIVAL =
      InetSocketAddress.createUnresolved("survival", 25567);

  private final List<EmbeddedChannel> channels = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (EmbeddedChannel channel : channels) {
      if (channel.isOpen()) {
        channel.finishAndReleaseAll();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // /server
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("/server")
  class ServerCommand {

    /**
     * The id and the trailer of the answer a client of each version expects: the Chat Message
     * packet with a system position until 1.18.2, System Chat Message from 1.19.
     */
    static Stream<Arguments> versions() {
      byte[] system = {1};
      byte[] systemNoSender = concat(system, new byte[McUuid.ENCODED_SIZE]);
      byte[] notOverlay = {0};
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_6, 0x02, new byte[0]),
          Arguments.of(ProtocolVersion.MINECRAFT_1_8, 0x02, system),
          Arguments.of(ProtocolVersion.MINECRAFT_1_12_2, 0x0F, system),
          Arguments.of(ProtocolVersion.MINECRAFT_1_13_2, 0x0E, system),
          Arguments.of(ProtocolVersion.MINECRAFT_1_15_2, 0x0F, system),
          Arguments.of(ProtocolVersion.MINECRAFT_1_16_4, 0x0E, systemNoSender),
          Arguments.of(ProtocolVersion.MINECRAFT_1_18_2, 0x0F, systemNoSender),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19, 0x5F, system),
          Arguments.of(ProtocolVersion.MINECRAFT_1_19_1, 0x62, notOverlay),
          Arguments.of(ProtocolVersion.MINECRAFT_1_20_1, 0x64, notOverlay),
          Arguments.of(ProtocolVersion.MINECRAFT_1_21_4, 0x73, notOverlay));
    }

    @ParameterizedTest(name = "{0}: id {1}")
    @MethodSource("versions")
    @DisplayName("should list the servers in the chat packet and layout of each version")
    void listsServers(ProtocolVersion version, int packetId, byte[] trailer) {
      Session session = join(version, Map.of("lobby", LOBBY));

      session.handler().handle(command(version, "server"));

      byte[] content = TextComponent.plainText("Servers: [lobby]", version);
      assertArrayEquals(concat(new byte[] {(byte) packetId}, content, trailer), session.toPlayer());
      assertNull(session.nextToPlayer(), "a single answer");
      assertNull(session.toBackend(), "the proxy's own command never reaches the backend");
    }

    @ParameterizedTest(name = "{0}: id {1}")
    @MethodSource("versions")
    @DisplayName("should name an unknown server in the chat packet and layout of each version")
    void unknownServer(ProtocolVersion version, int packetId, byte[] trailer) {
      Session session = join(version, Map.of("lobby", LOBBY));

      session.handler().handle(command(version, "server nowhere"));

      byte[] content = TextComponent.plainText("Unknown server: nowhere", version);
      assertArrayEquals(concat(new byte[] {(byte) packetId}, content, trailer), session.toPlayer());
      assertNull(session.toBackend());
    }

    @Test
    @DisplayName("should answer a 1.12.2 player with a JSON chat line in the system position")
    void exactLegacyAnswer() {
      Session session = join(ProtocolVersion.MINECRAFT_1_12_2, Map.of("lobby", LOBBY));

      session.handler().handle(new LegacyChatMessage("/server"));

      ByteBuf body = Unpooled.wrappedBuffer(session.toPlayer());
      assertEquals(0x0F, VarInt.read(body));
      assertEquals("{\"text\":\"Servers: [lobby]\"}", McString.read(body));
      assertEquals(1, body.readByte()); // system message
      assertFalse(body.isReadable(), "unread bytes");
    }

    @ParameterizedTest(name = "protocol {0}")
    @ValueSource(ints = {47, 340, 754, 758, 759, 760, 763})
    @DisplayName("should answer instead of switching a client that has no configuration phase")
    void noSwitchBefore1202(int protocol) {
      ProtocolVersion version = versionOf(protocol);
      Session session = join(version, Map.of("lobby", LOBBY, "survival", SURVIVAL));

      session.handler().handle(command(version, "server survival"));

      byte[] content =
          TextComponent.plainText("Switching servers needs Minecraft 1.20.2 or newer.", version);
      byte[] answer = session.toPlayer();
      assertArrayEquals(content, Arrays.copyOfRange(answer, 1, 1 + content.length));
      assertNull(session.nextToPlayer(), "no StartConfiguration");
      assertFalse(session.player().isSwitching());
    }

    @Test
    @DisplayName("should switch a 1.20.2+ player through the configuration phase")
    void switchesFrom1202() {
      Session session =
          join(ProtocolVersion.MINECRAFT_1_21_4, Map.of("lobby", LOBBY, "survival", SURVIVAL));

      session.handler().handle(new ChatCommand("server survival", new byte[0]));

      assertArrayEquals(new byte[] {0x70}, session.toPlayer()); // StartConfiguration
      assertTrue(session.player().isSwitching());
    }
  }

  // ---------------------------------------------------------------------------
  // Everything else goes to the backend
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("other chat")
  class OtherChat {

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"hello", "/servers", "/serverlist", "/help server", "server"})
    @DisplayName("should forward a pre-1.19 chat line that is not /server to the backend unchanged")
    void forwardsLegacyChat(String line) {
      Session session = join(ProtocolVersion.MINECRAFT_1_12_2, Map.of("lobby", LOBBY));

      session.handler().handle(new LegacyChatMessage(line));

      ByteBuf body = Unpooled.wrappedBuffer(session.toBackendOrFail());
      assertEquals(0x02, VarInt.read(body)); // 1.12.2 serverbound chat
      assertEquals(line, McString.read(body));
      assertFalse(body.isReadable(), "unread bytes");
      assertNull(session.nextToPlayer(), "no answer from the proxy");
    }

    @Test
    @DisplayName("should forward another 1.19.2 command with its signature data untouched")
    void forwardsKeyedCommand() {
      Session session = join(ProtocolVersion.MINECRAFT_1_19_1, Map.of("lobby", LOBBY));
      byte[] signatureData = {0, 0, 1, -117, -49, -27, 104, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 0, 0};

      session.handler().handle(new ChatCommand("help", signatureData));

      ByteBuf body = Unpooled.wrappedBuffer(session.toBackendOrFail());
      assertEquals(0x04, VarInt.read(body)); // 1.19.2 serverbound chat command
      assertEquals("help", McString.read(body));
      assertArrayEquals(signatureData, ByteBufUtil.getBytes(body));
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * A player in PLAY state on the lobby, with both of its connections.
   *
   * @param player the player
   * @param handler the handler under test, fed the player's packets
   * @param client the player's connection: what the proxy sends the player comes out here
   * @param backend the lobby connection: what the proxy forwards comes out here
   */
  private record Session(
      ConnectedPlayer player,
      ClientPlaySessionHandler handler,
      EmbeddedChannel client,
      EmbeddedChannel backend) {

    /** The next packet sent to the player, id first. */
    byte[] toPlayer() {
      byte[] packet = nextToPlayer();
      if (packet == null) {
        return fail("nothing sent to the player");
      }
      return packet;
    }

    byte @Nullable [] nextToPlayer() {
      return unframe(client);
    }

    /** The next packet forwarded to the backend, id first, once the read batch is flushed. */
    byte @Nullable [] toBackend() {
      handler.readComplete();
      return unframe(backend);
    }

    byte[] toBackendOrFail() {
      byte[] packet = toBackend();
      if (packet == null) {
        return fail("nothing forwarded to the backend");
      }
      return packet;
    }

    private static byte @Nullable [] unframe(EmbeddedChannel channel) {
      ByteBuf frame = channel.readOutbound();
      if (frame == null) {
        return null;
      }
      try {
        VarInt.read(frame); // frame length
        return ByteBufUtil.getBytes(frame);
      } finally {
        frame.release();
      }
    }
  }

  /** How a client of {@code version} sends a command: as a chat line before 1.19. */
  private static PlayPacket command(ProtocolVersion version, String command) {
    return version.isOlderThan(ProtocolVersion.MINECRAFT_1_19)
        ? new LegacyChatMessage("/" + command)
        : new ChatCommand(command, new byte[0]);
  }

  private Session join(ProtocolVersion version, Map<String, InetSocketAddress> servers) {
    EmbeddedChannel client = channel(PacketDirection.SERVERBOUND, version);
    EmbeddedChannel backend = channel(PacketDirection.CLIENTBOUND, version);

    ServerLoginContext loginContext = mock(ServerLoginContext.class);
    when(loginContext.serverRegistry()).thenReturn(new ServerRegistry(servers, "lobby", List.of()));
    ConnectedPlayer player =
        new ConnectedPlayer(
            connection(client),
            version,
            new GameProfile(UUID.randomUUID(), "Steve", List.of()),
            InetSocketAddress.createUnresolved("player", 50000),
            loginContext);
    player.setBackendConnection(backendConnection(connection(backend)));
    player.setCurrentServerName("lobby");
    return new Session(player, new ClientPlaySessionHandler(player), client, backend);
  }

  /** A pipeline like the proxy's, in PLAY state, receiving {@code inbound} packets. */
  private EmbeddedChannel channel(PacketDirection inbound, ProtocolVersion version) {
    PacketDirection outbound =
        inbound == PacketDirection.SERVERBOUND
            ? PacketDirection.CLIENTBOUND
            : PacketDirection.SERVERBOUND;
    EmbeddedChannel channel = new EmbeddedChannel();
    channel
        .pipeline()
        .addLast(
            ServerChannelInitializer.READ_TIMEOUT, new ReadTimeoutHandler(30, TimeUnit.SECONDS))
        .addLast(ServerChannelInitializer.FRAME_DECODER, new FrameDecoder())
        .addLast(
            ServerChannelInitializer.MINECRAFT_DECODER,
            new MinecraftDecoder(inbound, version, ProtocolState.PLAY))
        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(
            ServerChannelInitializer.MINECRAFT_ENCODER,
            new MinecraftEncoder(outbound, version, ProtocolState.PLAY))
        .addLast(ServerChannelInitializer.CONNECTION_HANDLER, new MinecraftConnection(channel));
    channels.add(channel);
    return channel;
  }

  private static MinecraftConnection connection(EmbeddedChannel channel) {
    return (MinecraftConnection)
        channel.pipeline().get(ServerChannelInitializer.CONNECTION_HANDLER);
  }

  private static BackendConnection backendConnection(MinecraftConnection connection) {
    try {
      var constructor =
          BackendConnection.class.getDeclaredConstructor(
              MinecraftConnection.class, InetSocketAddress.class);
      constructor.setAccessible(true);
      return constructor.newInstance(connection, LOBBY);
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("Failed to create test BackendConnection", e);
    }
  }

  private static ProtocolVersion versionOf(int protocol) {
    ProtocolVersion version = ProtocolVersion.byProtocolId(protocol);
    if (version == null) {
      throw new IllegalArgumentException("Unknown protocol " + protocol);
    }
    return version;
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }
}
