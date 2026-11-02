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

import static dev.warp.proxy.connection.SwitchHarness.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import dev.warp.api.event.EventBus;
import dev.warp.api.event.player.DisconnectEvent;
import dev.warp.api.event.player.PlayerChooseInitialServerEvent;
import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.event.player.PlayerChooseInitialServerEventImpl;
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

@DisplayName("PlayerChooseInitialServerEvent")
class InitialServerTest {

  /** Before 1.20.2: the client plays from its login, so a disconnection is a PLAY packet. */
  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20;

  private final List<SwitchHarness> harnesses = new ArrayList<>();
  private final WarpEventBus bus = new WarpEventBus(Runnable::run);
  private final EventBus events = new PluginEventBus(bus, "test", null, s -> {});
  private final List<ServerInfo> connections = new CopyOnWriteArrayList<>();
  private final List<DisconnectEvent> disconnects = new CopyOnWriteArrayList<>();

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
    bus.close();
  }

  @Nested
  @DisplayName("when the proxy proposes a server")
  class Proposed {

    @Test
    @DisplayName("should start allowed to the default server and connect there")
    void defaultServer() {
      SwitchHarness setup = harness();
      List<PlayerChooseInitialServerEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(PlayerChooseInitialServerEvent.class, fired::add);

      setup.player.join();
      setup.runPendingTasks();

      PlayerChooseInitialServerEvent event = fired.getFirst();
      assertEquals("lobby", event.proposedServer().orElseThrow().name());
      assertEquals(
          "lobby",
          assertInstanceOf(PlayerChooseInitialServerEvent.Allowed.class, event.result())
              .server()
              .name());
      assertEquals(List.of(SwitchHarness.LOBBY), connections);
    }

    @Test
    @DisplayName("should connect the player to the server a plugin picked")
    void pickedServer() {
      SwitchHarness setup = harness();
      events.subscribe(
          PlayerChooseInitialServerEvent.class,
          event ->
              event.setResult(
                  new PlayerChooseInitialServerEvent.Allowed(server(setup, "survival"))));

      setup.player.join();
      setup.runPendingTasks();

      assertEquals(List.of(SURVIVAL), connections);
    }

    @Test
    @DisplayName("should disconnect a denied player with the plugin's reason, joining nothing")
    void denied() {
      SwitchHarness setup = harness();
      events.subscribe(
          PlayerChooseInitialServerEvent.class,
          event ->
              event.setResult(
                  new PlayerChooseInitialServerEvent.Denied(
                      Component.text("Aucun lobby disponible."))));
      setup.sentToClient();

      setup.player.join();
      setup.runPendingTasks();

      assertEquals("Aucun lobby disponible.", lastReason(setup));
      assertFalse(setup.client.channel().isOpen());
      assertTrue(connections.isEmpty(), "no server is contacted");
      assertEquals(DisconnectEvent.Cause.NO_SERVER_AVAILABLE, disconnects.getFirst().cause());
    }
  }

  @Nested
  @DisplayName("when no server is registered")
  class NoneProposed {

    @Test
    @DisplayName("should start denied with the proxy's reason, and disconnect with it")
    void defaultDenial() {
      SwitchHarness setup = harnessWithoutServers();
      List<PlayerChooseInitialServerEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(PlayerChooseInitialServerEvent.class, fired::add);
      setup.sentToClient();

      setup.player.join();
      setup.runPendingTasks();

      PlayerChooseInitialServerEvent event = fired.getFirst();
      assertTrue(event.proposedServer().isEmpty());
      assertEquals(
          PlayerChooseInitialServerEventImpl.NO_SERVER_AVAILABLE,
          assertInstanceOf(PlayerChooseInitialServerEvent.Denied.class, event.result()).reason());
      assertEquals(
          PlainTextComponentSerializer.plainText()
              .serialize(PlayerChooseInitialServerEventImpl.NO_SERVER_AVAILABLE),
          lastReason(setup));
      assertFalse(setup.client.channel().isOpen());
    }

    @Test
    @DisplayName("should let a plugin send the player to a server the proxy did not propose")
    void allowedAnyway() {
      SwitchHarness setup = harnessWithoutServers();
      setup.player.loginContext().serverRegistry().register(SURVIVAL);
      BackendServer survival = server(setup, "survival");
      events.subscribe(
          PlayerChooseInitialServerEvent.class,
          event -> event.setResult(new PlayerChooseInitialServerEvent.Allowed(survival)));

      setup.player.join();
      setup.runPendingTasks();

      assertEquals(List.of(SURVIVAL), connections);
    }

    @Test
    @DisplayName("should disconnect with the proxy's reason when no plugin listens")
    void noHandler() {
      SwitchHarness setup = harnessWithoutServers();
      setup.sentToClient();

      setup.player.join();
      setup.runPendingTasks();

      assertEquals(
          PlainTextComponentSerializer.plainText()
              .serialize(PlayerChooseInitialServerEventImpl.NO_SERVER_AVAILABLE),
          lastReason(setup));
      assertFalse(setup.client.channel().isOpen());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private SwitchHarness harness() {
    SwitchHarness setup = new SwitchHarness(VERSION, List.of(), ProtocolState.PLAY);
    when(setup.player.loginContext().events()).thenReturn(bus);
    setup.player.setConnector(
        target -> {
          connections.add(target);
          return new CompletableFuture<>();
        });
    events.subscribe(DisconnectEvent.class, disconnects::add);
    harnesses.add(setup);
    return setup;
  }

  /** A player of a proxy whose static servers were all unregistered. */
  private SwitchHarness harnessWithoutServers() {
    SwitchHarness setup = harness();
    var registry = setup.player.loginContext().serverRegistry();
    for (var server : registry.servers()) {
      registry.unregister(server);
    }
    return setup;
  }

  private static BackendServer server(SwitchHarness setup, String name) {
    return Objects.requireNonNull(
        setup.player.loginContext().serverRegistry().getServer(name), name);
  }

  private static String lastReason(SwitchHarness setup) {
    List<SwitchHarness.Sent> sent = setup.sentToClient();
    return PlainTextComponentSerializer.plainText()
        .serialize(sent.getLast().as(PlayDisconnect.class).decodedReason(VERSION));
  }
}
