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
package dev.warp.proxy.query;

import dev.warp.proxy.StartupException;
import dev.warp.proxy.config.QueryConfig;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The GS4 Query listener: one datagram channel on the proxy's worker event loops. */
public final class QueryServer {

  private static final Logger logger = LoggerFactory.getLogger(QueryServer.class);

  /** How long {@link #stop()} waits for the channel to close. */
  private static final int CLOSE_TIMEOUT_SECONDS = 5;

  private final QueryConfig config;
  private final EventLoopGroup group;
  private final Class<? extends DatagramChannel> channelClass;
  private final QueryHandler handler;

  private volatile @Nullable Channel channel;

  /**
   * Creates the listener.
   *
   * @param config the {@code query} section
   * @param group the event loops it runs on: the proxy's workers
   * @param channelClass the datagram channel of the transport in use (epoll, kqueue or NIO)
   * @param handler the handler
   */
  public QueryServer(
      QueryConfig config,
      EventLoopGroup group,
      Class<? extends DatagramChannel> channelClass,
      QueryHandler handler) {
    this.config = config;
    this.group = group;
    this.channelClass = channelClass;
    this.handler = handler;
  }

  /**
   * Binds the listener.
   *
   * @throws StartupException if the address cannot be bound
   */
  public void start() {
    InetSocketAddress bind = config.bind();
    Bootstrap bootstrap =
        new Bootstrap()
            .group(group)
            .channel(channelClass)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .handler(handler);
    try {
      this.channel = bootstrap.bind(bind).sync().channel();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while binding the query listener", e);
    } catch (Exception e) {
      throw StartupException.cannotBind(bind, "query.bind", e);
    }
    logger.info("Query listening on {}/udp", address(bind));
  }

  /** Closes the listener. */
  public void stop() {
    Channel open = channel;
    if (open != null) {
      open.close().awaitUninterruptibly(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      channel = null;
    }
  }

  /**
   * Returns the address the listener is bound to.
   *
   * @return the address, or {@code null} before {@link #start()}
   */
  public @Nullable InetSocketAddress localAddress() {
    Channel open = channel;
    return open == null ? null : (InetSocketAddress) open.localAddress();
  }

  private static String address(InetSocketAddress address) {
    String host = address.getHostString();
    return host.indexOf(':') >= 0
        ? "[" + host + "]:" + address.getPort()
        : host + ":" + address.getPort();
  }
}
