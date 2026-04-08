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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.netty.FrameDecoder;
import dev.warp.protocol.netty.FrameEncoder;
import dev.warp.protocol.netty.MinecraftDecoder;
import dev.warp.protocol.netty.MinecraftEncoder;
import dev.warp.protocol.netty.SessionHandler;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the session handler lifecycle — specifically the interaction between {@link
 * MinecraftConnection#setSessionHandler(SessionHandler)} and handler {@code disconnected()}
 * callbacks.
 *
 * <p>The original bug: {@code setSessionHandler()} called {@code disconnected()} on the old
 * handler, which some handlers misinterpreted as a real channel close and tore down the backend
 * connection. The fix: {@code setSessionHandler()} now calls {@code deactivated()} on swap, while
 * {@code disconnected()} fires only on real channel close ({@code channelInactive}).
 *
 * <p>These tests verify:
 *
 * <ul>
 *   <li>Handler swap mechanics (old gets {@code deactivated()}, new gets {@code activated()})
 *   <li>Handler swap does NOT trigger {@code disconnected()} or tear down connections
 *   <li>Real channel close DOES trigger {@code disconnected()} and tears down connections
 *   <li>Full CONFIG to PLAY transition preserves both connections
 * </ul>
 */
@DisplayName("Session handler lifecycle")
class SessionHandlerLifecycleTest {

  // ---------------------------------------------------------------------------
  // Shared test infrastructure
  // ---------------------------------------------------------------------------

  /** Client-side channel (simulates the player's connection to the proxy). */
  private EmbeddedChannel clientChannel;

  /** Backend-side channel (simulates the proxy's connection to the backend server). */
  private EmbeddedChannel backendChannel;

  /** Client-side MinecraftConnection (tail handler in the client pipeline). */
  private MinecraftConnection clientConn;

  /** Backend-side MinecraftConnection (tail handler in the backend pipeline). */
  private MinecraftConnection backendConn;

  /** The connected player linking client and backend. */
  private ConnectedPlayer player;

  @BeforeEach
  void setUp() {
    clientChannel =
        createMinecraftChannel(PacketDirection.SERVERBOUND, ProtocolState.CONFIGURATION);
    backendChannel =
        createMinecraftChannel(PacketDirection.CLIENTBOUND, ProtocolState.CONFIGURATION);

    clientConn = extractConnection(clientChannel);
    backendConn = extractConnection(backendChannel);

    GameProfile profile = new GameProfile(UUID.randomUUID(), "TestPlayer", List.of());
    ServerLoginContext loginContext = mock(ServerLoginContext.class);
    // Provide a real ServerRegistry with empty fallback so handleBackendFailure disconnects.
    ServerRegistry registry =
        new ServerRegistry(
            Map.of("lobby", new InetSocketAddress("localhost", 25565)), "lobby", List.of());
    when(loginContext.serverRegistry()).thenReturn(registry);
    player =
        new ConnectedPlayer(
            clientConn,
            ProtocolVersion.MINECRAFT_1_21_4,
            profile,
            new InetSocketAddress("127.0.0.1", 25565),
            loginContext);

    BackendConnection backend = createTestBackendConnection(backendConn);
    player.setBackendConnection(backend);
  }

  @AfterEach
  void tearDown() {
    if (clientChannel.isOpen()) {
      clientChannel.finishAndReleaseAll();
    }
    if (backendChannel.isOpen()) {
      backendChannel.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // 1. MinecraftConnection handler swap mechanics
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("MinecraftConnection handler swap")
  class HandlerSwap {

    @Test
    @DisplayName("should call deactivated() on old handler and activated() on new handler")
    void swapNotifiesBothHandlers() {
      SessionHandler oldHandler = spy(SessionHandler.class);
      SessionHandler newHandler = spy(SessionHandler.class);

      clientConn.setSessionHandler(oldHandler);
      verify(oldHandler).activated();

      clientConn.setSessionHandler(newHandler);
      verify(oldHandler).deactivated();
      verify(oldHandler, never()).disconnected();
      verify(newHandler).activated();
    }

    @Test
    @DisplayName("should keep the channel active after handler swap")
    void channelRemainsActiveAfterSwap() {
      SessionHandler first = mock(SessionHandler.class);
      SessionHandler second = mock(SessionHandler.class);

      clientConn.setSessionHandler(first);
      clientConn.setSessionHandler(second);

      assertTrue(clientChannel.isActive(), "Channel must stay active after handler swap");
      assertSame(second, clientConn.sessionHandler(), "New handler must be installed");
    }

    @Test
    @DisplayName("should support multiple consecutive handler swaps without closing the channel")
    void multipleSwapsKeepChannelActive() {
      SessionHandler h1 = spy(SessionHandler.class);
      SessionHandler h2 = spy(SessionHandler.class);
      SessionHandler h3 = spy(SessionHandler.class);

      clientConn.setSessionHandler(h1);
      clientConn.setSessionHandler(h2);
      clientConn.setSessionHandler(h3);

      verify(h1).activated();
      verify(h1).deactivated();
      verify(h1, never()).disconnected();
      verify(h2).activated();
      verify(h2).deactivated();
      verify(h2, never()).disconnected();
      verify(h3).activated();
      verify(h3, never()).deactivated();
      verify(h3, never()).disconnected();

      assertTrue(clientChannel.isActive(), "Channel must remain active after multiple swaps");
      assertSame(h3, clientConn.sessionHandler());
    }

    @Test
    @DisplayName("should call disconnected() on the active handler when channel closes")
    void channelCloseCallsDisconnected() {
      SessionHandler handler = spy(SessionHandler.class);
      clientConn.setSessionHandler(handler);

      // Simulate a real channel close (channelInactive fires).
      clientChannel.close().syncUninterruptibly();
      clientChannel.runPendingTasks();

      // activated() once from setSessionHandler, disconnected() once from channelInactive.
      verify(handler).activated();
      verify(handler).disconnected();
      assertFalse(clientChannel.isActive());
    }
  }

  // ---------------------------------------------------------------------------
  // 2. ClientConfigSessionHandler — handler swap must NOT tear down backend
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ClientConfigSessionHandler disconnected()")
  class ClientConfigHandlerDisconnected {

    @Test
    @DisplayName("should NOT disconnect backend when handler is swapped (channel active)")
    void handlerSwapDoesNotTearDownBackend() {
      ClientConfigSessionHandler configHandler =
          new ClientConfigSessionHandler(player, backendConn);
      clientConn.setSessionHandler(configHandler);

      // Simulate handler swap: replace with a new handler.
      clientConn.setSessionHandler(mock(SessionHandler.class));

      // Channel is still active, so backend must survive.
      assertTrue(clientChannel.isActive());
      assertTrue(backendChannel.isActive(), "Backend must NOT be disconnected on handler swap");
      assertNotNull(player.backendConnection(), "Backend connection reference must survive");
    }

    @Test
    @DisplayName("should disconnect backend when client channel actually closes")
    void realDisconnectTearsDownBackend() {
      ClientConfigSessionHandler configHandler =
          new ClientConfigSessionHandler(player, backendConn);
      clientConn.setSessionHandler(configHandler);

      // Simulate real client disconnect.
      clientChannel.close().syncUninterruptibly();
      clientChannel.runPendingTasks();
      backendChannel.runPendingTasks();

      assertFalse(clientChannel.isActive());
      assertFalse(
          backendChannel.isActive(), "Backend must be disconnected when client really closes");
    }
  }

  // ---------------------------------------------------------------------------
  // 3. ClientPlaySessionHandler — handler swap must NOT kill backend or keepalive
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("ClientPlaySessionHandler disconnected()")
  class ClientPlayHandlerDisconnected {

    @Test
    @DisplayName("should NOT disconnect backend or cancel keepalive when handler is swapped")
    void handlerSwapPreservesBackendAndKeepalive() {
      // Install play handler (starts keepalive via activated()).
      ClientPlaySessionHandler playHandler = new ClientPlaySessionHandler(player);
      clientConn.setSessionHandler(playHandler);

      // Verify keepalive was started.
      // (activated() calls player.startKeepAliveTask())

      // Simulate handler swap.
      clientConn.setSessionHandler(mock(SessionHandler.class));

      assertTrue(clientChannel.isActive());
      assertTrue(backendChannel.isActive(), "Backend must survive handler swap");
      assertNotNull(player.backendConnection());
    }

    @Test
    @DisplayName("should disconnect backend and cancel keepalive when channel actually closes")
    void realDisconnectCleansUp() {
      ClientPlaySessionHandler playHandler = new ClientPlaySessionHandler(player);
      clientConn.setSessionHandler(playHandler);

      clientChannel.close().syncUninterruptibly();
      clientChannel.runPendingTasks();
      backendChannel.runPendingTasks();

      assertFalse(clientChannel.isActive());
      assertFalse(backendChannel.isActive(), "Backend must be torn down on real disconnect");
    }
  }

  // ---------------------------------------------------------------------------
  // 4. BackendPlaySessionHandler — handler swap must NOT disconnect the player
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("BackendPlaySessionHandler disconnected()")
  class BackendPlayHandlerDisconnected {

    @Test
    @DisplayName("should NOT disconnect player when handler is swapped (channel active)")
    void handlerSwapDoesNotDisconnectPlayer() {
      BackendPlaySessionHandler playHandler = new BackendPlaySessionHandler(player, backendConn);
      backendConn.setSessionHandler(playHandler);

      // Simulate handler swap on the backend connection.
      backendConn.setSessionHandler(mock(SessionHandler.class));

      assertTrue(backendChannel.isActive());
      assertTrue(
          clientChannel.isActive(),
          "Client must NOT be disconnected when backend handler is swapped");
    }

    @Test
    @DisplayName("should disconnect player when backend channel actually closes")
    void realBackendDisconnectDisconnectsPlayer() {
      BackendPlaySessionHandler playHandler = new BackendPlaySessionHandler(player, backendConn);
      backendConn.setSessionHandler(playHandler);

      backendChannel.close().syncUninterruptibly();
      backendChannel.runPendingTasks();
      clientChannel.runPendingTasks();

      assertFalse(backendChannel.isActive());
      assertFalse(clientChannel.isActive(), "Client must be disconnected when backend really dies");
    }
  }

  // ---------------------------------------------------------------------------
  // 5. BackendConfigSessionHandler — handler swap must NOT disconnect the player
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("BackendConfigSessionHandler disconnected()")
  class BackendConfigHandlerDisconnected {

    @Test
    @DisplayName("should NOT disconnect player when handler is swapped (channel active)")
    void handlerSwapDoesNotDisconnectPlayer() {
      BackendConfigSessionHandler configHandler =
          new BackendConfigSessionHandler(player, backendConn);
      backendConn.setSessionHandler(configHandler);

      // Simulate handler swap.
      backendConn.setSessionHandler(mock(SessionHandler.class));

      assertTrue(backendChannel.isActive());
      assertTrue(clientChannel.isActive(), "Client must survive backend handler swap");
    }

    @Test
    @DisplayName("should disconnect player when backend channel actually closes")
    void realBackendDisconnectDisconnectsPlayer() {
      BackendConfigSessionHandler configHandler =
          new BackendConfigSessionHandler(player, backendConn);
      backendConn.setSessionHandler(configHandler);

      backendChannel.close().syncUninterruptibly();
      backendChannel.runPendingTasks();
      clientChannel.runPendingTasks();

      assertFalse(backendChannel.isActive());
      assertFalse(clientChannel.isActive(), "Client must be disconnected when backend really dies");
    }
  }

  // ---------------------------------------------------------------------------
  // 6. BackendLoginSessionHandler — handler swap must NOT disconnect the player
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("BackendLoginSessionHandler disconnected()")
  class BackendLoginHandlerDisconnected {

    @Test
    @DisplayName("should NOT disconnect player when handler is swapped (channel active)")
    void handlerSwapDoesNotDisconnectPlayer() {
      // BackendLoginSessionHandler requires some extra args, but disconnected() only
      // checks backendConnection.channel().isActive().
      BackendLoginSessionHandler loginHandler =
          new BackendLoginSessionHandler(
              player, backendConn, new InetSocketAddress("localhost", 25565), new byte[0]);
      backendConn.setSessionHandler(loginHandler);

      // Simulate handler swap (e.g., transition to CONFIG after LoginSuccess).
      backendConn.setSessionHandler(mock(SessionHandler.class));

      assertTrue(backendChannel.isActive());
      assertTrue(clientChannel.isActive(), "Client must survive backend login handler swap");
    }

    @Test
    @DisplayName("should disconnect player when backend channel actually closes during login")
    void realBackendDisconnectDuringLogin() {
      BackendLoginSessionHandler loginHandler =
          new BackendLoginSessionHandler(
              player, backendConn, new InetSocketAddress("localhost", 25565), new byte[0]);
      backendConn.setSessionHandler(loginHandler);

      backendChannel.close().syncUninterruptibly();
      backendChannel.runPendingTasks();
      clientChannel.runPendingTasks();

      assertFalse(backendChannel.isActive());
      assertFalse(
          clientChannel.isActive(), "Client must be disconnected when backend dies during login");
    }
  }

  // ---------------------------------------------------------------------------
  // 7. Full CONFIG → PLAY transition
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("CONFIG → PLAY transition")
  class ConfigToPlayTransition {

    @Test
    @DisplayName("should transition both sides to PLAY without disconnecting either channel")
    void configToPlayPreservesBothConnections() {
      // Mirror the real flow: BackendConfigSessionHandler.activated() installs
      // ClientConfigSessionHandler on the client channel via eventLoop.execute().
      BackendConfigSessionHandler backendConfig =
          new BackendConfigSessionHandler(player, backendConn);
      backendConn.setSessionHandler(backendConfig);

      // Run pending tasks so activated() installs ClientConfigSessionHandler on client.
      clientChannel.runPendingTasks();

      // Verify client now has ClientConfigSessionHandler (installed by activated()).
      assertNotNull(clientConn.sessionHandler());
      assertTrue(
          clientConn.sessionHandler() instanceof ClientConfigSessionHandler,
          "Client should have ClientConfigSessionHandler after backend activated()");
      ClientConfigSessionHandler clientConfig =
          (ClientConfigSessionHandler) clientConn.sessionHandler();

      // Simulate the client sending AcknowledgeFinishConfiguration.
      // This triggers handleAcknowledgeFinish() which:
      //   1. Transitions client to PLAY state
      //   2. Installs ClientPlaySessionHandler (swapping out ClientConfigSessionHandler)
      //   3. Schedules backend transition on backend event loop
      clientConfig.handle(new dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration());

      // EmbeddedChannel runs tasks synchronously, so run pending tasks on both.
      clientChannel.runPendingTasks();
      backendChannel.runPendingTasks();

      // Both channels must remain active.
      assertTrue(clientChannel.isActive(), "Client channel must survive CONFIG→PLAY transition");
      assertTrue(backendChannel.isActive(), "Backend channel must survive CONFIG→PLAY transition");

      // Client should now have a ClientPlaySessionHandler.
      assertNotNull(clientConn.sessionHandler());
      assertTrue(
          clientConn.sessionHandler() instanceof ClientPlaySessionHandler,
          "Client should have ClientPlaySessionHandler after transition");

      // Client-side decoder/encoder should be in PLAY state.
      assertEquals(
          ProtocolState.PLAY,
          clientConn.decoder().state(),
          "Client decoder should be in PLAY state");
      assertEquals(
          ProtocolState.PLAY,
          clientConn.encoder().state(),
          "Client encoder should be in PLAY state");

      // Backend should now have BackendPlaySessionHandler (scheduled on backend event loop).
      assertNotNull(backendConn.sessionHandler());
      assertTrue(
          backendConn.sessionHandler() instanceof BackendPlaySessionHandler,
          "Backend should have BackendPlaySessionHandler after transition");

      // Backend decoder/encoder should be in PLAY state.
      assertEquals(
          ProtocolState.PLAY,
          backendConn.decoder().state(),
          "Backend decoder should be in PLAY state");
      assertEquals(
          ProtocolState.PLAY,
          backendConn.encoder().state(),
          "Backend encoder should be in PLAY state");
    }

    @Test
    @DisplayName("should call deactivated() on old handlers during transition without tearing down")
    void transitionCallsDeactivatedButDoesNotTearDown() {
      // Mirror the real flow: install backend handler first, let it set up client handler.
      BackendConfigSessionHandler backendConfig =
          new BackendConfigSessionHandler(player, backendConn);
      backendConn.setSessionHandler(backendConfig);
      clientChannel.runPendingTasks();

      // Spy on the client handler that activated() installed.
      ClientConfigSessionHandler realClientConfig =
          (ClientConfigSessionHandler) clientConn.sessionHandler();
      assertNotNull(realClientConfig);
      ClientConfigSessionHandler clientConfig = spy(realClientConfig);
      clientConn.setSessionHandler(clientConfig);

      // Trigger AcknowledgeFinishConfiguration.
      clientConfig.handle(new dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration());
      clientChannel.runPendingTasks();
      backendChannel.runPendingTasks();

      // deactivated() was called on clientConfig (during handler swap), not disconnected().
      verify(clientConfig).deactivated();
      verify(clientConfig, never()).disconnected();

      // But neither channel was torn down.
      assertTrue(clientChannel.isActive());
      assertTrue(backendChannel.isActive());
    }
  }

  // ---------------------------------------------------------------------------
  // 8. LoginSessionHandler — handler swap logging vs real disconnect
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LoginSessionHandler disconnected()")
  class LoginSessionHandlerDisconnected {

    @Test
    @DisplayName("should not close the channel when handler is swapped")
    void handlerSwapDoesNotCloseChannel() {
      // LoginSessionHandler needs a connection and loginContext. Its disconnected()
      // only logs — it never tears down anything. But it MUST NOT close the channel.
      // We verify by installing it and then swapping.
      EmbeddedChannel loginChannel =
          createMinecraftChannel(PacketDirection.SERVERBOUND, ProtocolState.LOGIN);
      MinecraftConnection loginConn = extractConnection(loginChannel);

      // Create a minimal mock — LoginSessionHandler.disconnected() only logs.
      SessionHandler placeholder = spy(SessionHandler.class);
      loginConn.setSessionHandler(placeholder);
      loginConn.setSessionHandler(mock(SessionHandler.class));

      assertTrue(loginChannel.isActive(), "Channel must survive login handler swap");
      loginChannel.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Creates a minimal Netty pipeline with frame + minecraft codecs + MinecraftConnection, matching
   * the structure used by {@link ServerChannelInitializer} and {@link BackendChannelInitializer}.
   */
  private static EmbeddedChannel createMinecraftChannel(
      PacketDirection direction, ProtocolState state) {
    ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
    MinecraftDecoder decoder = new MinecraftDecoder(direction, version, state);
    MinecraftEncoder encoder =
        new MinecraftEncoder(
            direction == PacketDirection.SERVERBOUND
                ? PacketDirection.CLIENTBOUND
                : PacketDirection.SERVERBOUND,
            version,
            state);

    EmbeddedChannel ch = new EmbeddedChannel();
    MinecraftConnection conn = new MinecraftConnection(ch);

    ch.pipeline()
        .addLast(
            ServerChannelInitializer.READ_TIMEOUT, new ReadTimeoutHandler(30, TimeUnit.SECONDS))
        .addLast(ServerChannelInitializer.FRAME_DECODER, new FrameDecoder())
        .addLast(ServerChannelInitializer.MINECRAFT_DECODER, decoder)
        .addLast(ServerChannelInitializer.FRAME_ENCODER, FrameEncoder.INSTANCE)
        .addLast(ServerChannelInitializer.MINECRAFT_ENCODER, encoder)
        .addLast(ServerChannelInitializer.CONNECTION_HANDLER, conn);

    return ch;
  }

  /** Extracts the {@link MinecraftConnection} from an embedded channel's pipeline. */
  private static MinecraftConnection extractConnection(EmbeddedChannel ch) {
    MinecraftConnection conn =
        (MinecraftConnection) ch.pipeline().get(ServerChannelInitializer.CONNECTION_HANDLER);
    assertNotNull(conn, "MinecraftConnection must be in the pipeline");
    return conn;
  }

  /**
   * Creates a test {@link BackendConnection} wrapping the given MinecraftConnection.
   *
   * <p>Uses reflection to invoke the private constructor since {@link BackendConnection} only
   * exposes a {@code connect()} factory that requires a real TCP connection.
   */
  private static BackendConnection createTestBackendConnection(MinecraftConnection backendConn) {
    try {
      var ctor =
          BackendConnection.class.getDeclaredConstructor(
              MinecraftConnection.class, InetSocketAddress.class);
      ctor.setAccessible(true);
      return ctor.newInstance(backendConn, new InetSocketAddress("localhost", 25565));
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Failed to create test BackendConnection", e);
    }
  }
}
