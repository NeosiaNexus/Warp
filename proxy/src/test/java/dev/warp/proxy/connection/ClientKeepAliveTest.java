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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameEncoder;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.play.KeepAlive;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests the keep-alive cycle between Warp and a client, over the real codecs of each version.
 *
 * <p>Warp's end of the connection is a PLAY pipeline driven by the production {@link
 * ClientPlaySessionHandler}; the client's end decodes what Warp sends and echoes the id, as a
 * vanilla client does. Warp only sends a keep-alive once the previous one was answered, so every
 * tick that produces a new keep-alive proves the previous echo was accepted.
 */
@DisplayName("Client keep-alive")
class ClientKeepAliveTest {

  /** Interval of Warp's keep-alive task. */
  private static final long INTERVAL_SECONDS = 15;

  /** Round trips per test: two minutes of play, far past the 30 s keep-alive time-out. */
  private static final int ROUND_TRIPS = 8;

  private final EmbeddedChannel warpSide = new EmbeddedChannel();
  private final EmbeddedChannel clientSide = new EmbeddedChannel();

  static Stream<ProtocolVersion> versionsAcrossIdWidths() {
    return Stream.of(
        ProtocolVersion.MINECRAFT_1_7_2,
        ProtocolVersion.MINECRAFT_1_8,
        ProtocolVersion.MINECRAFT_1_12_1,
        ProtocolVersion.MINECRAFT_1_12_2,
        ProtocolVersion.latest());
  }

  static Stream<ProtocolVersion> versionsWithIntId() {
    return Stream.of(
        ProtocolVersion.MINECRAFT_1_7_2,
        ProtocolVersion.MINECRAFT_1_8,
        ProtocolVersion.MINECRAFT_1_12_1);
  }

  static Stream<ProtocolVersion> versionsWithLongId() {
    return Stream.of(ProtocolVersion.MINECRAFT_1_12_2, ProtocolVersion.latest());
  }

  @AfterEach
  void tearDown() {
    warpSide.finishAndReleaseAll();
    clientSide.finishAndReleaseAll();
  }

  // ---------------------------------------------------------------------------
  // Round trips
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Round trips with a client of each version")
  class RoundTrips {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.ClientKeepAliveTest#versionsAcrossIdWidths")
    @DisplayName("should accept every echo and keep the client connected")
    void acceptsEveryEcho(ProtocolVersion version) {
      connectPlayer(version);

      for (int i = 0; i < ROUND_TRIPS; i++) {
        echo(nextKeepAlive());
      }

      assertTrue(warpSide.isActive(), "a client answering every keep-alive must stay connected");
      assertNotNull(nextKeepAlive(), "the last echo must have been accepted too");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.ClientKeepAliveTest#versionsWithIntId")
    @DisplayName("should send a client before 1.12.2 ids that fit a non-negative int")
    void sendsNonNegativeIntIds(ProtocolVersion version) {
      connectPlayer(version);

      long[] ids = roundTripIds();

      for (long id : ids) {
        assertTrue(id >= 0 && id <= Integer.MAX_VALUE, version + " id " + id);
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.ClientKeepAliveTest#versionsWithLongId")
    @DisplayName("should draw ids from the whole 64-bit range from 1.12.2")
    void sendsLongIds(ProtocolVersion version) {
      connectPlayer(version);

      long[] ids = roundTripIds();

      // All eight random longs landing in the int range has a probability of 2^-256.
      assertTrue(
          LongStream.of(ids).anyMatch(id -> id != (int) id),
          version + " ids never left the int range");
    }
  }

  // ---------------------------------------------------------------------------
  // Wrong echo
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Echo of another id")
  class WrongEcho {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.ClientKeepAliveTest#versionsAcrossIdWidths")
    @DisplayName("should keep waiting for the pending id and send no new keep-alive meanwhile")
    void keepsWaitingForPendingId(ProtocolVersion version) {
      connectPlayer(version);
      KeepAlive pending = nextKeepAlive();

      echo(new KeepAlive(pending.id() ^ 1));
      advanceOneTick();

      assertNull(warpSide.readOutbound(), "no keep-alive may be sent while one is pending");
      echo(pending);
      assertNotNull(nextKeepAlive(), "the right echo must still be accepted");
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Puts a player of {@code version} in PLAY, with a client of the same version on the wire. */
  private void connectPlayer(ProtocolVersion version) {
    addCodecs(warpSide, PacketDirection.SERVERBOUND, PacketDirection.CLIENTBOUND, version);
    addCodecs(clientSide, PacketDirection.CLIENTBOUND, PacketDirection.SERVERBOUND, version);
    MinecraftConnection connection = new MinecraftConnection(warpSide);
    warpSide.pipeline().addLast(ServerChannelInitializer.CONNECTION_HANDLER, connection);
    ConnectedPlayer player =
        new ConnectedPlayer(
            connection,
            version,
            new GameProfile(UUID.randomUUID(), "KeepAliveTester", List.of()),
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 25565),
            mock(ServerLoginContext.class));
    // Entering PLAY starts the keep-alive task.
    connection.setSessionHandler(new ClientPlaySessionHandler(player));
  }

  private static void addCodecs(
      EmbeddedChannel channel,
      PacketDirection decodes,
      PacketDirection encodes,
      ProtocolVersion version) {
    channel
        .pipeline()
        .addLast(ServerChannelInitializer.FRAME_DECODER, new FrameDecoder())
        .addLast(
            ServerChannelInitializer.MINECRAFT_DECODER,
            new MinecraftDecoder(decodes, version, ProtocolState.PLAY))
        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(
            ServerChannelInitializer.MINECRAFT_ENCODER,
            new MinecraftEncoder(encodes, version, ProtocolState.PLAY));
  }

  /** Lets one keep-alive interval pass on Warp's side. */
  private void advanceOneTick() {
    warpSide.advanceTimeBy(INTERVAL_SECONDS, TimeUnit.SECONDS);
    warpSide.runScheduledPendingTasks();
  }

  /** Lets one interval pass and returns the keep-alive the client decodes; fails if none came. */
  private KeepAlive nextKeepAlive() {
    advanceOneTick();
    ByteBuf wire = warpSide.readOutbound();
    assertNotNull(wire, "Warp sent no keep-alive: it refused the previous echo");
    assertNull(warpSide.readOutbound(), "one keep-alive per interval");
    assertTrue(clientSide.writeInbound(wire));
    return assertInstanceOf(KeepAlive.class, clientSide.readInbound());
  }

  /** Sends {@code answer} from the client to Warp, encoded and decoded as on a real connection. */
  private void echo(KeepAlive answer) {
    assertTrue(clientSide.writeOutbound(answer));
    ByteBuf wire = clientSide.readOutbound();
    assertFalse(warpSide.writeInbound(wire), "the session handler must consume the echo");
  }

  /** Runs {@link #ROUND_TRIPS} round trips and returns the ids the client received. */
  private long[] roundTripIds() {
    long[] ids = new long[ROUND_TRIPS];
    for (int i = 0; i < ROUND_TRIPS; i++) {
      KeepAlive received = nextKeepAlive();
      ids[i] = received.id();
      echo(received);
    }
    return ids;
  }
}
