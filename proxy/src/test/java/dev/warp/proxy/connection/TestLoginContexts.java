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

import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.auth.ProfileKeys;
import dev.warp.proxy.command.ProxyCommands;
import dev.warp.proxy.command.WarpCommandManager;
import dev.warp.proxy.config.DuplicateLoginPolicy;
import dev.warp.proxy.config.ForwardingMode;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.permission.PermissionRegistry;
import dev.warp.proxy.player.PlayerRegistry;
import dev.warp.proxy.server.ServerRegistry;
import dev.warp.proxy.status.ServerList;
import dev.warp.proxy.status.StatusSettings;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import io.netty.channel.socket.nio.NioSocketChannel;

/** {@link ServerLoginContext}s for tests that drive a client pipeline built by Warp. */
final class TestLoginContexts {

  /** An event bus nobody subscribes to, shared by the tests. */
  static final WarpEventBus EVENTS = new WarpEventBus();

  /**
   * One key pair for every test, as generating RSA keys is slow. The tests do not depend on its
   * size, so it is not the protocol's 1024 bits but the 2048 that key-size checks expect.
   */
  private static final KeyPair KEY_PAIR = generateKeyPair();

  private TestLoginContexts() {}

  /**
   * Returns an offline-mode context without compression or forwarding, whose only server is
   * unreachable.
   *
   * <p>Connecting to the server fails at once: an NIO channel cannot register with the embedded
   * event loop the client connection runs on, which the login handler sees as an unreachable
   * server.
   *
   * @return the context
   */
  static ServerLoginContext offline() {
    return offline(ProfileKeys.mojang());
  }

  /**
   * Returns {@link #offline()} with another checker of profile keys.
   *
   * @param profileKeys the checker of 1.19 to 1.19.2 profile keys
   * @return the context
   */
  static ServerLoginContext offline(ProfileKeys profileKeys) {
    return context(false, -1, new MojangSessionService(), profileKeys);
  }

  /**
   * Returns {@link #offline()} compressing from {@code compressionThreshold} bytes.
   *
   * @param compressionThreshold the configured threshold, or {@code -1} for no compression
   * @return the context
   */
  static ServerLoginContext offline(int compressionThreshold) {
    return context(false, compressionThreshold, new MojangSessionService(), ProfileKeys.mojang());
  }

  /**
   * An offline-mode context that settles duplicate logins with {@code policy}.
   *
   * @param policy the duplicate login policy
   * @return the context
   */
  static ServerLoginContext offline(DuplicateLoginPolicy policy) {
    return context(false, -1, new MojangSessionService(), ProfileKeys.mojang(), policy);
  }

  /**
   * An offline-mode context whose events go to {@code events}.
   *
   * @param events the event bus
   * @return the context
   */
  static ServerLoginContext offline(WarpEventBus events) {
    return context(
        false,
        -1,
        new MojangSessionService(),
        ProfileKeys.mojang(),
        DuplicateLoginPolicy.AUTO,
        events,
        false);
  }

  /**
   * An offline-mode context whose events go to {@code events}, with the server list entry and
   * player limit of {@code status}.
   *
   * @param events the event bus
   * @param status the status settings
   * @return the context
   */
  static ServerLoginContext offline(WarpEventBus events, StatusSettings status) {
    return context(
        false,
        -1,
        new MojangSessionService(),
        ProfileKeys.mojang(),
        DuplicateLoginPolicy.AUTO,
        events,
        false,
        status);
  }

  /**
   * Returns {@link #offline()}, letting in the players a Transfer packet sends to the proxy if
   * {@code acceptsTransfers}.
   *
   * @param acceptsTransfers the {@code accepts-transfers} setting
   * @return the context
   */
  static ServerLoginContext offlineAcceptingTransfers(boolean acceptsTransfers) {
    return context(
        false,
        -1,
        new MojangSessionService(),
        ProfileKeys.mojang(),
        DuplicateLoginPolicy.AUTO,
        EVENTS,
        acceptsTransfers);
  }

  /**
   * Returns {@link #offline()} in online mode: players authenticate with {@code sessionService}.
   *
   * @param sessionService the session service, usually a mock
   * @param profileKeys the checker of 1.19 to 1.19.2 profile keys
   * @return the context
   */
  static ServerLoginContext online(MojangSessionService sessionService, ProfileKeys profileKeys) {
    return context(true, -1, sessionService, profileKeys);
  }

  private static ServerLoginContext context(
      boolean onlineMode,
      int compressionThreshold,
      MojangSessionService sessionService,
      ProfileKeys profileKeys) {
    return context(
        onlineMode, compressionThreshold, sessionService, profileKeys, DuplicateLoginPolicy.AUTO);
  }

  private static ServerLoginContext context(
      boolean onlineMode,
      int compressionThreshold,
      MojangSessionService sessionService,
      ProfileKeys profileKeys,
      DuplicateLoginPolicy policy) {
    return context(
        onlineMode, compressionThreshold, sessionService, profileKeys, policy, EVENTS, false);
  }

  private static ServerLoginContext context(
      boolean onlineMode,
      int compressionThreshold,
      MojangSessionService sessionService,
      ProfileKeys profileKeys,
      DuplicateLoginPolicy policy,
      WarpEventBus events,
      boolean acceptsTransfers) {
    return context(
        onlineMode,
        compressionThreshold,
        sessionService,
        profileKeys,
        policy,
        events,
        acceptsTransfers,
        StatusSettings.defaults());
  }

  private static ServerLoginContext context(
      boolean onlineMode,
      int compressionThreshold,
      MojangSessionService sessionService,
      ProfileKeys profileKeys,
      DuplicateLoginPolicy policy,
      WarpEventBus events,
      boolean acceptsTransfers,
      StatusSettings status) {
    return context(
        onlineMode,
        compressionThreshold,
        sessionService,
        profileKeys,
        policy,
        events,
        acceptsTransfers,
        status,
        ClientLimits.unlimited(),
        new ServerRegistry(
            Map.of("lobby", new InetSocketAddress(InetAddress.getLoopbackAddress(), 1)),
            "lobby",
            List.of("lobby")),
        ForwardingMode.NONE,
        new byte[0],
        NioSocketChannel.class);
  }

  @SuppressWarnings("ParameterNumber") // every knob of the context, for the factories above
  private static ServerLoginContext context(
      boolean onlineMode,
      int compressionThreshold,
      MojangSessionService sessionService,
      ProfileKeys profileKeys,
      DuplicateLoginPolicy policy,
      WarpEventBus events,
      boolean acceptsTransfers,
      StatusSettings status,
      ClientLimits limits,
      ServerRegistry registry,
      ForwardingMode forwardingMode,
      byte[] forwardingSecret,
      Class<? extends io.netty.channel.Channel> channelClass) {
    PlayerRegistry players = new PlayerRegistry();
    return new ServerLoginContext(
        KEY_PAIR,
        onlineMode,
        compressionThreshold,
        Deflater.DEFAULT_COMPRESSION,
        true,
        sessionService,
        profileKeys,
        new PlaySession(),
        registry,
        players,
        new ServerList(status, players),
        policy,
        events,
        new ChannelRegistry(),
        commands(registry),
        new PermissionRegistry(),
        forwardingMode,
        forwardingSecret,
        channelClass,
        acceptsTransfers,
        limits);
  }

  /**
   * Returns an offline-mode context without compression whose only server, {@code lobby}, is at
   * {@code server} and receives players through Velocity modern forwarding signed with {@code
   * secret}. Its connections are real NIO sockets.
   *
   * @param server the address of the backend server
   * @param secret the forwarding secret
   * @return the context
   */
  static ServerLoginContext velocityForwarding(InetSocketAddress server, byte[] secret) {
    return context(
        false,
        -1,
        new MojangSessionService(),
        ProfileKeys.mojang(),
        DuplicateLoginPolicy.AUTO,
        EVENTS,
        false,
        StatusSettings.defaults(),
        ClientLimits.unlimited(),
        new ServerRegistry(Map.of("lobby", server), "lobby", List.of("lobby")),
        ForwardingMode.VELOCITY,
        secret,
        NioSocketChannel.class);
  }

  /**
   * Returns {@link #offline()}, or its online-mode form authenticating with {@code sessionService},
   * whose connections count against {@code limits}.
   *
   * @param onlineMode whether players authenticate
   * @param sessionService the session service, usually a mock
   * @param limits the limits on client addresses
   * @return the context
   */
  static ServerLoginContext limited(
      boolean onlineMode, MojangSessionService sessionService, ClientLimits limits) {
    return context(
        onlineMode,
        -1,
        sessionService,
        ProfileKeys.mojang(),
        DuplicateLoginPolicy.AUTO,
        EVENTS,
        false,
        StatusSettings.defaults(),
        limits,
        new ServerRegistry(
            Map.of("lobby", new InetSocketAddress(InetAddress.getLoopbackAddress(), 1)),
            "lobby",
            List.of("lobby")),
        ForwardingMode.NONE,
        new byte[0],
        NioSocketChannel.class);
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new AssertionError("RSA is a mandatory JCA algorithm", e);
    }
  }

  /**
   * The proxy's commands for a test, running on the calling thread, with {@code /server} over
   * {@code servers}.
   *
   * @param servers the servers {@code /server} knows
   * @return the command manager
   */
  static WarpCommandManager commands(ServerRegistry servers) {
    WarpCommandManager commands = new WarpCommandManager(Runnable::run);
    ProxyCommands.register(commands, servers);
    return commands;
  }
}
