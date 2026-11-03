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
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import dev.warp.api.event.EventBus;
import dev.warp.api.event.player.KickedFromServerEvent;
import dev.warp.api.event.player.ServerPostConnectEvent;
import dev.warp.api.event.player.ServerPreConnectEvent;
import dev.warp.api.player.ConnectionResult;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.server.BackendServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Player events")
class PlayerEventsTest {

  /** Before 1.20.2: switches the harness drives end to end, Join Game included. */
  private static final ProtocolVersion LEGACY = ProtocolVersion.MINECRAFT_1_20;

  /** From 1.20.2: switches through the configuration phase. */
  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;

  private final List<SwitchHarness> harnesses = new ArrayList<>();
  private final WarpEventBus bus = new WarpEventBus(Runnable::run);
  private final EventBus events = new PluginEventBus(bus, "test", null, s -> {});

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
    bus.close();
  }

  @Nested
  @DisplayName("KickedFromServerEvent")
  class Kicked {

    @Test
    @DisplayName("should carry the server's reason and follow a plugin's redirect")
    void redirect() {
      SwitchHarness setup = playingOnLobby(MODERN, SURVIVAL.name());
      setup.holdConnections();
      List<KickedFromServerEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(
          KickedFromServerEvent.class,
          event -> {
            fired.add(event);
            event.setResult(new KickedFromServerEvent.Redirect(server(setup, "creative"), null));
          });

      setup.receive(
          setup.current(), frame(MODERN, PlayDisconnect.ofPlainText("Restarting", MODERN)));

      KickedFromServerEvent event = fired.getFirst();
      assertEquals(KickedFromServerEvent.Cause.KICKED, event.cause());
      assertEquals(
          "Restarting",
          PlainTextComponentSerializer.plainText()
              .serialize(event.serverKickReason().orElseThrow()));
      assertEquals("lobby", event.server().name());
      assertFalse(event.kickedDuringServerConnect());
      assertInstanceOf(KickedFromServerEvent.Redirect.class, event.result(), "proposed fallback");
      assertEquals(CREATIVE, setup.player.pendingSwitchTarget());
    }

    @Test
    @DisplayName("should disconnect the player with the reason a plugin chose")
    void disconnect() {
      SwitchHarness setup = playingOnLobby(MODERN, SURVIVAL.name());
      events.subscribe(
          KickedFromServerEvent.class,
          event ->
              event.setResult(new KickedFromServerEvent.DisconnectPlayer(Component.text("Bye"))));
      setup.sentToClient();

      setup.current().close();
      setup.runPendingTasks();

      assertEquals(PlayDisconnect.class, setup.sentToClient().getLast().type());
      assertFalse(setup.client.channel().isOpen());
    }

    @Test
    @DisplayName("should refuse a redirect to the server that was just lost")
    void noBounce() {
      SwitchHarness setup = playingOnLobby(MODERN, SURVIVAL.name());
      events.subscribe(
          KickedFromServerEvent.class,
          event -> event.setResult(new KickedFromServerEvent.Redirect(event.server(), null)));

      setup.current().close();
      setup.runPendingTasks();

      assertFalse(setup.client.channel().isOpen());
    }

    @Test
    @DisplayName("should keep a player whose switch was refused, by default, and say why")
    void defaultNotify() {
      SwitchHarness setup = playingOnLobby(MODERN);
      setup.holdConnections();
      List<KickedFromServerEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(KickedFromServerEvent.class, fired::add);
      CompletableFuture<ConnectionResult> result =
          setup.player.api().connect(server(setup, "survival"));
      MinecraftConnection survival = setup.backend(ProtocolState.LOGIN);
      setup.player.serverSwitch().connected(new BackendConnection(survival, SURVIVAL));
      setup.player.recordKick(survival, Component.text("You are not whitelisted"));
      setup.sentToClient();

      setup.player.scheduleBackendFailure(survival);
      setup.runPendingTasks();

      KickedFromServerEvent event = fired.getFirst();
      assertTrue(event.kickedDuringServerConnect());
      assertInstanceOf(KickedFromServerEvent.Notify.class, event.result());
      ConnectionResult.Failed failed =
          assertInstanceOf(ConnectionResult.Failed.class, result.join());
      assertEquals(
          "You are not whitelisted",
          PlainTextComponentSerializer.plainText().serialize(failed.serverReason().orElseThrow()));
      assertEquals(List.of(SystemChatMessage.class), types(setup.sentToClient()));
      assertFalse(setup.player.isSwitching());
      assertTrue(setup.client.channel().isOpen());
    }
  }

  @Nested
  @DisplayName("ServerPreConnectEvent")
  class PreConnect {

    @Test
    @DisplayName("should let a plugin deny a switch, the player staying where it is")
    void deny() {
      SwitchHarness setup = playingOnLobby(MODERN);
      setup.holdConnections();
      events.subscribe(
          ServerPreConnectEvent.class,
          event -> event.setResult(new ServerPreConnectEvent.Denied()));

      ConnectionResult result = setup.player.api().connect(server(setup, "survival")).join();

      assertInstanceOf(ConnectionResult.Denied.class, result);
      assertFalse(setup.player.isSwitching());
    }

    @Test
    @DisplayName("should let a plugin send the player elsewhere")
    void reroute() {
      SwitchHarness setup = playingOnLobby(MODERN);
      setup.holdConnections();
      List<ServerPreConnectEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(
          ServerPreConnectEvent.class,
          event -> {
            fired.add(event);
            event.setResult(new ServerPreConnectEvent.Allowed(server(setup, "creative")));
          });

      var _ = setup.player.api().connect(server(setup, "survival"));

      assertEquals("survival", fired.getFirst().originalServer().name());
      assertEquals("lobby", fired.getFirst().previousServer().orElseThrow().name());
      assertEquals(CREATIVE, setup.player.pendingSwitchTarget());
      assertSame(server(setup, "creative"), setup.player.api().pendingServer().orElseThrow());
    }
  }

  @Nested
  @DisplayName("connection results")
  class Results {

    @Test
    @DisplayName("should complete with Connected once the player plays on the new server")
    void connected() {
      SwitchHarness setup = harness(LEGACY);
      setup.holdConnections();
      setup.spawnOn(LOBBY);

      CompletableFuture<ConnectionResult> result =
          setup.player.api().connect(server(setup, "survival"));
      MinecraftConnection survival = setup.login(SURVIVAL);
      setup.joinGame(survival);

      assertEquals(new ConnectionResult.Connected(server(setup, "survival")), result.join());
      assertSame(
          server(setup, "survival"), setup.player.api().currentServer().orElseThrow().server());
    }

    @Test
    @DisplayName("should answer AlreadyConnected and InProgress without doing anything")
    void noOps() {
      SwitchHarness setup = playingOnLobby(MODERN);
      setup.holdConnections();
      setup.player.setCurrentServerName("lobby");

      assertInstanceOf(
          ConnectionResult.AlreadyConnected.class,
          setup.player.api().connect(server(setup, "lobby")).join());
      var _ = setup.player.api().connect(server(setup, "survival"));
      assertInstanceOf(
          ConnectionResult.InProgress.class,
          setup.player.api().connect(server(setup, "creative")).join());
    }
  }

  @Test
  @DisplayName("should fire ServerPostConnectEvent with the server left, initial join included")
  void postConnect() {
    SwitchHarness setup = harness(LEGACY);
    List<ServerPostConnectEvent> fired = new CopyOnWriteArrayList<>();
    events.subscribe(ServerPostConnectEvent.class, fired::add);

    setup.spawnOn(LOBBY);
    setup.switchTo(SURVIVAL);

    assertEquals(2, fired.size());
    assertTrue(fired.get(0).isInitialJoin());
    assertEquals("lobby", fired.get(0).server().name());
    assertEquals("survival", fired.get(1).server().name());
    assertEquals("lobby", fired.get(1).previousServer().orElseThrow().name());
    assertNull(fired.get(0).previous());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private SwitchHarness harness(ProtocolVersion version, String... fallbackOrder) {
    SwitchHarness setup = new SwitchHarness(version, List.of(fallbackOrder), ProtocolState.PLAY);
    when(setup.player.loginContext().events()).thenReturn(bus);
    harnesses.add(setup);
    return setup;
  }

  /** A player playing on the lobby through the configuration phase path. */
  private SwitchHarness playingOnLobby(ProtocolVersion version, String... fallbackOrder) {
    SwitchHarness setup = harness(version, fallbackOrder);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    setup.player.setCurrentServerName(LOBBY.name());
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    // In a game, as after its Join Game: the proxy's messages go out at once.
    setup.player.joinedGame();
    return setup;
  }

  private static BackendServer server(SwitchHarness setup, String name) {
    return Objects.requireNonNull(
        setup.player.loginContext().serverRegistry().getServer(name), name);
  }
}
