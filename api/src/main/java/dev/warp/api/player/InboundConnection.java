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
package dev.warp.api.player;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * A client connection that has not become a player yet: it is logging in.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface InboundConnection {

  /**
   * Returns the client's address: the real one when the proxy sits behind a load balancer that
   * speaks the PROXY protocol.
   *
   * @return the remote address
   */
  InetSocketAddress remoteAddress();

  /**
   * Returns the host name the client typed to connect, as sent in its handshake, without the
   * suffixes some platforms append (Forge's {@code \0FML\0}, trailing dots).
   *
   * @return the host name, or empty if the client sent none
   */
  Optional<String> virtualHost();

  /**
   * Returns the protocol version the client speaks.
   *
   * @return the protocol version number, such as 769 for Minecraft 1.21.4
   */
  int protocolVersion();

  /**
   * Returns whether the client is still connected.
   *
   * @return {@code true} while connected
   */
  boolean isActive();
}
