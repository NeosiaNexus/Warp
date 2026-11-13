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

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelOption;

/**
 * The socket options of a connection that may carry a player, set when its login starts rather than
 * when it is accepted.
 *
 * <p>Each option is a system call, two for the DSCP marking of a dual-stack socket. A connection
 * that only asks for the status, or says nothing at all, is closed within seconds and needs none of
 * them; under a connection flood they were four of the five {@code setsockopt} calls made for every
 * accepted socket. The remaining one is Netty's own {@code TCP_NODELAY}, on by default.
 */
final class PlayerSocketOptions {

  /**
   * DSCP value for low-latency traffic: sets the IP Type of Service field to prioritise interactive
   * game packets over bulk transfers, for routers that honour it.
   */
  static final int IP_TOS_LOW_LATENCY = 0x18;

  private PlayerSocketOptions() {}

  /**
   * Sets the options on {@code channel}: no Nagle delay (critical for game traffic), TCP keep-alive
   * (dead peers are detected at the TCP level) and the low-latency DSCP marking. A transport
   * ignores the options it does not have, and a socket that is already closing keeps whatever it
   * had.
   *
   * @param channel the client's channel, on its event loop
   */
  static void apply(Channel channel) {
    ChannelConfig config = channel.config();
    try {
      var _ = config.setOption(ChannelOption.TCP_NODELAY, true);
      var _ = config.setOption(ChannelOption.SO_KEEPALIVE, true);
      var _ = config.setOption(ChannelOption.IP_TOS, IP_TOS_LOW_LATENCY);
    } catch (ChannelException _) {
      // The socket closed under us: the close is handled where it is seen, not here.
    }
  }
}
