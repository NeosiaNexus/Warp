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
package dev.warp.proxy.network;

import dev.warp.api.network.PipelinePosition;

import java.util.List;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

/**
 * A connection whose pipeline takes plugin handlers. The connection knows its own layout; this
 * package only decides who may add what, and when.
 */
public interface PipelineHost {

  /**
   * Returns the connection's channel.
   *
   * @return the channel
   */
  Channel channel();

  /**
   * Adds a plugin handler at a position, after the plugin handlers already there. A handler at
   * {@link PipelinePosition#PACKET} switches the connection to packet level first.
   *
   * @param position the position
   * @param name the handler's full name, {@code plugin-id:name}
   * @param handler the handler
   * @throws IllegalArgumentException if the pipeline already has a handler of that name
   */
  void addPluginHandler(PipelinePosition position, String name, ChannelHandler handler);

  /**
   * Returns the names of the plugin handlers at a position, outermost first.
   *
   * @param position the position
   * @return the names, unmodifiable
   */
  List<String> pluginHandlers(PipelinePosition position);

  /**
   * Returns whether plugin handlers receive this connection's packets decompressed.
   *
   * @return {@code true} once a packet handler was added
   */
  boolean isPacketLevel();
}
