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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.api.event.EventBus;
import dev.warp.api.event.player.PlayerDrainEvent;
import dev.warp.api.event.player.ServerPostConnectEvent;
import dev.warp.api.event.proxy.ProxyDrainEvent;
import dev.warp.api.lifecycle.DrainOutcome;
import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.server.RegisteredServer;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameEncoder;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.Transfer;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.config.TimeoutsConfig;
import dev.warp.proxy.drain.ManualScheduler;
import dev.warp.proxy.drain.ProxyDrain;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.metrics.MetricRegistry;
import dev.warp.proxy.metrics.ProxyMetrics;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The drain end to end, with real players of several versions on embedded channels: the transfer
 * packets their clients decode, the plugins' decisions, and the shutdown it ends with.
 */
@DisplayName("Drain with connected players")
class DrainPlayersTest {

  private static final Duration PROPAGATION = Duration.ofSeconds(5);
  private static final Duration INTERVAL = Duration.ofMillis(100);
  private static final InetSocketAddress ADDRESS =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 1);

  private final WarpEventBus events = new WarpEventBus(Runnable::run);
  private final EventBus plugin = new PluginEventBus(events, "primemc", null, s -> {});
  private final PlayerRegistry registry = new PlayerRegistry();
  private final ServerRegistry servers =
      new ServerRegistry(Map.of("lobby", ADDRESS, "game-1", ADDRESS), "lobby", List.of("lobby"));
  private final ManualScheduler clock = new ManualScheduler();
  private final List<DrainStatus> shutdowns = new ArrayList<>();
  private final List<Client> clients = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (Client client : clients) {
      client.warpSide().finishAndReleaseAll();
    }
    events.close();
  }

  /** A packet the client received: its type, as far as the test tells, and its body as text. */
  private record Received(Class<? extends Packet> type, String text) {}

  /** A player, and Warp's end of its connection. */
  private record Client(ConnectedPlayer player, EmbeddedChannel warpSide) {

    /** Returns what Warp sent the client since the last call, framed as the client reads it. */
    List<Received> received() {
      ProtocolVersion version = player.protocolVersion();
      PacketRegistry registry = StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);
      int transfer = idOf(registry, version, Transfer.class);
      int disconnect = idOf(registry, version, PlayDisconnect.class);
      List<Received> received = new ArrayList<>();
      for (ByteBuf frame = warpSide.readOutbound();
          frame != null;
          frame = warpSide.readOutbound()) {
        try {
          VarInt.read(frame);
          int id = VarInt.read(frame);
          byte[] body = new byte[frame.readableBytes()];
          frame.readBytes(body);
          Class<? extends Packet> type =
              id == transfer
                  ? Transfer.class
                  : id == disconnect ? PlayDisconnect.class : Packet.class;
          received.add(new Received(type, new String(body, StandardCharsets.UTF_8)));
        } finally {
          frame.release();
        }
      }
      return received;
    }

    private static int idOf(
        PacketRegistry registry, ProtocolVersion version, Class<? extends Packet> type) {
      try {
        return registry.packetId(version, type);
      } catch (IllegalArgumentException notInThisVersion) {
        return -1;
      }
    }

    /** The client leaves, as one does once it got a Transfer packet. */
    void leave() {
      var _ = warpSide.close();
      warpSide.runPendingTasks();
    }
  }

  private Client join(String name, ProtocolVersion version) {
    EmbeddedChannel warpSide = new EmbeddedChannel();
    addCodecs(warpSide, version);
    MinecraftConnection connection = new MinecraftConnection(warpSide);
    warpSide.pipeline().addLast(ServerChannelInitializer.CONNECTION_HANDLER, connection);
    ServerLoginContext context = mock(ServerLoginContext.class);
    when(context.metrics()).thenReturn(new ProxyMetrics(new MetricRegistry(0)));
    when(context.messages()).thenReturn(dev.warp.proxy.i18n.PlayerMessages.bundled());
    when(context.logging()).thenReturn(LoggingConfig.DEFAULTS);
    when(context.timeouts()).thenReturn(TimeoutsConfig.DEFAULTS);
    when(context.antiBot()).thenReturn(TestLoginContexts.calmAntiBot());
    when(context.playerRegistry()).thenReturn(registry);
    when(context.events()).thenReturn(events);
    when(context.channels()).thenReturn(new ChannelRegistry());
    when(context.serverRegistry()).thenReturn(servers);
    when(context.commands()).thenReturn(TestLoginContexts.commands(servers));
    ConnectedPlayer player =
        new ConnectedPlayer(
            connection,
            version,
            new GameProfile(UUID.randomUUID(), name, List.of()),
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 50_000 + clients.size()),
            context,
            new ClientCookies(connection, version, warpSide.eventLoop()));
    assertInstanceOf(
        PlayerRegistry.Registration.Registered.class, registry.register(player, false));
    connection.setSessionHandler(new ClientPlaySessionHandler(player));
    Client client = new Client(player, warpSide);
    clients.add(client);
    return client;
  }

  /** Warp's end of a client connection in PLAY. */
  private static void addCodecs(EmbeddedChannel channel, ProtocolVersion version) {
    channel
        .pipeline()
        .addLast(ServerChannelInitializer.FRAME_DECODER, new FrameDecoder())
        .addLast(
            ServerChannelInitializer.MINECRAFT_DECODER,
            new MinecraftDecoder(PacketDirection.SERVERBOUND, version, ProtocolState.PLAY))
        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(
            ServerChannelInitializer.MINECRAFT_ENCODER,
            new MinecraftEncoder(PacketDirection.CLIENTBOUND, version, ProtocolState.PLAY));
  }

  private ProxyDrain drain(DrainConfig.CannotTransfer cannotTransfer) {
    return new ProxyDrain(
        new DrainConfig(
            new DrainConfig.Target("play.example.net", 25565),
            true,
            PROPAGATION,
            INTERVAL,
            cannotTransfer,
            DrainConfig.NewPlayers.ACCEPT,
            Duration.ofMinutes(30),
            Component.text("Restarting, see you on the other side")),
        registry,
        events,
        clock,
        shutdowns::add);
  }

  private static long transfers(List<Received> received) {
    return received.stream().filter(r -> r.type() == Transfer.class).count();
  }

  private RegisteredServer server(String name) {
    return servers.server(name).orElseThrow();
  }

  @Test
  @DisplayName(
      "should transfer each player its client can, at the rate, keep a game player until it is"
          + " back on a lobby, wait for an old client to leave, then stop")
  void primemc() {
    Client steve = join("Steve", ProtocolVersion.MINECRAFT_1_21_4);
    Client alex = join("Alex", ProtocolVersion.MINECRAFT_1_20_5);
    Client gamer = join("Gamer", ProtocolVersion.latest());
    Client old = join("Old", ProtocolVersion.MINECRAFT_1_20_1);
    AtomicBoolean inMatch = new AtomicBoolean(true);
    var _ =
        plugin.subscribe(
            PlayerDrainEvent.class,
            event -> {
              if (event.player().username().equals("Gamer") && inMatch.get()) {
                event.setResult(new PlayerDrainEvent.Keep());
              }
            });
    List<DrainPhase> phases = new CopyOnWriteArrayList<>();
    var _ = plugin.subscribe(ProxyDrainEvent.class, event -> phases.add(event.phase()));
    ProxyDrain drain = drain(DrainConfig.CannotTransfer.KEEP);

    var _ = drain.start(DrainTrigger.SIGNAL);
    clock.advance(PROPAGATION.minusMillis(1));

    assertEquals(0, transfers(steve.received()) + transfers(alex.received()));
    assertEquals(DrainPhase.ANNOUNCING, drain.status().phase());

    clock.advance(Duration.ofMillis(1));
    List<Received> first = new ArrayList<>(steve.received());
    first.addAll(alex.received());

    assertEquals(1, transfers(first), "one transfer, then the interval");

    clock.advance(INTERVAL);
    clock.advance(INTERVAL);
    List<Received> all = new ArrayList<>(first);
    all.addAll(steve.received());
    all.addAll(alex.received());

    assertEquals(2, transfers(all), "both modern clients, once each");
    for (Received transfer : all) {
      assertTrue(transfer.text().contains("play.example.net"), transfer.text());
    }
    assertEquals(0, transfers(gamer.received()), "kept by the plugin: in a match");
    assertTrue(old.received().isEmpty(), "kept: its client predates transfers");
    assertEquals(DrainPhase.WAITING, drain.status().phase());

    steve.leave();
    alex.leave();
    inMatch.set(false);
    var _ =
        events.fire(
            new ServerPostConnectEvent(gamer.player().api(), server("lobby"), server("game-1")));
    clock.advance(INTERVAL);

    assertEquals(1, transfers(gamer.received()), "back on a lobby: transferred");

    gamer.leave();
    clock.advance(Duration.ofSeconds(10));

    assertTrue(shutdowns.isEmpty(), "the old client is still connected");

    old.leave();
    clock.advance(Duration.ofSeconds(1));

    assertEquals(1, shutdowns.size());
    DrainStatus status = shutdowns.getFirst();
    assertEquals(DrainOutcome.DRAINED, status.outcome());
    assertEquals(3, status.transferred());
    assertEquals(2, status.kept());
    assertEquals(0, status.players());
    assertEquals(
        List.of(
            DrainPhase.ANNOUNCING,
            DrainPhase.TRANSFERRING,
            DrainPhase.WAITING,
            DrainPhase.STOPPING),
        phases);
  }

  @Test
  @DisplayName("should kick an old client with the drain's message when configured to")
  void kicksOldClient() {
    Client old = join("Old", ProtocolVersion.MINECRAFT_1_8);
    ProxyDrain drain = drain(DrainConfig.CannotTransfer.KICK);

    var _ = drain.start(DrainTrigger.HTTP);
    clock.advance(PROPAGATION);

    List<Received> received = old.received();
    assertEquals(1, received.size(), received.toString());
    assertEquals(PlayDisconnect.class, received.getFirst().type());
    assertTrue(
        received.getFirst().text().contains("Restarting, see you on the other side"),
        received.getFirst().text());
    assertEquals(0, registry.count());
    clock.advance(Duration.ofSeconds(1));
    assertEquals(DrainOutcome.DRAINED, shutdowns.getFirst().outcome());
    assertEquals(1, shutdowns.getFirst().kicked());
  }

  @Test
  @DisplayName("should time out, leaving the players still connected for the shutdown")
  void timesOut() {
    join("Old", ProtocolVersion.MINECRAFT_1_20_1);
    ProxyDrain drain = drain(DrainConfig.CannotTransfer.KEEP);

    var _ = drain.start(DrainTrigger.PLUGIN);
    clock.advance(PROPAGATION.plus(Duration.ofMinutes(30)));

    assertEquals(DrainOutcome.TIMED_OUT, shutdowns.getFirst().outcome());
    assertEquals(1, shutdowns.getFirst().players());
  }
}
