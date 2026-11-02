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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginSuccess;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drives the handshake through the pipeline {@link ServerChannelInitializer} builds: the intent a
 * client declares, and transfers (intent 3) in particular.
 */
@DisplayName("HandshakeSessionHandler")
class HandshakeSessionHandlerTest {

  private static final int STATUS = 1;
  private static final int LOGIN = 2;
  private static final int TRANSFER = 3;

  static Stream<ProtocolVersion> versionsWithTransfers() {
    return ProtocolVersion.values().stream()
        .filter(v -> v.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5));
  }

  static Stream<ProtocolVersion> versionsBeforeTransfers() {
    return ProtocolVersion.values().stream()
        .filter(v -> v.isOlderThan(ProtocolVersion.MINECRAFT_1_20_5));
  }

  // ---------------------------------------------------------------------------
  // Transfers
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("transfer intent")
  class Transfers {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.HandshakeSessionHandlerTest#versionsWithTransfers")
    @DisplayName("should log a transferred player in when transfers are accepted")
    void acceptsTransfer(ProtocolVersion version) {
      EmbeddedChannel channel = channel(true);
      try {
        handshake(channel, version, TRANSFER);
        sendLoginStart(channel, "Steve");

        nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, version);
        assertEquals(HandshakeIntent.TRANSFER, connection(channel).handshakeIntent());
        assertInstanceOf(LoginSessionHandler.class, connection(channel).sessionHandler());
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.HandshakeSessionHandlerTest#versionsWithTransfers")
    @DisplayName("should refuse a transfer with vanilla's translated reason when they are off")
    void refusesTransfer(ProtocolVersion version) {
      EmbeddedChannel channel = channel(false);
      try {
        handshake(channel, version, TRANSFER);

        byte[] reason = nextPacket(channel, ProtocolState.LOGIN, LoginDisconnect.class, version);
        assertArrayEquals(
            string("{\"translate\":\"multiplayer.disconnect.transfers_disabled\"}"), reason);
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.HandshakeSessionHandlerTest#versionsBeforeTransfers")
    @DisplayName("should close a transfer from a version before 1.20.5, even when accepted")
    void closesTransferBefore1205(ProtocolVersion version) {
      EmbeddedChannel channel = channel(true);
      try {
        handshake(channel, version, TRANSFER);

        assertNull(channel.readOutbound(), "no client of this version reads a reason");
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should tell a transfer from an unknown protocol its version, as a login from one")
    void refusesUnknownProtocol() {
      EmbeddedChannel channel = channel(false);
      try {
        writeHandshake(channel, 99_999, TRANSFER);

        String reason = reason(channel);
        assertTrue(reason.contains(HandshakeSessionHandler.OUTDATED_SERVER_KEY), reason);
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Unsupported versions and invalid handshakes
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("unsupported versions and invalid handshakes")
  class Refusals {

    @Test
    @DisplayName("should tell a newer client the server is outdated, with the versions that join")
    void newerClient() {
      EmbeddedChannel channel = channel(false);
      try {
        writeHandshake(channel, 99_999, LOGIN);

        String reason = reason(channel);
        assertTrue(reason.contains(HandshakeSessionHandler.OUTDATED_SERVER_KEY), reason);
        assertTrue(reason.contains(HandshakeSessionHandler.SUPPORTED_VERSIONS), reason);
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should tell an older client it is outdated, with the versions that join")
    void olderClient() {
      EmbeddedChannel channel = channel(false);
      try {
        writeHandshake(channel, 3, LOGIN);

        String reason = reason(channel);
        assertTrue(reason.contains(HandshakeSessionHandler.OUTDATED_CLIENT_KEY), reason);
        assertTrue(reason.contains(HandshakeSessionHandler.SUPPORTED_VERSIONS), reason);
        // Read as the oldest version, a 1.7 client, which keeps its connection a little longer.
        channel.advanceTimeBy(MinecraftConnection.LEGACY_CLOSE_DELAY_MS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "next state {0}")
    @ValueSource(ints = {0, 4, -1})
    @DisplayName("should reset a handshake with an unknown next state, unanswered")
    void unknownIntent(int nextState) {
      EmbeddedChannel channel = channel(false);
      try {
        writeHandshake(channel, ProtocolVersion.latest().protocol(), nextState);

        assertNull(channel.readOutbound());
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Other intents
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("other intents")
  class OtherIntents {

    @Test
    @DisplayName("should record a login, and not take it for a transfer")
    void recordsLogin() {
      EmbeddedChannel channel = channel(true);
      try {
        handshake(channel, ProtocolVersion.MINECRAFT_1_21_4, LOGIN);

        assertEquals(HandshakeIntent.LOGIN, connection(channel).handshakeIntent());
        assertInstanceOf(LoginSessionHandler.class, connection(channel).sessionHandler());
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @Test
    @DisplayName("should record a server list ping and answer it as before")
    void recordsStatus() {
      EmbeddedChannel channel = channel(false);
      try {
        handshake(channel, ProtocolVersion.MINECRAFT_1_21_4, STATUS);

        assertEquals(HandshakeIntent.STATUS, connection(channel).handshakeIntent());
        assertInstanceOf(StatusSessionHandler.class, connection(channel).sessionHandler());
      } finally {
        channel.finishAndReleaseAll();
      }
    }

    @ParameterizedTest(name = "nextState {0}")
    @ValueSource(ints = {0, 4, -1})
    @DisplayName("should close a handshake with an intent no client declares")
    void closesUnknownIntent(int nextState) {
      EmbeddedChannel channel = channel(true);
      MinecraftConnection connection = connection(channel);
      try {
        handshake(channel, ProtocolVersion.MINECRAFT_1_21_4, nextState);

        assertNull(channel.readOutbound());
        assertNull(connection.handshakeIntent());
        assertClosed(channel);
      } finally {
        channel.finishAndReleaseAll();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** A client connection on an offline proxy that accepts transfers or not. */
  private EmbeddedChannel channel(boolean acceptsTransfers) {
    EmbeddedChannel channel = new ClientChannel();
    channel
        .pipeline()
        .addLast(
            new ServerChannelInitializer(
                TestLoginContexts.offlineAcceptingTransfers(acceptsTransfers)));
    channel.runPendingTasks();
    return channel;
  }

  private MinecraftConnection connection(EmbeddedChannel channel) {
    MinecraftConnection connection = channel.pipeline().get(MinecraftConnection.class);
    assertNotNull(connection);
    return connection;
  }

  private void handshake(EmbeddedChannel channel, ProtocolVersion version, int nextState) {
    writeHandshake(channel, version.protocol(), nextState);
  }

  /** Writes a Handshake as a client does: id 0, protocol, host, port, next state. */
  /** Reads the Login Disconnect the proxy wrote and returns its JSON reason. */
  private String reason(EmbeddedChannel channel) {
    ByteBuf reason =
        Unpooled.wrappedBuffer(
            nextPacket(
                channel, ProtocolState.LOGIN, LoginDisconnect.class, ProtocolVersion.latest()));
    try {
      return McString.read(reason);
    } finally {
      reason.release();
    }
  }

  private void writeHandshake(EmbeddedChannel channel, int protocol, int nextState) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    VarInt.write(body, protocol);
    McString.write(body, "play.primemc.net");
    body.writeShort(25577);
    VarInt.write(body, nextState);
    writeFrame(channel, body);
  }

  /** Writes a Login Start as a 1.20.2+ client does: the name, then the player's UUID. */
  private void sendLoginStart(EmbeddedChannel channel, String name) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    McUuid.write(body, UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    writeFrame(channel, body);
  }

  private void writeFrame(EmbeddedChannel channel, ByteBuf body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    channel.writeInbound(frame);
  }

  /** Reads the next frame, checks that it holds one {@code type} packet, and returns its body. */
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
    assertFalse(channel.isOpen(), "the connection must be closed");
  }

  /** A protocol string: VarInt byte length, then UTF-8. */
  private byte[] string(String text) {
    ByteBuf buf = Unpooled.buffer();
    try {
      McString.write(buf, text);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  /** An embedded channel with the socket address of a real client connection. */
  private static final class ClientChannel extends EmbeddedChannel {

    private static final InetSocketAddress CLIENT_ADDRESS =
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 50_000);

    @Override
    protected SocketAddress remoteAddress0() {
      return CLIENT_ADDRESS;
    }
  }
}
