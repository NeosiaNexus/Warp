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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.event.EventBus;
import dev.warp.api.event.player.DisconnectEvent;
import dev.warp.api.event.player.LoginEvent;
import dev.warp.api.event.player.PostLoginEvent;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.netty.FrameEncoder;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.event.player.LoginEventImpl;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Session events")
class SessionEventsTest {

  private final WarpEventBus bus = new WarpEventBus();
  private final EventBus events = new PluginEventBus(bus, "test", null, s -> {});
  private final PlayerRegistry registry = new PlayerRegistry();
  private final ServerLoginContext context = mock(ServerLoginContext.class);
  private final List<EmbeddedChannel> channels = new CopyOnWriteArrayList<>();

  SessionEventsTest() {
    when(context.events()).thenReturn(bus);
    when(context.playerRegistry()).thenReturn(registry);
    when(context.channels()).thenReturn(new ChannelRegistry());
    when(context.serverRegistry()).thenReturn(new ServerRegistry(Map.of(), null, List.of()));
  }

  @AfterEach
  void tearDown() {
    channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    bus.close();
  }

  @Test
  @DisplayName("should fire exactly one disconnect event per session, with its cause")
  void oneDisconnectPerSession() throws Exception {
    List<DisconnectEvent> fired = new CopyOnWriteArrayList<>();
    events.subscribe(DisconnectEvent.class, fired::add);
    ConnectedPlayer steve = player(UUID.randomUUID(), "Steve");
    steve.startSession();

    steve.disconnectShutdown();
    steve.disconnect();
    steve.disconnect();
    awaitQuiet();

    assertEquals(1, fired.size());
    assertEquals(DisconnectEvent.Cause.PROXY_SHUTDOWN, fired.getFirst().cause());
    assertEquals(steve.api(), fired.getFirst().player());
  }

  @Test
  @DisplayName("should fire no disconnect event for a player whose session never started")
  void noSessionNoDisconnect() throws Exception {
    List<DisconnectEvent> fired = new CopyOnWriteArrayList<>();
    events.subscribe(DisconnectEvent.class, fired::add);
    ConnectedPlayer refused = player(UUID.randomUUID(), "Refused");

    refused.disconnect();
    awaitQuiet();

    assertTrue(fired.isEmpty());
  }

  @Test
  @DisplayName(
      "should handle a kicked session's disconnect fully before the next session's login starts")
  void accountOrder() throws Exception {
    List<String> order = new CopyOnWriteArrayList<>();
    events.subscribeAsync(
        DisconnectEvent.class,
        0,
        30,
        event -> {
          order.add("disconnect start " + event.cause());
          // A plugin saving the player's data, slowly.
          return CompletableFuture.runAsync(
              () -> order.add("disconnect end"),
              CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS));
        });
    events.subscribe(LoginEvent.class, event -> order.add("login " + event.player().username()));
    events.subscribe(PostLoginEvent.class, event -> order.add("post-login"));
    UUID account = UUID.randomUUID();
    ConnectedPlayer first = player(account, "Steve");
    registry.register(first, true);
    first.startSession();

    ConnectedPlayer second = player(account, "Steve");
    registry.register(second, true);
    LoginEvent login = bus.fire(new LoginEventImpl(second.api()), account).get(5, TimeUnit.SECONDS);

    assertEquals(
        List.of(
            "post-login", "disconnect start CONFLICTING_LOGIN", "disconnect end", "login Steve"),
        order);
    assertEquals(second.api(), login.player());
  }

  private ConnectedPlayer player(UUID id, String name) {
    EmbeddedChannel channel = new EmbeddedChannel();
    channels.add(channel);
    MinecraftConnection connection = new MinecraftConnection(channel);
    ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
    channel
        .pipeline()
        .addLast(
            ServerChannelInitializer.MINECRAFT_DECODER,
            new MinecraftDecoder(PacketDirection.SERVERBOUND, version, ProtocolState.PLAY))
        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(
            ServerChannelInitializer.MINECRAFT_ENCODER,
            new MinecraftEncoder(PacketDirection.CLIENTBOUND, version, ProtocolState.PLAY))
        .addLast(ServerChannelInitializer.CONNECTION_HANDLER, connection);
    return new ConnectedPlayer(
        connection,
        ProtocolVersion.MINECRAFT_1_21_4,
        new GameProfile(id, name, List.of()),
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 50000),
        context);
  }

  /** Lets deliveries in flight finish: events with no async handler complete quickly. */
  private void awaitQuiet() throws Exception {
    bus.fire("barrier", "barrier").get(5, TimeUnit.SECONDS);
    TimeUnit.MILLISECONDS.sleep(100);
  }
}
