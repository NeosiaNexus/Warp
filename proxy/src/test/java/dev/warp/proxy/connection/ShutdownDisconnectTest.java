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
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.PlayDisconnect;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a player sees when the proxy shuts down, or a drain disconnects it. */
@DisplayName("Disconnecting on shutdown")
class ShutdownDisconnectTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

  private final List<SwitchHarness> harnesses = new ArrayList<>();

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
  }

  @Test
  @DisplayName("should show the reason given, such as the drain's message")
  void reason() {
    SwitchHarness setup = playingOnLobby();

    setup.player.disconnectShutdown("Back in a minute");
    setup.runPendingTasks();

    List<SwitchHarness.Sent> sent = setup.sentToClient();
    assertEquals(List.of(PlayDisconnect.class), types(sent));
    assertTrue(text(sent.getFirst()).contains("Back in a minute"), text(sent.getFirst()));
  }

  @Test
  @DisplayName("should close an open bundle first, or the client would never show the reason")
  void closesBundle() {
    SwitchHarness setup = playingOnLobby();
    setup.receive(setup.current(), frame(VERSION, new BundleDelimiter()));
    setup.sentToClient();

    setup.player.disconnectShutdown();
    setup.runPendingTasks();

    assertEquals(List.of(BundleDelimiter.class, PlayDisconnect.class), types(setup.sentToClient()));
  }

  private static String text(SwitchHarness.Sent sent) {
    return new String(sent.body(), StandardCharsets.UTF_8);
  }

  private SwitchHarness playingOnLobby() {
    SwitchHarness setup = new SwitchHarness(VERSION, List.of(), ProtocolState.PLAY);
    harnesses.add(setup);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    setup.player.setCurrentServerName(LOBBY.name());
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    return setup;
  }
}
