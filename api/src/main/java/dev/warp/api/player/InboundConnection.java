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

import dev.warp.api.server.Route;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * A client connection that has not become a player yet: it is logging in, or asking for the server
 * list entry.
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
   * Returns the host name the client typed to connect, cleaned: lowercase, without a trailing dot,
   * without a {@code :port}, and without the suffixes some platforms append (Forge's {@code
   * \0FML\0}, {@code \0FML2\0} and {@code \0FML3\0} markers, a BungeeCord forwarding payload). The
   * one the {@code routes} of {@code warp.conf} are matched against.
   *
   * @return the host name, or empty if the client sent none
   */
  Optional<String> virtualHost();

  /**
   * Returns the route the connection matched, if the {@code routes} of {@code warp.conf} have an
   * entry for its {@link #virtualHost() virtual host}. Known from the handshake on, so from the
   * first login event.
   *
   * @return the route, or empty when no entry matches and the connection goes to the default
   */
  Optional<Route> route();

  /**
   * Returns the protocol version the client speaks, as its handshake announced it: during a server
   * list ping, it may be one the proxy does not support.
   *
   * @return the protocol version number, such as 769 for Minecraft 1.21.4; {@code -1} for the
   *     legacy server list ping of Minecraft 1.6 and older, which predates these numbers
   */
  int protocolVersion();

  /**
   * Returns what kind of client this is, as the markers of its handshake declared: a Forge client
   * of one of its three eras, otherwise {@link ClientPlatform#JAVA} until a later phase says more
   * (NeoForge is recognized in the configuration phase, Bedrock from Floodgate's data). A platform
   * is a claim, not a proof: anything can send a Forge marker.
   *
   * @return the platform, {@link ClientPlatform#JAVA} when nothing marks the client
   */
  ClientPlatform platform();

  /**
   * Returns whether the client is still connected.
   *
   * @return {@code true} while connected
   */
  boolean isActive();

  /**
   * Returns whether the client arrived by a transfer: a server sent it here with a Transfer packet,
   * and its handshake said so (Minecraft 1.20.5 and newer). Such a client may carry cookies the
   * server that sent it stored; a client can also forge them.
   *
   * @return {@code true} if the client was transferred here
   */
  boolean isTransferred();

  /**
   * Returns whether another Warp proxy of the network handed the client off: it arrived by a
   * transfer with a handoff ticket that proxy sealed, which this proxy checked ({@code
   * transfers.handoff}). On an online-mode login, the player's identity then comes from the ticket,
   * as that proxy authenticated it, rather than from a new authentication with Mojang; the same
   * events fire as for any login. Known from {@code PreLoginEvent} on; a login whose handoff turns
   * out not to be the client's own is refused before {@code GameProfileRequestEvent}.
   *
   * @return {@code true} if the client was handed off; always {@code false} when the handoff is off
   */
  boolean isHandedOff();
}
