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

import static dev.warp.proxy.connection.SwitchHarness.CREATIVE;
import static dev.warp.proxy.connection.SwitchHarness.LOBBY;
import static dev.warp.proxy.connection.SwitchHarness.SURVIVAL;
import static dev.warp.proxy.connection.SwitchHarness.frame;
import static dev.warp.proxy.connection.SwitchHarness.opaqueFrame;
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.play.BundleDelimiter;
import dev.warp.protocol.packet.play.StartConfiguration;
import dev.warp.protocol.packet.play.SystemChatMessage;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Server switches from 1.20.2, through the configuration phase: the behaviour the respawn switch
 * must leave unchanged.
 */
@DisplayName("Reconfiguration server switch (1.20.2 and newer)")
class ReconfigurationSwitchTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

  private final List<SwitchHarness> harnesses = new ArrayList<>();

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
  }

  /** A player playing on the lobby, with {@code fallbackOrder}. */
  private SwitchHarness playingOnLobby(String... fallbackOrder) {
    SwitchHarness setup = new SwitchHarness(VERSION, List.of(fallbackOrder), ProtocolState.PLAY);
    harnesses.add(setup);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    setup.player.setCurrentServerName(LOBBY.name());
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    return setup;
  }

  @Test
  @DisplayName(
      "should close an open bundle, send Start Configuration, then drop the server's packets")
  void startConfiguration() {
    SwitchHarness setup = playingOnLobby();
    MinecraftConnection lobby = setup.current();
    setup.receive(lobby, frame(VERSION, new BundleDelimiter()));
    setup.sentToClient();

    setup.player.switchServer(SURVIVAL);
    setup.receive(lobby, opaqueFrame(0x22));

    assertEquals(
        List.of(BundleDelimiter.class, StartConfiguration.class), types(setup.sentToClient()));
    assertTrue(setup.player.isSwitching());
    assertFalse(setup.player.isPlayingOn(lobby));
  }

  @Test
  @DisplayName("should ignore the server being left until the client acknowledges")
  void serverLeftBeforeAcknowledgement() {
    SwitchHarness setup = playingOnLobby();
    MinecraftConnection lobby = setup.current();
    setup.player.switchServer(SURVIVAL);

    lobby.close();
    setup.runPendingTasks();

    assertTrue(setup.player.isSwitching());
    assertEquals(SURVIVAL, setup.player.pendingSwitchTarget());
    assertTrue(setup.client.channel().isActive());
  }

  @Test
  @DisplayName("should move a player whose server went down through the configuration phase")
  void serverDown() {
    SwitchHarness setup = playingOnLobby(SURVIVAL.name());
    MinecraftConnection lobby = setup.current();
    setup.sentToClient();

    lobby.close();
    setup.runPendingTasks();

    assertEquals(
        List.of(SystemChatMessage.class, StartConfiguration.class), types(setup.sentToClient()));
    assertEquals(SURVIVAL, setup.player.pendingSwitchTarget());
  }

  @Test
  @DisplayName("should connect the next server once the client acknowledges")
  void acknowledged() {
    SwitchHarness setup = playingOnLobby(CREATIVE.name());
    MinecraftConnection lobby = setup.current();
    setup.player.switchServer(SURVIVAL);
    setup.sentToClient();

    ((ReconfigurationSwitch) setup.player.serverSwitch()).acknowledged(SURVIVAL);

    assertFalse(lobby.channel().isOpen());
    assertEquals(ProtocolState.CONFIGURATION, setup.client.decoder().state());
    assertInstanceOf(SwitchWaitSessionHandler.class, setup.client.sessionHandler());
    // survival, then creative, cannot be reached from an embedded channel: nothing is left.
    assertEquals(ConfigDisconnect.class, setup.sentToClient().getLast().type());
    assertFalse(setup.client.channel().isOpen());
  }

  @Test
  @DisplayName("should connect a joining client to the fallback directly")
  void fallbackWhileJoining() {
    SwitchHarness setup =
        new SwitchHarness(VERSION, List.of(SURVIVAL.name()), ProtocolState.CONFIGURATION);
    harnesses.add(setup);
    MinecraftConnection lobby = setup.backend(ProtocolState.CONFIGURATION);
    setup.player.serverSwitch().connected(new BackendConnection(lobby, LOBBY));

    setup.player.scheduleBackendFailure(lobby);
    setup.runPendingTasks();

    assertInstanceOf(SwitchWaitSessionHandler.class, setup.client.sessionHandler());
    // survival cannot be reached from an embedded channel: nothing is left.
    assertEquals(ConfigDisconnect.class, setup.sentToClient().getLast().type());
    assertFalse(setup.client.channel().isOpen());
  }
}
