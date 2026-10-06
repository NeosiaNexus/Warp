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
import dev.warp.proxy.config.ForwardingMode;
import dev.warp.proxy.server.ServerRegistry;

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
    ServerRegistry registry =
        new ServerRegistry(
            Map.of("lobby", new InetSocketAddress(InetAddress.getLoopbackAddress(), 1)),
            "lobby",
            List.of("lobby"));
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
}
