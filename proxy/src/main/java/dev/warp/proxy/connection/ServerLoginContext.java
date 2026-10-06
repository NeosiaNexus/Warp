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

import java.security.KeyPair;

import io.netty.channel.Channel;

/**
 * Server-wide configuration shared across all client connections.
 *
 * <p>Created once during server startup and injected into {@link ServerChannelInitializer}. Using a
 * record rather than injecting {@link dev.warp.proxy.WarpServer WarpServer} directly keeps session
 * handlers testable and avoids coupling to the server lifecycle.
 *
 * @param rsaKeyPair the RSA keypair for encryption handshake (generated once at server start)
 * @param onlineMode whether to authenticate players with Mojang
 * @param compressionThreshold the compression threshold in bytes, or {@code -1} to disable
 * @param compressionLevel the zlib compression level (0–9 or {@code -1} for default)
 * @param compressionPassthrough whether uninspected packets keep their original compressed form
 * @param sessionService the Mojang session service for online-mode authentication
 * @param profileKeys the checker of the profile public keys 1.19 to 1.19.2 clients send
 * @param serverRegistry the registry of backend servers
 * @param forwardingMode the player info forwarding mode
 * @param forwardingSecret the shared HMAC secret for Velocity modern forwarding
 * @param channelClass the socket channel class matching the detected transport
 */
@SuppressWarnings("ArrayRecordComponent") // forwardingSecret is treated as immutable
public record ServerLoginContext(
    KeyPair rsaKeyPair,
    boolean onlineMode,
    int compressionThreshold,
    int compressionLevel,
    boolean compressionPassthrough,
    MojangSessionService sessionService,
    ProfileKeys profileKeys,
    ServerRegistry serverRegistry,
    ForwardingMode forwardingMode,
    byte[] forwardingSecret,
    Class<? extends Channel> channelClass) {

  /** Validates configuration invariants. */
  public ServerLoginContext {
    if (compressionThreshold < -1) {
      throw new IllegalArgumentException(
          "compressionThreshold must be >= -1, got " + compressionThreshold);
    }
    if (compressionLevel < -1 || compressionLevel > 9) {
      throw new IllegalArgumentException(
          "compressionLevel must be -1 to 9, got " + compressionLevel);
    }
  }
}
