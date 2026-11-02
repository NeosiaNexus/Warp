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

import dev.warp.api.player.InboundConnection;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * A client connection logging in or asking for the server list entry, as plugins see it before it
 * becomes a player.
 */
final class WarpInboundConnection implements InboundConnection {

  /** {@link #protocolVersion()} of a legacy server list ping, which predates protocol numbers. */
  static final int LEGACY_PROTOCOL = -1;

  /** Marks a connection whose protocol is the one its decoder runs. */
  private static final int DECODER_PROTOCOL = Integer.MIN_VALUE;

  private final MinecraftConnection connection;
  private final int protocol;

  /**
   * Creates the view of a connection logging in: its protocol is the one Warp decodes it with.
   *
   * @param connection the connection
   */
  WarpInboundConnection(MinecraftConnection connection) {
    this(connection, DECODER_PROTOCOL);
  }

  /**
   * Creates the view of a connection asking for the server list entry, whose protocol may be one
   * Warp does not support.
   *
   * @param connection the connection
   * @param protocol the protocol its handshake announced, or {@link #LEGACY_PROTOCOL}
   */
  WarpInboundConnection(MinecraftConnection connection, int protocol) {
    this.connection = connection;
    this.protocol = protocol;
  }

  @Override
  public InetSocketAddress remoteAddress() {
    return (InetSocketAddress) connection.channel().remoteAddress();
  }

  @Override
  public Optional<String> virtualHost() {
    return Optional.ofNullable(connection.virtualHost());
  }

  @Override
  public int protocolVersion() {
    return protocol == DECODER_PROTOCOL ? connection.decoder().version().protocol() : protocol;
  }

  @Override
  public boolean isActive() {
    return connection.channel().isActive();
  }

  @Override
  public String toString() {
    return "InboundConnection[" + remoteAddress() + "]";
  }
}
