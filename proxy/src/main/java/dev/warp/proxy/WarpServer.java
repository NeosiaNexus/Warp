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
package dev.warp.proxy;

import dev.warp.api.Warp;
import dev.warp.api.WarpProvider;

import java.net.InetSocketAddress;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Concrete proxy implementation that owns the full server lifecycle.
 *
 * <p>Implements the public {@link Warp} API so that {@link WarpProvider#get()} returns this
 * instance once the server has started.
 */
public final class WarpServer implements Warp {

  private static final Logger logger = LoggerFactory.getLogger(WarpServer.class);

  private final InetSocketAddress bindAddress;

  /** Netty boss group — accepts incoming connections. */
  private @Nullable MultiThreadIoEventLoopGroup bossGroup;

  /** Netty worker group — handles I/O on accepted connections. */
  private @Nullable MultiThreadIoEventLoopGroup workerGroup;

  private volatile boolean running;

  /** Creates a server that will bind to {@code 0.0.0.0:25577}. */
  public WarpServer() {
    this(new InetSocketAddress("0.0.0.0", 25577));
  }

  /**
   * Creates a server that will bind to the given address.
   *
   * @param bindAddress the address and port to listen on
   */
  public WarpServer(InetSocketAddress bindAddress) {
    this.bindAddress = bindAddress;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /** Initialises Netty, registers the shutdown hook, and publishes this instance via the API. */
  public void start() {
    if (running) {
      throw new IllegalStateException("Server is already running.");
    }

    logger.info(
        "Starting Warp {} (commit: {}, branch: {})",
        WarpBuildInfo.VERSION,
        WarpBuildInfo.GIT_COMMIT,
        WarpBuildInfo.GIT_BRANCH);

    IoHandlerFactory ioFactory = detectIoHandlerFactory();
    this.bossGroup = new MultiThreadIoEventLoopGroup(1, ioFactory);
    this.workerGroup = new MultiThreadIoEventLoopGroup(ioFactory);

    Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "warp-shutdown"));

    // Publish ourselves as the API singleton.
    registerProvider();

    this.running = true;
    logger.info("Warp is listening on {}", bindAddress);
  }

  /** Gracefully shuts down Netty event loops. */
  public void stop() {
    if (!running) {
      return;
    }
    running = false;
    logger.info("Shutting down Warp...");

    if (bossGroup != null) {
      bossGroup.shutdownGracefully().awaitUninterruptibly();
    }
    if (workerGroup != null) {
      workerGroup.shutdownGracefully().awaitUninterruptibly();
    }

    logger.info("Warp has been stopped.");
  }

  // ---------------------------------------------------------------------------
  // Warp API
  // ---------------------------------------------------------------------------

  @Override
  public String version() {
    return WarpBuildInfo.VERSION;
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /**
   * Detects the best available Netty I/O transport for the current platform.
   *
   * <p>Tries epoll (Linux), then kqueue (macOS), then falls back to NIO. Detection uses reflection
   * so there is no hard compile-time dependency on platform-specific modules.
   */
  private static IoHandlerFactory detectIoHandlerFactory() {
    try {
      Class<?> epoll = Class.forName("io.netty.channel.epoll.Epoll");
      if ((boolean) epoll.getMethod("isAvailable").invoke(null)) {
        logger.info("Using epoll native transport");
        return (IoHandlerFactory)
            Class.forName("io.netty.channel.epoll.EpollIoHandler")
                .getMethod("newFactory")
                .invoke(null);
      }
    } catch (ReflectiveOperationException | UnsatisfiedLinkError e) {
      logger.trace("Epoll not available", e);
    }

    try {
      Class<?> kqueue = Class.forName("io.netty.channel.kqueue.KQueue");
      if ((boolean) kqueue.getMethod("isAvailable").invoke(null)) {
        logger.info("Using kqueue native transport");
        return (IoHandlerFactory)
            Class.forName("io.netty.channel.kqueue.KQueueIoHandler")
                .getMethod("newFactory")
                .invoke(null);
      }
    } catch (ReflectiveOperationException | UnsatisfiedLinkError e) {
      logger.trace("KQueue not available", e);
    }

    logger.info("Using NIO transport (native transport unavailable)");
    return NioIoHandler.newFactory();
  }

  /** Registers this server as the {@link WarpProvider} singleton. */
  private void registerProvider() {
    WarpProvider.set(this);
  }
}
