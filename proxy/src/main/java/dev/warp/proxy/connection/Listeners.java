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

import dev.warp.proxy.logging.LogCode;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The listening sockets of the port clients connect to.
 *
 * <p>Where the transport can share a port between sockets ({@code SO_REUSEPORT}, epoll on Linux),
 * every event loop listens on its own socket and keeps the connections it accepts: the kernel
 * spreads new connections over the sockets, and a connection is registered on the loop that
 * accepted it. Nothing is handed from an acceptor thread to a worker, so accepting costs no task,
 * no wake-up of a sleeping loop and no extra pass through {@code epoll_wait}: under a connection
 * flood that hand-over was the largest cost the proxy could avoid.
 *
 * <p>A second proxy started on the same port must still fail to bind. The first socket is therefore
 * bound <em>without</em> {@code SO_REUSEPORT}, which the kernel refuses while anything else holds
 * the port, whatever its options; the option is set on it only once it is bound, and the sockets of
 * the other loops then join it. Any other process binds without the option first, or at all, and
 * gets the usual "address already in use".
 *
 * <p>Elsewhere, or if the kernel refuses to share the port, one socket listens on a loop of its own
 * and hands the connections round the workers.
 */
public final class Listeners {

  private static final Logger logger = LoggerFactory.getLogger(Listeners.class);

  /**
   * The name of {@code SO_REUSEPORT} among Netty's options, declared by its Unix transports. Named
   * rather than referenced, like the transports themselves: no compile-time dependency on a
   * platform module.
   */
  private static final String SHARED_PORT_OPTION =
      "io.netty.channel.unix.UnixChannelOption#SO_REUSEPORT";

  private final List<Channel> channels;

  private Listeners(List<Channel> channels) {
    this.channels = List.copyOf(channels);
  }

  // ---------------------------------------------------------------------------
  // Binding
  // ---------------------------------------------------------------------------

  /**
   * Returns the option that lets several sockets of the epoll transport share a port and its
   * connections ({@code SO_REUSEPORT}), to pass to {@link #bind}.
   *
   * @return Netty's option of that name
   */
  public static ChannelOption<Boolean> sharedPortOption() {
    return ChannelOption.valueOf(SHARED_PORT_OPTION);
  }

  /**
   * Binds the port: one socket per worker loop when {@code reusePort} is given and the kernel
   * accepts it, one shared socket otherwise.
   *
   * @param address the address to listen on; port 0 lets the system choose, the same for every
   *     socket
   * @param workers the loops that serve the connections
   * @param acceptor gives the loop of the shared socket; asked only when the port is not shared
   * @param reusePort the option that lets several sockets share a port, or {@code null} when the
   *     transport has none
   * @param bootstraps builds a bootstrap from the group that accepts and the group that serves
   * @return the bound sockets
   * @throws InterruptedException if the thread is interrupted while binding; a port that cannot be
   *     bound, typically because it is already in use, fails with the transport's own exception
   */
  public static Listeners bind(
      SocketAddress address,
      EventLoopGroup workers,
      Supplier<? extends EventLoopGroup> acceptor,
      @Nullable ChannelOption<Boolean> reusePort,
      BiFunction<EventLoopGroup, EventLoopGroup, ServerBootstrap> bootstraps)
      throws InterruptedException {
    if (reusePort != null) {
      List<Channel> bound = new ArrayList<>();
      try {
        bindPerLoop(address, workers, reusePort, bootstraps, bound);
        return new Listeners(bound);
      } catch (PortNotSharedException e) {
        // Nothing listens while the shared socket takes over: the sockets close first.
        closeAll(bound);
        logger.warn(
            LogCode.LISTENER_NOT_SHARED.marker(),
            "The event loops cannot each listen on {}: {}. One socket accepts for all of them,"
                + " which costs more under a connection flood and nothing otherwise.",
            address,
            e.getMessage());
      } catch (Throwable e) {
        // The port is taken, or the thread was interrupted: nothing stays bound.
        closeAll(bound);
        throw e;
      }
    }
    Channel shared = bootstraps.apply(acceptor.get(), workers).bind(address).sync().channel();
    return new Listeners(List.of(shared));
  }

  /**
   * Binds one socket per loop into {@code bound}. A failure of the first bind is the caller's (the
   * port is taken); any later one means the port cannot be shared.
   */
  private static void bindPerLoop(
      SocketAddress address,
      EventLoopGroup workers,
      ChannelOption<Boolean> reusePort,
      BiFunction<EventLoopGroup, EventLoopGroup, ServerBootstrap> bootstraps,
      List<Channel> bound)
      throws InterruptedException, PortNotSharedException {
    SocketAddress shared = address;
    for (EventExecutor executor : workers) {
      EventLoopGroup loop = (EventLoopGroup) executor;
      ServerBootstrap bootstrap = bootstraps.apply(loop, loop);
      if (bound.isEmpty()) {
        // Without the option: the bind fails if anything at all holds the port.
        Channel first = bootstrap.bind(address).sync().channel();
        bound.add(first);
        if (!first.config().setOption(reusePort, true)) {
          throw new PortNotSharedException("the transport does not know the option " + reusePort);
        }
        // With port 0 the others take the port the system gave the first.
        shared = first.localAddress();
      } else {
        ChannelFuture joined =
            bootstrap.option(reusePort, true).bind(shared).awaitUninterruptibly();
        if (!joined.isSuccess()) {
          throw new PortNotSharedException(String.valueOf(joined.cause().getMessage()));
        }
        bound.add(joined.channel());
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Accessors and closing
  // ---------------------------------------------------------------------------

  /**
   * Returns the address the port is bound to, with the port the system chose if it was asked to.
   *
   * @return the local address shared by every socket
   */
  public SocketAddress localAddress() {
    return channels.getFirst().localAddress();
  }

  /**
   * Returns the listening sockets: one per worker loop, or a single shared one.
   *
   * @return the sockets, never empty
   */
  public List<Channel> channels() {
    return channels;
  }

  /**
   * Stops listening, and waits for it at most {@code timeout}: new connections are refused by the
   * system from then on, the ones already accepted are untouched.
   *
   * @param timeout how long to wait for each socket to close
   * @param unit the unit of {@code timeout}
   */
  public void close(long timeout, TimeUnit unit) {
    List<ChannelFuture> closing = new ArrayList<>(channels.size());
    for (Channel channel : channels) {
      closing.add(channel.close());
    }
    for (ChannelFuture future : closing) {
      var _ = future.awaitUninterruptibly(timeout, unit);
    }
  }

  private static void closeAll(List<Channel> channels) {
    for (Channel channel : channels) {
      var _ = channel.close().awaitUninterruptibly();
    }
  }

  /** The port is bound, but the kernel or the transport does not let more sockets share it. */
  private static final class PortNotSharedException extends Exception {

    private static final long serialVersionUID = 1L;

    private PortNotSharedException(String message) {
      super(message, null, false, false);
    }
  }
}
