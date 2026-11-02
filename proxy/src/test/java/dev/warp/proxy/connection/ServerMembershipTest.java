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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.server.BackendServer;
import dev.warp.proxy.server.ServerRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Server membership")
class ServerMembershipTest {

  /** A version that switches with Join Game and Respawn, which the harness drives end to end. */
  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20;

  private final List<SwitchHarness> harnesses = new ArrayList<>();

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
  }

  @Test
  @DisplayName("should count a player on the server it plays on")
  void countsOnCurrentServer() {
    SwitchHarness setup = harness();

    setup.spawnOn(LOBBY);

    BackendServer lobby = server(setup, "lobby");
    assertEquals(1, lobby.playerCount());
    assertTrue(lobby.players().contains(setup.player));
    assertSame(lobby, setup.player.currentServer());
  }

  @Test
  @DisplayName("should move the player's count to the server it switched to")
  void movesOnSwitch() {
    SwitchHarness setup = harness();
    setup.spawnOn(LOBBY);

    setup.switchTo(SURVIVAL);

    assertEquals(0, server(setup, "lobby").playerCount());
    assertEquals(1, server(setup, "survival").playerCount());
    assertSame(server(setup, "survival"), setup.player.currentServer());
  }

  @Test
  @DisplayName("should stop counting a player that left the proxy")
  void forgetsOnDisconnect() {
    SwitchHarness setup = harness();
    setup.spawnOn(LOBBY);

    setup.player.disconnect();
    setup.runPendingTasks();

    assertEquals(0, server(setup, "lobby").playerCount());
    assertNull(setup.player.currentServer());
  }

  @Test
  @DisplayName("should keep counting players on a server unregistered while they play on it")
  void keepsPlayersOfUnregisteredServer() {
    SwitchHarness setup = harness();
    setup.spawnOn(LOBBY);
    BackendServer lobby = server(setup, "lobby");

    registry(setup).unregister("lobby");

    assertEquals(1, lobby.playerCount());
    assertSame(lobby, setup.player.currentServer());
  }

  @Test
  @DisplayName("should not count a player on a server unregistered before it got there")
  void ignoresServerUnregisteredBeforeArrival() {
    SwitchHarness setup = harness();
    setup.spawnOn(LOBBY);
    registry(setup).unregister("survival");

    setup.switchTo(SURVIVAL);

    assertEquals(0, server(setup, "lobby").playerCount());
    assertNull(setup.player.currentServer());
  }

  private SwitchHarness harness() {
    SwitchHarness setup = new SwitchHarness(VERSION, List.of("lobby"), ProtocolState.PLAY);
    harnesses.add(setup);
    return setup;
  }

  private static ServerRegistry registry(SwitchHarness setup) {
    return setup.player.loginContext().serverRegistry();
  }

  private static BackendServer server(SwitchHarness setup, String name) {
    return Objects.requireNonNull(registry(setup).getServer(name), name);
  }
}
