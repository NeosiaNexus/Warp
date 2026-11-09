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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import dev.warp.api.event.EventBus;
import dev.warp.api.event.Subscribe;
import dev.warp.api.event.player.KickedFromServerEvent;
import dev.warp.api.event.player.PlayerChooseInitialServerEvent;
import dev.warp.api.player.ConnectionResult;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.handshake.Handshake;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginStart;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.protocol.packet.play.AcknowledgeConfiguration;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.SystemChatMessage;
import dev.warp.proxy.config.TimeoutsConfig;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.server.BackendServer;
import dev.warp.proxy.server.ServerHealth;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateHandler;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests that the proxy keeps a player alive while it holds it on purpose: between two servers,
 * while plugins decide where it goes, and while a slow server logs it in, in the configuration
 * phase (1.20.2+) or in play.
 *
 * <p>A vanilla client drops a connection that sent it nothing for 30 s, and the proxy drops a
 * client that sent it nothing for 30 s, or answers a keep-alive more than 30 s late. Every hold
 * here lasts {@link #HOLD}, longer than all three, and longer than the switch watchdog. Time is the
 * embedded event loop's: the proxy's keep-alives, its watchdogs and the read time-out of the
 * pipeline all run on it, deterministically.
 */
@DisplayName("Players held between servers")
class HeldPlayerTest {

  /** From 1.20.2: held in the configuration phase. */
  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;

  /** Before 1.20.2: held in play. */
  private static final ProtocolVersion LEGACY = ProtocolVersion.MINECRAFT_1_12_2;

  /** How long plugins hold the player. */
  private static final Duration HOLD = Duration.ofSeconds(40);

  /** The next state a handshake asks for to log in. */
  private static final int LOGIN_NEXT_STATE = 2;

  /** The fields of a 1.20.2+ Join Game after the hardcore flag, forwarded as they are. */
  private static final byte[] JOIN_GAME = {0x03, 0x05, 0x00, 0x01};

  /** The time-out the holding handler declares, as PrimeMC's fallback handler does. */
  private static final int DECLARED_TIMEOUT_SECONDS = 45;

  private final WarpEventBus bus = new WarpEventBus(Runnable::run);
  private final EventBus events = new PluginEventBus(bus, "test", null, s -> {});

  /** Completes the plugin's hold. */
  private final CompletableFuture<Void> hold = new CompletableFuture<>();

  private final List<SwitchHarness> harnesses = new ArrayList<>();
  private final List<EmbeddedChannel> channels = new ArrayList<>();

  @AfterEach
  void tearDown() {
    hold.complete(null);
    harnesses.forEach(SwitchHarness::close);
    channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    bus.close();
  }

  static Stream<ProtocolVersion> bothSwitches() {
    return Stream.of(MODERN, LEGACY);
  }

  // ---------------------------------------------------------------------------
  // While the first server is chosen
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("while plugins choose its first server")
  class FirstServer {

    /**
     * The state each client waits in, and the ids of its keep-alives as minecraft.wiki lists them
     * (clientbound, then serverbound), written out independently of the proxy's registry.
     */
    static Stream<Arguments> clients() {
      return Stream.of(
          Arguments.of(ProtocolVersion.MINECRAFT_1_7_6, ProtocolState.PLAY, 0x00, 0x00),
          Arguments.of(ProtocolVersion.MINECRAFT_1_8, ProtocolState.PLAY, 0x00, 0x00),
          Arguments.of(LEGACY, ProtocolState.PLAY, 0x1F, 0x0B),
          Arguments.of(ProtocolVersion.MINECRAFT_1_20_2, ProtocolState.CONFIGURATION, 0x03, 0x03),
          Arguments.of(MODERN, ProtocolState.CONFIGURATION, 0x04, 0x04),
          Arguments.of(ProtocolVersion.MINECRAFT_26_3, ProtocolState.CONFIGURATION, 0x04, 0x04));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clients")
    @DisplayName("should keep a client that answers its keep-alives connected 40 s, then go on")
    void keptAlive(
        ProtocolVersion version, ProtocolState waitingIn, int keepAliveId, int answerId) {
      events.subscribeAsync(
          PlayerChooseInitialServerEvent.class,
          Subscribe.NORMAL,
          DECLARED_TIMEOUT_SECONDS,
          e -> hold);
      Client client = loggedIn(version);

      client.waitFor(HOLD, Client.Behaviour.ANSWERING);

      assertTrue(client.channel.isActive(), "dropped while plugins held it");
      assertEquals(waitingIn, client.connection.encoder().state());
      assertEquals(List.of(keepAliveId, keepAliveId), client.keepAliveWireIds, "one per 15 s");
      assertEquals(answerId, client.answerWireId());
      assertEquals(
          Client.ROUND_TRIP_MS, client.player().measuredLatencyMs(), "its answers were matched");
      // The plugins settle on the only server, which cannot be reached: the player is told.
      hold.complete(null);
      // The reason may be given time to reach the client before the connection closes.
      client.waitFor(Duration.ofSeconds(1), Client.Behaviour.SILENT);
      assertFalse(client.channel.isOpen(), "the decision is applied once the hold ends");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.HeldPlayerTest#bothSwitches")
    @DisplayName("should still time out a held client that stops answering its keep-alives")
    void unansweredKeepAlives(ProtocolVersion version) {
      events.subscribeAsync(
          PlayerChooseInitialServerEvent.class,
          Subscribe.NORMAL,
          DECLARED_TIMEOUT_SECONDS,
          e -> hold);
      Client client = loggedIn(version);

      // It goes on sending other packets, so that only the keep-alive check can tell.
      client.waitFor(Duration.ofSeconds(45), Client.Behaviour.CHATTING);
      boolean openAt45 = client.channel.isOpen();
      client.waitFor(Duration.ofSeconds(15), Client.Behaviour.CHATTING);

      assertTrue(openAt45, "an answer may be up to 30 s late");
      assertFalse(client.channel.isOpen(), "a client that never answers must be dropped");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.HeldPlayerTest#bothSwitches")
    @DisplayName("should still drop a held client that goes silent, after timeouts.read")
    void silentClient(ProtocolVersion version) {
      events.subscribeAsync(
          PlayerChooseInitialServerEvent.class,
          Subscribe.NORMAL,
          DECLARED_TIMEOUT_SECONDS,
          e -> hold);
      Client client = loggedIn(version);

      Duration readTimeout = TimeoutsConfig.DEFAULTS.read();

      client.waitFor(readTimeout.minusSeconds(1), Client.Behaviour.SILENT);
      boolean openJustBefore = client.channel.isOpen();
      client.waitFor(Duration.ofSeconds(2), Client.Behaviour.SILENT);

      assertTrue(openJustBefore);
      assertFalse(client.channel.isOpen(), "the read time-out of the pipeline");
    }
  }

  // ---------------------------------------------------------------------------
  // While a slow backend logs it in
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("while a slow backend logs it in (timeouts.backend-read)")
  class SlowBackend {

    /** The server players join first. */
    private static final String FIRST = "lobby";

    /** The server the fallback order sends them to when the first fails. */
    private static final String FALLBACK = "survival";

    /** The servers the player was connected to, in order. */
    private final List<String> targets = new ArrayList<>();

    /** The backend connections the player opened, in order. */
    private final List<EmbeddedChannel> backends = new ArrayList<>();

    @Test
    @DisplayName("should give backend connections timeouts.backend-read, and clients timeouts.read")
    void backendTimeoutFromConfig() {
      Client client = joining(MODERN, timeouts(Duration.ofSeconds(7), Duration.ofSeconds(45)));

      assertEquals(List.of(FIRST), targets);
      assertEquals(45_000, readTimeoutMillis(backends.getFirst()), "timeouts.backend-read");
      assertEquals(7_000, readTimeoutMillis(client.channel), "timeouts.read");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.HeldPlayerTest#bothSwitches")
    @DisplayName("should keep the client connected while its first server takes 40 s to log it in")
    void clientKeptAlive(ProtocolVersion version) {
      // A backend allowed more than the client's own 30 s: only the proxy's keep-alives keep the
      // client connected meanwhile, timeouts.read (25 s) being shorter than the wait.
      Client client =
          joining(version, timeouts(TimeoutsConfig.DEFAULTS.read(), Duration.ofMinutes(1)));
      EmbeddedChannel lobby = backends.getFirst();

      client.waitFor(HOLD, Client.Behaviour.ANSWERING);
      boolean clientActive = client.channel.isActive();
      boolean lobbyOpen = lobby.isOpen();
      lobby.writeInbound(loginSuccess(version));
      client.waitFor(Duration.ofSeconds(1), Client.Behaviour.ANSWERING);

      assertTrue(clientActive, "dropped while its server logged it in");
      assertTrue(lobbyOpen, "within timeouts.backend-read");
      assertEquals(2, client.keepAliveWireIds.size(), "one per 15 s");
      assertEquals(
          version.supportsConfigurationState() ? ProtocolState.CONFIGURATION : ProtocolState.PLAY,
          connectionOf(lobby).decoder().state(),
          "the server logged the player in");
      assertTrue(client.channel.isActive());
      assertEquals(List.of(FIRST), targets, "no fallback");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.HeldPlayerTest#bothSwitches")
    @DisplayName("should close a silent first server after timeouts.backend-read, and fall back")
    void backendTimesOut(ProtocolVersion version) {
      Client client = joining(version, TimeoutsConfig.DEFAULTS);
      EmbeddedChannel lobby = backends.getFirst();
      Duration backendRead = TimeoutsConfig.DEFAULTS.backendRead();

      client.waitFor(backendRead.minusSeconds(1), Client.Behaviour.ANSWERING);
      boolean openJustBefore = lobby.isOpen();
      client.waitFor(Duration.ofSeconds(2), Client.Behaviour.ANSWERING);

      assertEquals(Duration.ofSeconds(30), backendRead, "Velocity's and BungeeCord's");
      assertTrue(openJustBefore, "past timeouts.read: the backend has its own time-out");
      assertFalse(lobby.isOpen(), "the backend's read time-out");
      assertEquals(List.of(FIRST, FALLBACK), targets, "down the fallback order");
      assertTrue(backends.getLast().isOpen(), "the fallback logs the player in");
      assertTrue(client.channel.isActive(), "the player waits for its fallback");
    }

    @Test
    @DisplayName(
        "should feed the servers' health with their logins, not the connections they accept")
    void loginsFeedHealth() {
      Client client = joining(MODERN, TimeoutsConfig.DEFAULTS);
      ServerHealth health = client.player().loginContext().serverRegistry().health();
      // Two players the first server refused before, and one the fallback refused.
      health.loginRefused(FIRST);
      health.loginRefused(FIRST);
      health.loginRefused(FALLBACK);
      EmbeddedChannel lobby = backends.getFirst();

      lobby.writeInbound(
          frame(MODERN, ProtocolState.LOGIN, LoginDisconnect.ofPlainText("Not whitelisted")));
      client.waitFor(Duration.ofSeconds(1), Client.Behaviour.ANSWERING);

      assertEquals(List.of(FIRST, FALLBACK), targets, "down the fallback order");
      assertEquals(
          ServerHealth.State.OPEN,
          health.status(FIRST).state(),
          "the third login refused in a row opens the breaker");
      assertEquals(
          ServerHealth.LOGIN_REFUSALS_TO_OPEN,
          health.status(FIRST).failures(),
          "a kick followed by the connection closing counts once");
      assertEquals(
          1,
          health.status(FALLBACK).failures(),
          "the connection the fallback accepted settles nothing");

      backends.getLast().writeInbound(loginSuccess(MODERN));
      client.waitFor(Duration.ofSeconds(1), Client.Behaviour.ANSWERING);

      assertEquals(ServerHealth.State.CLOSED, health.status(FALLBACK).state());
      assertEquals(0, health.status(FALLBACK).failures(), "the login accepted closes the breaker");
      assertTrue(client.channel.isActive(), "the player plays on the fallback");
    }

    /** Timeouts of {@code read} for clients and {@code backendRead} for backends. */
    private static TimeoutsConfig timeouts(Duration read, Duration backendRead) {
      TimeoutsConfig defaults = TimeoutsConfig.DEFAULTS;
      return new TimeoutsConfig(defaults.connect(), read, backendRead, defaults.ping());
    }

    /**
     * Logs a client of {@code version} in on a proxy with {@code timeouts}, and lets it join its
     * first server: each server the player connects to accepts the connection at once, through the
     * pipeline Warp builds for backends, then says nothing until the test plays its part.
     */
    private Client joining(ProtocolVersion version, TimeoutsConfig timeouts) {
      Map<String, InetSocketAddress> servers = new LinkedHashMap<>();
      servers.put(FIRST, new InetSocketAddress(InetAddress.getLoopbackAddress(), 25_565));
      servers.put(FALLBACK, new InetSocketAddress(InetAddress.getLoopbackAddress(), 25_566));
      ServerLoginContext context =
          TestLoginContexts.with(
              TestLoginContexts.offline(bus),
              new ServerRegistry(servers, FIRST, List.of(FIRST, FALLBACK)),
              timeouts);
      // Held until the backends below replace the real connections, which open nothing here.
      holdEvery(PlayerChooseInitialServerEvent.class, DECLARED_TIMEOUT_SECONDS);
      Client client = loggedIn(version, context);
      ConnectedPlayer player = client.player();
      player.setConnector(
          target -> {
            EmbeddedChannel channel =
                new EmbeddedChannel(new BackendChannelInitializer(player, target));
            channels.add(channel);
            backends.add(channel);
            targets.add(target.name());
            client.shareClock(channel);
            MinecraftConnection backend = connectionOf(channel);
            backend.setSessionHandler(
                new BackendLoginSessionHandler(player, backend, target, new byte[0]));
            return CompletableFuture.completedFuture(new BackendConnection(backend, target));
          });
      hold.complete(null);
      client.channel.runPendingTasks();
      return client;
    }

    /** The backend's Login Success for the player. */
    private static ByteBuf loginSuccess(ProtocolVersion version) {
      UUID uuid = UUID.nameUUIDFromBytes("Steve".getBytes(StandardCharsets.UTF_8));
      return frame(
          version, ProtocolState.LOGIN, new LoginSuccess(uuid, "Steve", List.of(), false, null));
    }
  }

  // ---------------------------------------------------------------------------
  // After losing a server
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("while plugins decide where it goes after losing its server")
  class AfterLosingServer {

    @Test
    @DisplayName("should keep a 1.20.2+ client in the configuration phase alive 40 s, then go on")
    void inConfiguration() {
      SwitchHarness setup = playingOnLobby(MODERN, CREATIVE.name());
      setup.holdConnections();
      holdEvery(KickedFromServerEvent.class, DECLARED_TIMEOUT_SECONDS);
      var _ = setup.player.api().connect(server(setup, "survival"));
      MinecraftConnection survival = setup.login(SURVIVAL);
      setup.fromClient(
          frame(MODERN, ProtocolState.PLAY, PacketDirection.SERVERBOUND, ackConfiguration()));
      // Survival goes down in the middle of the client's configuration.
      survival.close();
      setup.runPendingTasks();
      Client client = Client.of(setup);

      client.waitFor(HOLD, Client.Behaviour.ANSWERING);

      assertTrue(client.channel.isActive(), "dropped while plugins held it");
      assertEquals(ProtocolState.CONFIGURATION, setup.client.encoder().state());
      assertEquals(2, client.keepAliveWireIds.size(), "configuration keep-alives, one per 15 s");
      hold.complete(null);
      assertEquals(CREATIVE, setup.player.pendingSwitchTarget(), "the proposed fallback");
      assertInstanceOf(SwitchWaitSessionHandler.class, setup.client.sessionHandler());
      assertTrue(setup.client.channel().isActive());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.HeldPlayerTest#bothSwitches")
    @DisplayName("should keep a client in play alive 40 s, read even if its dead server paused it")
    void inPlay(ProtocolVersion version) {
      SwitchHarness setup = playingOnLobby(version, SURVIVAL.name());
      setup.holdConnections();
      holdEvery(KickedFromServerEvent.class, DECLARED_TIMEOUT_SECONDS);
      MinecraftConnection lobby = setup.current();
      // The lobby stops reading before it dies: its back-pressure pauses the client.
      lobby.channel().unsafe().outboundBuffer().setUserDefinedWritability(1, false);
      setup.runPendingTasks();
      lobby.close();
      setup.runPendingTasks();
      boolean reading = setup.client.channel().config().isAutoRead();
      Client client = Client.of(setup);

      client.waitFor(HOLD, Client.Behaviour.ANSWERING);

      assertTrue(reading, "nothing reads the client at a server's pace any more");
      assertTrue(client.channel.isActive(), "dropped while plugins held it");
      assertEquals(ProtocolState.PLAY, setup.client.encoder().state());
      assertEquals(2, client.keepAliveWireIds.size(), "play keep-alives, one per 15 s");
      hold.complete(null);
      assertEquals(SURVIVAL, setup.player.pendingSwitchTarget(), "the proposed fallback");
    }
  }

  // ---------------------------------------------------------------------------
  // Switch watchdog
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("switch watchdog")
  class Watchdog {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.HeldPlayerTest#bothSwitches")
    @DisplayName("should not give up a switch while a plugin holds it within its declared time-out")
    void pausedByHold(ProtocolVersion version) {
      SwitchHarness setup = playingOnLobby(version);
      setup.holdConnections();
      holdEvery(KickedFromServerEvent.class, DECLARED_TIMEOUT_SECONDS);
      CompletableFuture<ConnectionResult> result =
          setup.player.api().connect(server(setup, "survival"));
      Client client = Client.of(setup);
      client.waitFor(Duration.ofSeconds(10), Client.Behaviour.ANSWERING);
      refuse(setup, SURVIVAL);

      client.waitFor(HOLD, Client.Behaviour.ANSWERING);

      assertTrue(setup.player.isSwitching(), "50 s into the switch, 40 s of them held");
      assertTrue(client.channel.isActive());
      hold.complete(null);
      assertInstanceOf(ConnectionResult.Failed.class, settled(result));
      assertFalse(setup.player.isSwitching());
      assertEquals(List.of(SystemChatMessage.class), types(setup.sentToClient()), "told why");
      assertTrue(client.channel.isActive(), "kept on its server");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.HeldPlayerTest#bothSwitches")
    @DisplayName("should give up a switch after 30 s when nothing holds it")
    void firesWithoutHold(ProtocolVersion version) {
      SwitchHarness setup = playingOnLobby(version);
      setup.holdConnections();
      CompletableFuture<ConnectionResult> result =
          setup.player.api().connect(server(setup, "survival"));
      // Survival accepts the connection, and never logs the player in.
      MinecraftConnection survival = setup.backend(ProtocolState.LOGIN);
      setup.player.serverSwitch().connected(new BackendConnection(survival, SURVIVAL));
      Client client = Client.of(setup);

      client.waitFor(Duration.ofSeconds(29), Client.Behaviour.ANSWERING);
      boolean switchingAt29 = setup.player.isSwitching();
      client.waitFor(Duration.ofSeconds(1), Client.Behaviour.ANSWERING);

      assertTrue(switchingAt29);
      assertFalse(setup.player.isSwitching(), "given up at 30 s");
      assertInstanceOf(ConnectionResult.Failed.class, settled(result));
      assertFalse(survival.channel().isOpen());
      assertTrue(client.channel.isActive(), "kept on its server");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.connection.HeldPlayerTest#bothSwitches")
    @DisplayName("should disconnect a player that plugins hold past their declared time-outs")
    void holdOverrun(ProtocolVersion version) {
      SwitchHarness setup = playingOnLobby(version);
      setup.holdConnections();
      // Asynchronous handlers are skipped at their time-out (here in 20 s of real time, which the
      // test never reaches): only a synchronous handler that blocks can overrun it.
      holdEvery(KickedFromServerEvent.class, 20);
      var _ = setup.player.api().connect(server(setup, "survival"));
      refuse(setup, SURVIVAL);
      Client client = Client.of(setup);

      client.waitFor(Duration.ofSeconds(24), Client.Behaviour.ANSWERING);
      boolean openAt24 = client.channel.isOpen();
      client.waitFor(Duration.ofSeconds(2), Client.Behaviour.ANSWERING);

      assertTrue(openAt24, "20 s declared, and " + ConnectedPlayer.HOLD_GRACE_MS + " ms of grace");
      assertFalse(client.channel.isOpen(), "the player cannot wait for ever");
    }

    @Test
    @DisplayName("should let the new server configure the client for as long as it takes")
    void configurationNotWatched() {
      SwitchHarness setup = playingOnLobby(MODERN);
      setup.holdConnections();
      var _ = setup.player.api().connect(server(setup, "survival"));
      MinecraftConnection survival = setup.login(SURVIVAL);
      setup.fromClient(
          frame(MODERN, ProtocolState.PLAY, PacketDirection.SERVERBOUND, ackConfiguration()));
      Client client = Client.of(setup);

      // A resource pack to download, or a code of conduct to read.
      client.waitFor(Duration.ofSeconds(60), Client.Behaviour.ANSWERING);

      assertTrue(client.channel.isActive());
      assertTrue(setup.player.isSwitching(), "still configured by survival");
      assertTrue(survival.channel().isOpen());
      assertEquals(4, client.keepAliveWireIds.size(), "configuration keep-alives meanwhile");
    }
  }

  // ---------------------------------------------------------------------------
  // What a waiting client sends
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("what a client waiting in the configuration phase sends")
  class WaitingClient {

    @Test
    @DisplayName("should match its answers and keep everything else, in order, for its server")
    void answersReadOthersHeld() {
      SwitchHarness setup = new SwitchHarness(MODERN, List.of(), ProtocolState.CONFIGURATION);
      harnesses.add(setup);
      setup.holdConnections();
      setup.player.join();
      ByteBuf brand = opaqueFrame(0x02);
      byte[] brandBytes = ByteBufUtil.getBytes(brand);
      ByteBuf other = opaqueFrame(0x07);
      byte[] otherBytes = ByteBufUtil.getBytes(other);
      ClientInformation settings =
          new ClientInformation("fr_FR", (byte) 12, 0, true, (byte) 127, 1, false, true, 0);
      Client client = Client.of(setup);

      setup.fromClient(brand);
      client.waitFor(Duration.ofSeconds(15), Client.Behaviour.ANSWERING);
      setup.fromClient(other);
      setup.fromClient(
          frame(MODERN, ProtocolState.CONFIGURATION, PacketDirection.SERVERBOUND, settings));
      MinecraftConnection lobby = setup.login(LOBBY);
      setup.runPendingTasks();

      List<byte[]> sent = setup.framesSentOn(lobby);
      assertEquals(1, client.keepAliveWireIds.size());
      assertEquals(
          Client.ROUND_TRIP_MS, setup.player.measuredLatencyMs(), "its answer was matched");
      assertEquals(4, sent.size(), "Login Acknowledged, settings, then the two frames held");
      assertEquals(
          settings,
          SwitchHarness.decodeFrame(
              sent.get(1),
              MODERN,
              ProtocolState.CONFIGURATION,
              PacketDirection.SERVERBOUND,
              ClientInformation.class));
      assertArrayEquals(brandBytes, sent.get(2));
      assertArrayEquals(otherBytes, sent.get(3));
    }

    @Test
    @DisplayName("should stop reading a client that floods the proxy while it waits")
    void floodPausesReads() {
      SwitchHarness setup = new SwitchHarness(MODERN, List.of(), ProtocolState.CONFIGURATION);
      harnesses.add(setup);
      setup.holdConnections();
      setup.player.join();
      boolean readAtFirst = setup.client.channel().config().isAutoRead();

      for (int i = 0; i < SwitchWaitSessionHandler.MAX_HELD_FRAMES; i++) {
        setup.fromClient(opaqueFrame(0x02));
      }

      assertTrue(readAtFirst);
      assertFalse(setup.client.channel().config().isAutoRead());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * The outcome of a connection request, settled by now: the bus delivers on the thread that fires
   * and the channels are embedded, so nothing is left to wait for. A mutant that never settles it
   * fails the test here, instead of hanging it until PIT's time-out.
   */
  private static ConnectionResult settled(CompletableFuture<ConnectionResult> result) {
    assertTrue(result.isDone(), "the connection request is settled");
    return result.join();
  }

  /** Makes every handler of {@code type} hold the event until {@link #hold} completes. */
  private void holdEvery(Class<?> type, int timeoutSeconds) {
    events.subscribeAsync(type, Subscribe.NORMAL, timeoutSeconds, e -> hold);
  }

  /** A player playing on the lobby, its keep-alives started, everything it was sent read. */
  private SwitchHarness playingOnLobby(ProtocolVersion version, String... fallbackOrder) {
    SwitchHarness setup = new SwitchHarness(version, List.of(fallbackOrder), ProtocolState.PLAY);
    when(setup.player.loginContext().events()).thenReturn(bus);
    harnesses.add(setup);
    if (version.supportsConfigurationState()) {
      MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
      setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
      setup.player.setCurrentServerName(LOBBY.name());
      lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
      setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
      // The lobby's Join Game ends the configuration phase, as on a real server.
      setup.receive(lobby, frame(version, new JoinGame(1, false, new JoinGame.Opaque(JOIN_GAME))));
    } else {
      setup.spawnOn(LOBBY);
    }
    setup.sentToClient();
    return setup;
  }

  /** The server opens the connection, then refuses the player. */
  private static void refuse(SwitchHarness setup, dev.warp.api.server.ServerInfo target) {
    MinecraftConnection backend = setup.backend(ProtocolState.LOGIN);
    setup.player.serverSwitch().connected(new BackendConnection(backend, target));
    setup.player.recordKick(backend, Component.text("The server is full"));
    setup.player.scheduleBackendFailure(backend);
    setup.runPendingTasks();
    setup.sentToClient();
  }

  private static AcknowledgeConfiguration ackConfiguration() {
    return new AcknowledgeConfiguration();
  }

  /** The connection handler of a pipeline Warp built. */
  private static MinecraftConnection connectionOf(EmbeddedChannel channel) {
    return Objects.requireNonNull(channel.pipeline().get(MinecraftConnection.class));
  }

  /** The read time-out of a pipeline Warp built, in milliseconds. */
  private static long readTimeoutMillis(EmbeddedChannel channel) {
    return assertInstanceOf(
            IdleStateHandler.class, channel.pipeline().get(ServerChannelInitializer.READ_TIMEOUT))
        .getReaderIdleTimeInMillis();
  }

  private static BackendServer server(SwitchHarness setup, String name) {
    return Objects.requireNonNull(
        setup.player.loginContext().serverRegistry().getServer(name), name);
  }

  /**
   * Logs a client of {@code version} in through the pipeline Warp builds, read time-out included,
   * and returns it once the player joined: in the configuration state from 1.20.2, in play before.
   */
  private Client loggedIn(ProtocolVersion version) {
    return loggedIn(version, TestLoginContexts.offline(bus));
  }

  /**
   * Logs a client of {@code version} in as {@link #loggedIn(ProtocolVersion)}, on {@code context}.
   */
  private Client loggedIn(ProtocolVersion version, ServerLoginContext context) {
    EmbeddedChannel channel = new ClientChannel();
    channels.add(channel);
    channel.pipeline().addLast(new ServerChannelInitializer(context));
    channel.runPendingTasks();
    Client client =
        new Client(
            channel,
            Objects.requireNonNull(channel.pipeline().get(MinecraftConnection.class)),
            version,
            channel::runPendingTasks);
    client.send(
        ProtocolState.HANDSHAKE,
        new Handshake(version.protocol(), "localhost", 25577, LOGIN_NEXT_STATE));
    UUID uuid = UUID.nameUUIDFromBytes("Steve".getBytes(StandardCharsets.UTF_8));
    client.send(ProtocolState.LOGIN, new LoginStart("Steve", uuid));
    if (version.supportsConfigurationState()) {
      client.send(ProtocolState.LOGIN, new LoginAcknowledged());
    }
    client.discardReceived();
    client.player = Objects.requireNonNull(context.playerRegistry().get("Steve"), "not joined");
    return client;
  }

  /**
   * The vanilla client's side of a held player: lets time pass on the proxy's clock, a second at a
   * time, and answers every keep-alive at once in the state it reads, as a vanilla client does.
   */
  private static final class Client {

    /** How the client behaves while time passes. */
    enum Behaviour {
      /** Answers every keep-alive. */
      ANSWERING,
      /** Answers no keep-alive, but sends something else every 5 s. */
      CHATTING,
      /** Sends nothing at all. */
      SILENT
    }

    final EmbeddedChannel channel;
    final MinecraftConnection connection;
    private final ProtocolVersion version;
    private final Runnable runTasks;

    /** Time between a keep-alive reaching the client and its answer reaching the proxy. */
    static final long ROUND_TRIP_MS = 20;

    /** The wire id of every keep-alive the client received, in order. */
    final List<Integer> keepAliveWireIds = new ArrayList<>();

    private int answerWireId = -1;
    private long seconds;

    /** Backend connections whose time passes with the client's, as on the event loop they share. */
    private final List<EmbeddedChannel> backends = new ArrayList<>();

    /** The player, once it joined. */
    private @Nullable ConnectedPlayer player;

    Client(
        EmbeddedChannel channel,
        MinecraftConnection connection,
        ProtocolVersion version,
        Runnable runTasks) {
      this.channel = channel;
      this.connection = connection;
      this.version = version;
      this.runTasks = runTasks;
      // Time moves only when the test lets it pass, not with the real clock as well.
      channel.freezeTime();
    }

    static Client of(SwitchHarness setup) {
      Client client =
          new Client(
              (EmbeddedChannel) setup.client.channel(),
              setup.client,
              setup.version,
              setup::runPendingTasks);
      client.player = setup.player;
      return client;
    }

    ConnectedPlayer player() {
      return Objects.requireNonNull(player, "not joined");
    }

    int answerWireId() {
      return answerWireId;
    }

    /** Lets time pass on {@code backend} with the client's from now on. */
    void shareClock(EmbeddedChannel backend) {
      backend.freezeTime();
      backends.add(backend);
    }

    void waitFor(Duration duration, Behaviour behaviour) {
      for (long i = 0; i < duration.toSeconds() && channel.isOpen(); i++) {
        channel.advanceTimeBy(1, TimeUnit.SECONDS);
        // A copy: a backend that times out sends the player to a fallback, which opens another.
        for (EmbeddedChannel backend : List.copyOf(backends)) {
          backend.advanceTimeBy(1, TimeUnit.SECONDS);
          backend.runPendingTasks();
        }
        runTasks.run();
        seconds++;
        readReceived(behaviour == Behaviour.ANSWERING);
        if (behaviour == Behaviour.CHATTING && seconds % 5 == 0 && channel.isOpen()) {
          channel.writeInbound(opaqueFrame(0x7F));
          runTasks.run();
        }
      }
    }

    void send(ProtocolState state, dev.warp.protocol.packet.Packet packet) {
      channel.writeInbound(frame(version, state, PacketDirection.SERVERBOUND, packet));
      runTasks.run();
    }

    void discardReceived() {
      for (ByteBuf frame = channel.readOutbound(); frame != null; frame = channel.readOutbound()) {
        frame.release();
      }
    }

    /** Reads what the proxy sent; answers the keep-alives if {@code answer}. */
    private void readReceived(boolean answer) {
      ProtocolState state = connection.encoder().state();
      int keepAliveId =
          StateRegistry.get(state, PacketDirection.CLIENTBOUND).packetId(version, KeepAlive.class);
      List<KeepAlive> received = new ArrayList<>();
      for (ByteBuf frame = channel.readOutbound(); frame != null; frame = channel.readOutbound()) {
        try {
          VarInt.read(frame);
          int id = VarInt.read(frame);
          if (id == keepAliveId) {
            keepAliveWireIds.add(id);
            received.add(KeepAlive.CODEC.decode(frame, version));
          }
        } finally {
          frame.release();
        }
      }
      if (!answer || !channel.isOpen()) {
        return;
      }
      ProtocolState answerState = connection.decoder().state();
      answerWireId =
          StateRegistry.get(answerState, PacketDirection.SERVERBOUND)
              .packetId(version, KeepAlive.class);
      if (received.isEmpty()) {
        return;
      }
      channel.advanceTimeBy(ROUND_TRIP_MS, TimeUnit.MILLISECONDS);
      for (KeepAlive keepAlive : received) {
        channel.writeInbound(
            frame(
                version, answerState, PacketDirection.SERVERBOUND, new KeepAlive(keepAlive.id())));
        runTasks.run();
      }
    }
  }

  /** A client connection with a remote address, as the pipeline expects. */
  private static final class ClientChannel extends EmbeddedChannel {

    private static final InetSocketAddress CLIENT_ADDRESS =
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 50_000);

    @Override
    protected SocketAddress remoteAddress0() {
      return CLIENT_ADDRESS;
    }
  }
}
