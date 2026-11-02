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

import static dev.warp.proxy.connection.SwitchHarness.LOBBY;
import static dev.warp.proxy.connection.SwitchHarness.SURVIVAL;
import static dev.warp.proxy.connection.SwitchHarness.frame;
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.cookie.StoreCookie;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.Transfer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Transfers: players who arrive by one, the proxy sending one, and the servers' own. */
@DisplayName("Transfers")
class TransferTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

  private final List<SwitchHarness> harnesses = new ArrayList<>();

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
  }

  // ---------------------------------------------------------------------------
  // Arriving by a transfer
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("arriving by a transfer")
  class Arriving {

    @Test
    @DisplayName("should tell a transferred player from one who connected directly")
    void recordsTransfer() {
      ConnectedPlayer transferred = playerWhoseHandshakeSaid(HandshakeIntent.TRANSFER);
      ConnectedPlayer direct = playerWhoseHandshakeSaid(HandshakeIntent.LOGIN);

      assertTrue(transferred.isTransferred());
      assertFalse(direct.isTransferred());
    }

    @Test
    @DisplayName("should not take a player whose connection has no handshake for a transferred one")
    void noHandshake() {
      assertFalse(playerWhoseHandshakeSaid(null).isTransferred());
    }

    private ConnectedPlayer playerWhoseHandshakeSaid(@Nullable HandshakeIntent intent) {
      EmbeddedChannel channel = new EmbeddedChannel();
      MinecraftConnection connection = new MinecraftConnection(channel);
      if (intent != null) {
        connection.setHandshakeIntent(intent);
      }
      ConnectedPlayer player =
          new ConnectedPlayer(
              connection,
              VERSION,
              new GameProfile(UUID.randomUUID(), "Steve", List.of()),
              new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000),
              mock(ServerLoginContext.class),
              new ClientCookies(connection, VERSION, channel.eventLoop()));
      channel.finishAndReleaseAll();
      return player;
    }
  }

  // ---------------------------------------------------------------------------
  // Sending a transfer
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("sending a transfer")
  class Sending {

    /** Every version with transfers, in both states that have the packet. */
    static Stream<Arguments> versionsAndStates() {
      return ProtocolVersion.values().stream()
          .filter(v -> v.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5))
          .flatMap(
              v ->
                  Stream.of(
                      Arguments.of(v, ProtocolState.CONFIGURATION),
                      Arguments.of(v, ProtocolState.PLAY)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("versionsAndStates")
    @DisplayName("should send the host and port at the id of the client's version and state")
    void sendsTransfer(ProtocolVersion version, ProtocolState state) {
      SwitchHarness setup = harness(version, state);

      setup.player.transfer("play.primemc.net", 25565);

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(List.of(Transfer.class), types(sent));
      assertEquals(new Transfer("play.primemc.net", 25565), sent.getFirst().as(Transfer.class));
    }

    @Test
    @DisplayName("should close an open bundle first, so that the client acts on it at once")
    void closesBundle() {
      SwitchHarness setup = playingOnLobby();
      setup.receive(setup.current(), frame(VERSION, new BundleDelimiter()));
      setup.sentToClient();

      setup.player.transfer("play.primemc.net", 25565);

      assertEquals(List.of(BundleDelimiter.class, Transfer.class), types(setup.sentToClient()));
    }

    @Test
    @DisplayName("should wait for the client to acknowledge a state change before sending it")
    void heldDuringStateChange() {
      SwitchHarness setup = playingOnLobby();
      setup.player.switchServer(SURVIVAL);
      setup.sentToClient();

      setup.player.transfer("play.primemc.net", 25565);
      boolean held = setup.sentToClient().isEmpty();
      setup.client.setState(ProtocolState.CONFIGURATION); // the client acknowledged

      assertTrue(held);
      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(List.of(Transfer.class), types(sent));
      assertEquals(ProtocolState.CONFIGURATION, sent.getFirst().state());
    }

    @Test
    @DisplayName("should refuse a client older than 1.20.5, saying why")
    void refusesOldClient() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_20_4, ProtocolState.PLAY);

      UnsupportedOperationException refused =
          assertThrows(
              UnsupportedOperationException.class,
              () -> setup.player.transfer("play.primemc.net", 25565));
      assertEquals(
          "Cannot transfer Steve: transfers need Minecraft 1.20.5 or newer, the client plays"
              + " 1.20.4",
          refused.getMessage());
    }

    @Test
    @DisplayName("should refuse a blank host and a port out of range")
    void refusesInvalidTarget() {
      SwitchHarness setup = playingOnLobby();

      assertThrows(IllegalArgumentException.class, () -> setup.player.transfer(" ", 25565));
      assertThrows(IllegalArgumentException.class, () -> setup.player.transfer("a", 0));
      assertThrows(IllegalArgumentException.class, () -> setup.player.transfer("a", 65536));
      assertTrue(setup.sentToClient().isEmpty());
    }

    @Test
    @DisplayName("should refuse a client in a state without transfers")
    void refusesLoginState() {
      SwitchHarness setup = harness(VERSION, ProtocolState.LOGIN);

      assertThrows(
          IllegalStateException.class, () -> setup.player.transfer("play.primemc.net", 25565));
      assertTrue(setup.sentToClient().isEmpty());
    }
  }

  // ---------------------------------------------------------------------------
  // A server's own transfers and cookies
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("a server's own transfers and stored cookies")
  class FromServers {

    @Test
    @DisplayName("should pass a transfer the server sends in play to the client untouched")
    void transferInPlay() {
      SwitchHarness setup = playingOnLobby();

      assertForwardedUntouched(setup, ProtocolState.PLAY, new Transfer("play.other.net", 25566));
    }

    @Test
    @DisplayName("should pass a transfer the server sends in configuration to the client untouched")
    void transferInConfiguration() {
      SwitchHarness setup = configuringOnLobby();

      assertForwardedUntouched(
          setup, ProtocolState.CONFIGURATION, new Transfer("play.other.net", 25566));
    }

    @Test
    @DisplayName("should pass a cookie the server stores in play to the client untouched")
    void storeInPlay() {
      SwitchHarness setup = playingOnLobby();

      assertForwardedUntouched(
          setup, ProtocolState.PLAY, new StoreCookie("lobby:visits", new byte[] {4, 2}));
    }

    @Test
    @DisplayName("should pass a cookie the server stores in configuration to the client untouched")
    void storeInConfiguration() {
      SwitchHarness setup = configuringOnLobby();

      assertForwardedUntouched(
          setup, ProtocolState.CONFIGURATION, new StoreCookie("lobby:visits", new byte[] {4, 2}));
    }

    private void assertForwardedUntouched(SwitchHarness setup, ProtocolState state, Packet packet) {
      ByteBuf sent = frame(VERSION, state, PacketDirection.CLIENTBOUND, packet);
      byte[] bytes = ByteBufUtil.getBytes(sent);

      setup.receive(setup.current(), sent);

      List<byte[]> received = setup.framesToClient();
      assertEquals(1, received.size());
      assertArrayEquals(bytes, received.getFirst());
    }

    /** A player whose client and server are both in the configuration phase. */
    private SwitchHarness configuringOnLobby() {
      SwitchHarness setup = harness(VERSION, ProtocolState.CONFIGURATION);
      MinecraftConnection lobby = setup.backend(ProtocolState.CONFIGURATION);
      setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
      lobby.setSessionHandler(new BackendConfigSessionHandler(setup.player, lobby));
      setup.runPendingTasks();
      return setup;
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private SwitchHarness harness(ProtocolVersion version, ProtocolState clientState) {
    SwitchHarness setup = new SwitchHarness(version, List.of(), clientState);
    harnesses.add(setup);
    return setup;
  }

  /** A player playing on the lobby, both sides in PLAY. */
  private SwitchHarness playingOnLobby() {
    SwitchHarness setup = harness(VERSION, ProtocolState.PLAY);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    setup.player.setCurrentServerName(LOBBY.name());
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    return setup;
  }
}
