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

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;

/**
 * Represents a connection to a backend Minecraft server.
 *
 * <p>Wraps a {@link MinecraftConnection} with backend-specific state: the target server address and
 * active/teardown tracking. Created via the static {@link #connect} factory which returns a {@link
 * CompletableFuture} that completes when the TCP connection is established.
 *
 * <p>The same {@link MinecraftConnection} class is reused for backend connections — it is
 * deliberately thin and direction-agnostic. The direction difference is handled entirely by the
 * decoder/encoder in the pipeline.
 */
public final class BackendConnection {

  /** Default connect timeout in milliseconds. */
  private static final int CONNECT_TIMEOUT_MS = 5_000;

  /** Write buffer water marks matching the server-side settings. */
  private static final WriteBufferWaterMark WRITE_BUFFER_WATER_MARK =
      new WriteBufferWaterMark(512 * 1024, 2 * 1024 * 1024);

  /** DSCP value for low-latency traffic. */
  private static final int IP_TOS_LOW_LATENCY = 0x18;

  // ---------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------

  private final MinecraftConnection connection;
  private final InetSocketAddress serverAddress;
  private volatile boolean active = true;

  // ---------------------------------------------------------------------------
  // Constructor
  // ---------------------------------------------------------------------------

  private BackendConnection(MinecraftConnection connection, InetSocketAddress serverAddress) {
    this.connection = connection;
    this.serverAddress = serverAddress;
  }

  // ---------------------------------------------------------------------------
  // Factory
  // ---------------------------------------------------------------------------

  /**
   * Connects to a backend server asynchronously.
   *
   * <p>The backend channel is registered on the <b>player's client event loop</b>, so both legs of
   * a player's traffic are served by one thread. Forwarding a packet is then a plain method call
   * into the other channel's pipeline: no cross-thread write task, no MPSC queue hand-off, no
   * wakeup, and pooled buffers are released on the thread that allocated them. Velocity binds its
   * backend connections the same way.
   *
   * <p>The returned future completes when the TCP connection is established (not when the login
   * sequence finishes — that is handled by session handlers).
   *
   * @param channelClass the socket channel class matching the transport
   * @param player the connected player (used to initialise the backend pipeline)
   * @param serverAddress the backend server address
   * @param forwardingSecret the shared HMAC secret for Velocity modern forwarding
   * @return a future that completes with the backend connection
   */
  public static CompletableFuture<BackendConnection> connect(
      Class<? extends Channel> channelClass,
      ConnectedPlayer player,
      InetSocketAddress serverAddress,
      byte[] forwardingSecret) {

    CompletableFuture<BackendConnection> future = new CompletableFuture<>();

    new Bootstrap()
        .group(player.clientConnection().channel().eventLoop())
        .channel(channelClass)
        .handler(new BackendChannelInitializer(player))
        .option(ChannelOption.TCP_NODELAY, true)
        .option(ChannelOption.SO_KEEPALIVE, true)
        .option(ChannelOption.IP_TOS, IP_TOS_LOW_LATENCY)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
        .option(ChannelOption.WRITE_BUFFER_WATER_MARK, WRITE_BUFFER_WATER_MARK)
        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .connect(serverAddress)
        .addListener(
            (ChannelFutureListener)
                channelFuture -> {
                  if (channelFuture.isSuccess()) {
                    Channel ch = channelFuture.channel();
                    MinecraftConnection conn = ch.pipeline().get(MinecraftConnection.class);
                    // Set session handler AFTER TCP connect succeeds — activated()
                    // sends Handshake + LoginStart, which requires an active channel.
                    conn.setSessionHandler(
                        new BackendLoginSessionHandler(
                            player, conn, serverAddress, forwardingSecret));
                    future.complete(new BackendConnection(conn, serverAddress));
                  } else {
                    future.completeExceptionally(channelFuture.cause());
                  }
                });

    return future;
  }

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  /**
   * Returns the underlying Minecraft connection.
   *
   * @return the connection
   */
  public MinecraftConnection connection() {
    return connection;
  }

  /**
   * Returns the backend server address.
   *
   * @return the server address
   */
  public InetSocketAddress serverAddress() {
    return serverAddress;
  }

  /**
   * Returns whether this backend connection is still active.
   *
   * @return {@code true} if active
   */
  public boolean isActive() {
    return active && connection.channel().isActive();
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /** Marks this connection as inactive and closes the channel. */
  public void disconnect() {
    active = false;
    connection.close();
  }
}
