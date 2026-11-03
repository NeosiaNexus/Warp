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
package dev.warp.api.network;

import java.net.InetSocketAddress;
import java.util.List;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

/**
 * The pipeline of one connection, as an initializer sees it while the connection is being set up.
 *
 * <p>Only valid during the {@link PipelineInitializer#initialize} call it was given to, on the
 * connection's event loop: the handlers it adds stay for the life of the connection.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface ConnectionPipeline {

  /**
   * Returns which peer this connection leads to.
   *
   * @return {@link ConnectionSide#CLIENT} or {@link ConnectionSide#BACKEND}
   */
  ConnectionSide side();

  /**
   * Returns the protocol version the proxy speaks on this connection: the client's version, on both
   * sides. A translator that makes a backend of another version understand it rewrites the
   * handshake and translates every packet, at {@link PipelinePosition#PACKET}.
   *
   * @return the protocol version number, such as 769 for Minecraft 1.21.4
   */
  int protocolVersion();

  /**
   * Returns the address of the peer: the player's real address (after the PROXY protocol, when the
   * proxy uses it), or the backend server's.
   *
   * @return the address
   */
  InetSocketAddress remoteAddress();

  /**
   * Returns the connection's channel, for its event loop, its attributes, and closing it. A backend
   * channel is not connected yet when its initializers run.
   *
   * <p>The proxy's own handlers in {@code channel().pipeline()}, their names and their order are
   * not part of the API: add handlers with {@link #addHandler}, which places them. After each
   * initializer, the proxy checks that the handlers it found (the proxy's, and those of the plugins
   * initialized before) are all still there, in their order: one removed, replaced or moved closes
   * the connection, and the proxy reports the plugin and the handler.
   *
   * @return the channel
   */
  Channel channel();

  /**
   * Adds a handler at a position, after (further from the socket than) the handlers already there.
   *
   * <p>The handler's name in the pipeline is the plugin's identifier, a colon and {@code name},
   * such as {@code viaversion:decoder}: plugins never collide, and a pipeline dump names its owner.
   *
   * @param position where the handler goes, and what it sees there
   * @param name the handler's name, unique within the plugin for this connection
   * @param handler the handler; one instance per connection unless it is {@link
   *     ChannelHandler.Sharable}
   * @return the handler's name in the pipeline
   * @throws IllegalArgumentException if this plugin already added a handler of that name to this
   *     connection
   * @throws IllegalStateException if called after the initializer returned
   */
  String addHandler(PipelinePosition position, String name, ChannelHandler handler);

  /**
   * Returns the names of the plugin handlers at a position, outermost first: those added by every
   * plugin so far, this one included.
   *
   * @param position the position
   * @return the names, unmodifiable
   */
  List<String> handlers(PipelinePosition position);

  /**
   * Returns whether this connection hands its packets decompressed to {@link
   * PipelinePosition#PACKET} handlers, which disabled compression passthrough for it.
   *
   * @return {@code true} once a packet handler was added, by any plugin
   */
  boolean decompressesPackets();
}
