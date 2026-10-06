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
import static dev.warp.proxy.connection.SwitchHarness.frame;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.JoinGame;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("BackendPlaySessionHandler")
class BackendPlaySessionHandlerTest {

  @Nested
  @DisplayName("Join Game")
  class JoinGameOnlineMode {

    /**
     * The end of a backend's Join Game after the hardcore flag: some fields, then, from 26.2, the
     * online mode flag ({@code false}: the backend runs offline behind the proxy) and the enforces
     * secure chat flag ({@code true}).
     */
    private static final byte[] FROM_OFFLINE_BACKEND = {0x03, 0x05, 0x00, 0x01};

    /** The proxy's online mode, and the bytes the client receives after the hardcore flag. */
    static Stream<Arguments> forwardedBodies() {
      byte[] online = {0x03, 0x05, 0x01, 0x01};
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_26_2, true, online),
          Arguments.of(ProtocolVersion.MINECRAFT_26_3, true, online),
          Arguments.of(ProtocolVersion.MINECRAFT_26_3, false, FROM_OFFLINE_BACKEND),
          // No online mode flag before 26.2: these bytes are other fields, kept as they are.
          Arguments.of(ProtocolVersion.MINECRAFT_26_1, true, FROM_OFFLINE_BACKEND));
    }

    @ParameterizedTest(name = "{0}, online proxy: {1}")
    @MethodSource("forwardedBodies")
    @DisplayName("should tell the client the proxy's online mode, not the backend's, from 26.2")
    void proxyOnlineMode(ProtocolVersion version, boolean proxyOnline, byte[] expected) {
      try (SwitchHarness setup = new SwitchHarness(version, List.of(), ProtocolState.PLAY)) {
        when(setup.player.loginContext().onlineMode()).thenReturn(proxyOnline);
        MinecraftConnection lobby = playingOn(setup);

        setup.receive(
            lobby,
            frame(version, new JoinGame(7, false, new JoinGame.Opaque(FROM_OFFLINE_BACKEND))));

        List<SwitchHarness.Sent> sent = setup.sentToClient();
        assertEquals(1, sent.size());
        JoinGame forwarded = sent.getFirst().as(JoinGame.class);
        assertEquals(7, forwarded.entityId());
        assertArrayEquals(expected, ((JoinGame.Opaque) forwarded.body()).bytes());
      }
    }

    /** Hands the player a backend in PLAY, as when its configuration phase ends. */
    private MinecraftConnection playingOn(SwitchHarness setup) {
      MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
      setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
      lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
      return lobby;
    }
  }
}
