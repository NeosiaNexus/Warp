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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import dev.warp.api.event.EventBus;
import dev.warp.api.event.Subscription;
import dev.warp.api.messaging.ChannelIdentifier;
import dev.warp.api.messaging.Messaging.Origin;
import dev.warp.api.messaging.PluginMessageEvent;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.PlayPluginMessage;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.messaging.PluginMessaging;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Plugin messaging")
class PluginMessagingTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;
  private static final ChannelIdentifier TRANSFER = ChannelIdentifier.of("pmc", "transfer");
  private static final byte[] LOBBY_REQUEST = "lobby".getBytes(StandardCharsets.UTF_8);

  private final List<SwitchHarness> harnesses = new ArrayList<>();
  private final List<Subscription> registrations = new ArrayList<>();
  private final WarpEventBus bus = new WarpEventBus(Runnable::run);
  private final EventBus events = new PluginEventBus(bus, "test", null, s -> {});

  @AfterEach
  void tearDown() {
    registrations.forEach(Subscription::close);
    harnesses.forEach(SwitchHarness::close);
    bus.close();
  }

  @Nested
  @DisplayName("channels nobody owns")
  class Unowned {

    @Test
    @DisplayName("should pass through untouched, without any event")
    void passThrough() {
      SwitchHarness setup = playingOnLobby();
      List<PluginMessageEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(PluginMessageEvent.class, fired::add);
      setup.sentToClient();

      setup.receive(
          setup.current(), frame(VERSION, new PlayPluginMessage("pmc:transfer", LOBBY_REQUEST)));

      assertEquals(List.of(PlayPluginMessage.class), types(setup.sentToClient()));
      assertTrue(fired.isEmpty());
    }
  }

  @Nested
  @DisplayName("owned channels")
  class Owned {

    @Test
    @DisplayName("should hand a server's message to the plugin, and not relay it by default")
    void fromServer() {
      SwitchHarness setup = playingOnLobby();
      register(setup, Set.of(Origin.SERVER));
      List<PluginMessageEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(PluginMessageEvent.class, fired::add);
      setup.sentToClient();

      setup.receive(
          setup.current(), frame(VERSION, new PlayPluginMessage("pmc:transfer", LOBBY_REQUEST)));

      PluginMessageEvent event = fired.getFirst();
      assertEquals(TRANSFER, event.channel());
      assertArrayEquals(LOBBY_REQUEST, event.data());
      PluginMessageEvent.FromServer source =
          assertInstanceOf(PluginMessageEvent.FromServer.class, event.source());
      assertEquals("lobby", source.connection().server().name());
      assertEquals(setup.player.api(), event.player());
      assertTrue(setup.sentToClient().isEmpty(), "not relayed");
    }

    @Test
    @DisplayName("should relay a message a handler forwards")
    void forward() {
      SwitchHarness setup = playingOnLobby();
      register(setup, Set.of(Origin.SERVER));
      events.subscribe(
          PluginMessageEvent.class, event -> event.setResult(new PluginMessageEvent.Forward()));
      setup.sentToClient();

      setup.receive(
          setup.current(), frame(VERSION, new PlayPluginMessage("pmc:transfer", LOBBY_REQUEST)));

      assertEquals(List.of(PlayPluginMessage.class), types(setup.sentToClient()));
    }

    @Test
    @DisplayName("should drop a client's message on a channel meant for servers")
    void spoofedByClient() {
      SwitchHarness setup = playingOnLobby();
      register(setup, Set.of(Origin.SERVER));
      List<PluginMessageEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(PluginMessageEvent.class, fired::add);

      clientHandler(setup).handle(new PlayPluginMessage("pmc:transfer", LOBBY_REQUEST));

      assertTrue(fired.isEmpty());
      assertEquals(0, setup.sentToBackend(setup.current()), "not relayed to the server");
    }

    @Test
    @DisplayName("should hand a client's message to the plugin on a channel accepting players")
    void fromPlayer() {
      SwitchHarness setup = playingOnLobby();
      register(setup, Set.of(Origin.PLAYER));
      List<PluginMessageEvent> fired = new CopyOnWriteArrayList<>();
      events.subscribe(PluginMessageEvent.class, fired::add);

      clientHandler(setup).handle(new PlayPluginMessage("pmc:transfer", LOBBY_REQUEST));

      assertInstanceOf(PluginMessageEvent.FromPlayer.class, fired.getFirst().source());
      assertEquals(0, setup.sentToBackend(setup.current()));
    }

    @Test
    @DisplayName("should refuse a channel another plugin owns, naming it")
    void exclusive() {
      ChannelRegistry registry = new ChannelRegistry();
      registrations.add(
          new PluginMessaging(registry, "first", s -> {})
              .register(TRANSFER, Set.of(Origin.SERVER)));

      IllegalStateException error =
          assertThrows(
              IllegalStateException.class,
              () ->
                  new PluginMessaging(registry, "second", s -> {})
                      .register(TRANSFER, Set.of(Origin.PLAYER)));

      assertTrue(String.valueOf(error.getMessage()).contains("first"));
    }

    @Test
    @DisplayName("should release a channel once its registration is closed")
    void release() {
      ChannelRegistry registry = new ChannelRegistry();
      Subscription registration =
          new PluginMessaging(registry, "first", s -> {}).register(TRANSFER, Set.of(Origin.SERVER));

      registration.close();

      assertFalse(registration.isActive());
      registrations.add(
          new PluginMessaging(registry, "second", s -> {})
              .register(TRANSFER, Set.of(Origin.SERVER)));
    }
  }

  @Nested
  @DisplayName("sending")
  class Sending {

    @Test
    @DisplayName("should send a plugin message to the client")
    void toClient() {
      SwitchHarness setup = playingOnLobby();
      setup.sentToClient();

      boolean sent = setup.player.api().sendPluginMessage(TRANSFER, LOBBY_REQUEST);

      assertTrue(sent);
      assertEquals(List.of(PlayPluginMessage.class), types(setup.sentToClient()));
    }

    @Test
    @DisplayName("should send a plugin message to the server the player plays on")
    void toServer() {
      SwitchHarness setup = playingOnLobby();

      boolean sent =
          setup
              .player
              .api()
              .currentServer()
              .orElseThrow()
              .sendPluginMessage(TRANSFER, LOBBY_REQUEST);

      assertTrue(sent);
      assertEquals(1, setup.sentToBackend(setup.current()));
    }
  }

  private void register(SwitchHarness setup, Set<Origin> accepted) {
    registrations.add(
        new PluginMessaging(setup.player.loginContext().channels(), "test", s -> {})
            .register(TRANSFER, accepted));
  }

  private static ClientPlaySessionHandler clientHandler(SwitchHarness setup) {
    return (ClientPlaySessionHandler)
        java.util.Objects.requireNonNull(setup.client.sessionHandler());
  }

  /** A player playing on the lobby, its server counted, through the configuration phase path. */
  private SwitchHarness playingOnLobby() {
    SwitchHarness setup = new SwitchHarness(VERSION, List.of(), ProtocolState.PLAY);
    when(setup.player.loginContext().events()).thenReturn(bus);
    harnesses.add(setup);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    setup.player.setCurrentServerName(LOBBY.name());
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    // In a game, as after its Join Game: the proxy's messages go out at once.
    setup.player.joinedGame();
    return setup;
  }
}
