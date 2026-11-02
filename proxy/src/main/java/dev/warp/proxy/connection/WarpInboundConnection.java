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

/** A client connection logging in, as plugins see it before it becomes a player. */
final class WarpInboundConnection implements InboundConnection {

  private final MinecraftConnection connection;

  WarpInboundConnection(MinecraftConnection connection) {
    this.connection = connection;
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
    return connection.decoder().version().protocol();
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
