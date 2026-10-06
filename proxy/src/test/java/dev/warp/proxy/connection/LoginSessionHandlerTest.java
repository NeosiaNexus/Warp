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

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.config.ForwardingMode;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drives the client login through the pipeline {@link ServerChannelInitializer} builds, and reads
 * what the proxy sends back as a client would.
 */
@DisplayName("LoginSessionHandler")
class LoginSessionHandlerTest {

  private static final int LOGIN_NEXT_STATE = 2;

  private static final KeyPair RSA_KEY_PAIR = rsaKeyPair();

  // ---------------------------------------------------------------------------
  // Disconnect reasons
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("disconnect reasons")
  class DisconnectReasons {

    static Stream<ProtocolVersion> allVersions() {
      return ProtocolVersion.values().stream();
    }

    static Stream<ProtocolVersion> versionsBefore1202() {
      return ProtocolVersion.values().stream()
          .filter(version -> version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_2));
    }

    static Stream<ProtocolVersion> versionsFrom1203() {
      return ProtocolVersion.values().stream()
          .filter(version -> version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVersions")
    @DisplayName("should refuse a login with a JSON string reason in every version")
    void loginStateJson(ProtocolVersion version) {
      EmbeddedChannel channel = loginChannel(version);
      try {
        // "x" is shorter than any valid username.
        sendLoginStart(channel, "x", version);

        byte[] reason = nextPacket(channel, ProtocolState.LOGIN, LoginDisconnect.class, version);
        assertArrayEquals(jsonString("{\"text\":\"Invalid username\"}"), reason);
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsBefore1202")
    @DisplayName("should report an unreachable server in the play state with a JSON string")
    void playStateJson(ProtocolVersion version) {
      EmbeddedChannel channel = loginChannel(version);
      try {
        sendLoginStart(channel, "Steve", version);
        channel.runPendingTasks();

        nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, version);
        byte[] reason = nextPacket(channel, ProtocolState.PLAY, PlayDisconnect.class, version);
        assertArrayEquals(jsonString("{\"text\":\"Could not connect to backend server\"}"), reason);
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should report an unreachable server in the configuration state on 1.20.2 as JSON")
    void configurationStateJson() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_20_2;
      EmbeddedChannel channel = configurationChannel(version);
      try {
        byte[] reason =
            nextPacket(channel, ProtocolState.CONFIGURATION, ConfigDisconnect.class, version);
        assertArrayEquals(
            jsonString("{\"text\":\"Could not connect to any available server.\"}"), reason);
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionsFrom1203")
    @DisplayName("should report an unreachable server in the configuration state as NBT")
    void configurationStateNbt(ProtocolVersion version) {
      EmbeddedChannel channel = configurationChannel(version);
      try {
        byte[] reason =
            nextPacket(channel, ProtocolState.CONFIGURATION, ConfigDisconnect.class, version);
        assertArrayEquals(nbtString("Could not connect to any available server."), reason);
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    /** Logs in offline, acknowledges the login and returns once the backend connection failed. */
    private EmbeddedChannel configurationChannel(ProtocolVersion version) {
      EmbeddedChannel channel = loginChannel(version);
      sendLoginStart(channel, "Steve", version);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, version);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), version);
      channel.runPendingTasks();
      return channel;
    }
  }

  // ---------------------------------------------------------------------------
  // Pipeline
  // ---------------------------------------------------------------------------

  /**
   * A client connection that has completed the handshake into the LOGIN state, on an offline-mode
   * proxy whose only server is unreachable.
   */
  private EmbeddedChannel loginChannel(ProtocolVersion version) {
    EmbeddedChannel channel = new ClientChannel();
    channel.pipeline().addLast(new ServerChannelInitializer(loginContext()));
    channel.runPendingTasks();
    send(
        channel,
        ProtocolState.HANDSHAKE,
        new Handshake(version.protocol(), "localhost", 25577, LOGIN_NEXT_STATE),
        version);
    return channel;
  }

  /**
   * An offline-mode context with one server and no compression.
   *
   * <p>Connecting to the server fails at once: an NIO channel cannot register with the embedded
   * event loop the client connection runs on, which the login handler sees as an unreachable
   * server.
   */
  private ServerLoginContext loginContext() {
    ServerRegistry registry =
        new ServerRegistry(
            Map.of("lobby", new InetSocketAddress("127.0.0.1", 1)), "lobby", List.of("lobby"));
    return new ServerLoginContext(
        RSA_KEY_PAIR,
        false,
        -1,
        -1,
        true,
        new MojangSessionService(),
        registry,
        ForwardingMode.NONE,
        new byte[0],
        NioSocketChannel.class);
  }

  /** Writes {@code packet} to the proxy as a client would, in one frame. */
  @SuppressWarnings("unchecked")
  private <T extends Packet> void send(
      EmbeddedChannel channel, ProtocolState state, T packet, ProtocolVersion version) {
    var encoding =
        StateRegistry.get(state, PacketDirection.SERVERBOUND).encoding(version, packet.getClass());
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, encoding.packetId());
    ((PacketCodec<T>) encoding.codec()).encode(packet, body, version);
    writeFrame(channel, body);
  }

  /** Writes {@code body} (packet id and fields) to the proxy in one frame, and releases it. */
  private void writeFrame(EmbeddedChannel channel, ByteBuf body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    channel.writeInbound(frame);
  }

  /**
   * Writes a Login Start as a client of {@code version} does, written out here rather than with the
   * codec under test: the name, then from 1.19 a "no signature data" flag (until 1.19.2) and the
   * player's UUID (from 1.19.1, behind a presence flag until 1.20.1).
   */
  private void sendLoginStart(EmbeddedChannel channel, String name, ProtocolVersion version) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)
        && version.isOlderThan(ProtocolVersion.MINECRAFT_1_19_3)) {
      body.writeBoolean(false);
    }
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_2)) {
        body.writeBoolean(true);
      }
      McUuid.write(body, UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    }
    writeFrame(channel, body);
  }

  /**
   * Reads the next frame the proxy sent, checks that it holds exactly one {@code type} packet, and
   * returns the packet's body.
   */
  private byte[] nextPacket(
      EmbeddedChannel channel,
      ProtocolState state,
      Class<? extends Packet> type,
      ProtocolVersion version) {
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame, "expected a " + type.getSimpleName());
    try {
      assertEquals(VarInt.read(frame), frame.readableBytes(), "frame length");
      int expectedId =
          StateRegistry.get(state, PacketDirection.CLIENTBOUND).packetId(version, type);
      assertEquals(expectedId, VarInt.read(frame), type.getSimpleName() + " packet id");
      return ByteBufUtil.getBytes(frame);
    } finally {
      frame.release();
    }
  }

  private void assertClosed(EmbeddedChannel channel) {
    channel.runPendingTasks();
    assertFalse(channel.isOpen(), "the connection must close after the reason");
  }

  // ---------------------------------------------------------------------------
  // Expected wire formats, written out independently of the code under test
  // ---------------------------------------------------------------------------

  /** A protocol string: VarInt byte length, then UTF-8. */
  private byte[] jsonString(String json) {
    byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
    ByteBuf buf = Unpooled.buffer();
    try {
      VarInt.write(buf, utf8.length);
      buf.writeBytes(utf8);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  /** A network NBT string tag holding ASCII text: type 8, unsigned-short length, the bytes. */
  private byte[] nbtString(String ascii) {
    byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
    ByteBuf buf = Unpooled.buffer();
    try {
      buf.writeByte(0x08);
      buf.writeShort(bytes.length);
      buf.writeBytes(bytes);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  private static KeyPair rsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new AssertionError("RSA not available", e);
    }
  }

  /** An embedded channel with the socket address of a real client connection. */
  private static final class ClientChannel extends EmbeddedChannel {

    private static final InetSocketAddress CLIENT_ADDRESS =
        new InetSocketAddress("127.0.0.1", 50_000);

    @Override
    protected SocketAddress remoteAddress0() {
      return CLIENT_ADDRESS;
    }
  }
}
