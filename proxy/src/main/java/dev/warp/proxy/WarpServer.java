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
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.config.WarpConfig;
import dev.warp.proxy.connection.ServerChannelInitializer;
import dev.warp.proxy.connection.ServerLoginContext;
import dev.warp.proxy.server.ServerRegistry;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
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

  /**
   * Low water mark (bytes) for the write buffer. When the buffered amount drops below this, the
   * channel becomes writable again.
   */
  private static final int WRITE_BUFFER_LOW_WATER_MARK = 512 * 1024;

  /**
   * High water mark (bytes) for the write buffer. When the buffered amount exceeds this, the
   * channel becomes unwritable (back-pressure).
   */
  private static final int WRITE_BUFFER_HIGH_WATER_MARK = 2 * 1024 * 1024;

  /**
   * DSCP value for low-latency traffic. Sets the IP Type of Service field to prioritise interactive
   * game packets over bulk transfers.
   */
  private static final int IP_TOS_LOW_LATENCY = 0x18;

  /** RSA key size for Minecraft encryption handshake (matches vanilla). */
  private static final int RSA_KEY_SIZE = 1024;

  // ---------------------------------------------------------------------------
  // Instance fields
  // ---------------------------------------------------------------------------

  private final WarpConfig config;

  /** Netty boss group — accepts incoming connections. */
  private @Nullable MultiThreadIoEventLoopGroup bossGroup;

  /** Netty worker group — handles I/O on accepted connections. */
  private @Nullable MultiThreadIoEventLoopGroup workerGroup;

  /** The bound server channel, used for graceful shutdown. */
  private @Nullable Channel serverChannel;

  /** The detected transport info, needed for outbound connections. */
  private @Nullable TransportInfo transportInfo;

  /** The Mojang session service, kept for lifecycle management. */
  private @Nullable MojangSessionService sessionService;

  private volatile boolean running;

  /**
   * Creates a server with the given configuration.
   *
   * @param config the proxy configuration
   */
  public WarpServer(WarpConfig config) {
    this.config = config;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Initialises Netty, registers the shutdown hook, binds the server port, and publishes the API.
   */
  public void start() {
    if (running) {
      throw new IllegalStateException("Server is already running.");
    }

    logger.info(
        "Starting Warp {} (commit: {}, branch: {})",
        WarpBuildInfo.VERSION,
        WarpBuildInfo.GIT_COMMIT,
        WarpBuildInfo.GIT_BRANCH);

    TransportInfo transport = detectTransport();
    this.transportInfo = transport;
    this.bossGroup = new MultiThreadIoEventLoopGroup(1, transport.ioFactory());
    this.workerGroup = new MultiThreadIoEventLoopGroup(transport.ioFactory());

    ServerLoginContext loginContext = createLoginContext(transport);

    ServerBootstrap bootstrap =
        new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(transport.serverChannelClass())
            .childHandler(new ServerChannelInitializer(loginContext))
            // TCP_NODELAY: disable Nagle — critical for game traffic. BungeeCord misses this.
            .childOption(ChannelOption.TCP_NODELAY, true)
            // SO_KEEPALIVE: detect dead connections at the TCP level.
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            // IP_TOS: DSCP low-latency marking for routers that honour it.
            .childOption(ChannelOption.IP_TOS, IP_TOS_LOW_LATENCY)
            // Write buffer water marks for back-pressure (512 KB / 2 MB).
            .childOption(
                ChannelOption.WRITE_BUFFER_WATER_MARK,
                new WriteBufferWaterMark(WRITE_BUFFER_LOW_WATER_MARK, WRITE_BUFFER_HIGH_WATER_MARK))
            // Pooled direct allocator — avoids unpooled heap copies.
            .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            // SO_REUSEADDR: allow rebind immediately after restart.
            .option(ChannelOption.SO_REUSEADDR, true);

    // SO_REUSEPORT: distribute accept load across threads (epoll only).
    applySoReusePort(bootstrap, transport);

    try {
      this.serverChannel = bootstrap.bind(config.bind()).sync().channel();
    } catch (InterruptedException e) {
      shutdownEventLoops();
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while binding server", e);
    } catch (Exception e) {
      shutdownEventLoops();
      throw new RuntimeException("Failed to bind to " + config.bind(), e);
    }

    Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "warp-shutdown"));

    // Publish ourselves as the API singleton.
    registerProvider();

    this.running = true;
    logger.info("Warp is listening on {}", config.bind());
  }

  /** Gracefully shuts down the server channel and Netty event loops. */
  public void stop() {
    if (!running) {
      return;
    }
    running = false;
    logger.info("Shutting down Warp...");

    if (serverChannel != null) {
      serverChannel.close().awaitUninterruptibly();
    }
    shutdownEventLoops();

    if (sessionService != null) {
      sessionService.close();
    }

    logger.info("Warp has been stopped.");
  }

  /** Shuts down boss and worker event loops. Safe to call multiple times. */
  private void shutdownEventLoops() {
    if (bossGroup != null) {
      bossGroup.shutdownGracefully().awaitUninterruptibly();
    }
    if (workerGroup != null) {
      workerGroup.shutdownGracefully().awaitUninterruptibly();
    }
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
   * Returns the worker event loop group for creating outbound connections (backend).
   *
   * @return the worker group
   * @throws IllegalStateException if the server has not started
   */
  public MultiThreadIoEventLoopGroup workerGroup() {
    if (workerGroup == null) {
      throw new IllegalStateException("Server has not started");
    }
    return workerGroup;
  }

  /**
   * Returns the client socket channel class matching the detected transport.
   *
   * @return the channel class for outbound connections
   * @throws IllegalStateException if the server has not started
   */
  public Class<? extends SocketChannel> clientChannelClass() {
    if (transportInfo == null) {
      throw new IllegalStateException("Server has not started");
    }
    return transportInfo.clientChannelClass();
  }

  /**
   * Creates the server-wide login context with RSA keypair and configuration values.
   *
   * @param transport the detected transport for channel class resolution
   */
  private ServerLoginContext createLoginContext(TransportInfo transport) {
    KeyPairGenerator gen;
    try {
      gen = KeyPairGenerator.getInstance("RSA");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("RSA not available", e);
    }
    gen.initialize(RSA_KEY_SIZE);
    KeyPair rsaKeyPair = gen.generateKeyPair();

    MojangSessionService service = new MojangSessionService();
    this.sessionService = service;

    ServerRegistry serverRegistry =
        new ServerRegistry(config.servers(), config.defaultServer(), config.fallbackOrder());

    return new ServerLoginContext(
        rsaKeyPair,
        config.onlineMode(),
        config.compressionThreshold(),
        config.compressionLevel(),
        service,
        serverRegistry,
        config.forwardingMode(),
        config.forwardingSecret(),
        transport.clientChannelClass());
  }

  /**
   * Detects the best available Netty I/O transport for the current platform.
   *
   * <p>Tries epoll (Linux), then kqueue (macOS), then falls back to NIO. Detection uses reflection
   * so there is no hard compile-time dependency on platform-specific modules.
   */
  @SuppressWarnings("unchecked")
  private static TransportInfo detectTransport() {
    try {
      Class<?> epoll = Class.forName("io.netty.channel.epoll.Epoll");
      if ((boolean) epoll.getMethod("isAvailable").invoke(null)) {
        logger.info("Using epoll native transport");
        IoHandlerFactory factory =
            (IoHandlerFactory)
                Class.forName("io.netty.channel.epoll.EpollIoHandler")
                    .getMethod("newFactory")
                    .invoke(null);
        Class<? extends ServerSocketChannel> serverClass =
            (Class<? extends ServerSocketChannel>)
                Class.forName("io.netty.channel.epoll.EpollServerSocketChannel");
        Class<? extends SocketChannel> clientClass =
            (Class<? extends SocketChannel>)
                Class.forName("io.netty.channel.epoll.EpollSocketChannel");
        return new TransportInfo(factory, serverClass, clientClass);
      }
    } catch (ReflectiveOperationException | UnsatisfiedLinkError e) {
      logger.trace("Epoll not available", e);
    }

    try {
      Class<?> kqueue = Class.forName("io.netty.channel.kqueue.KQueue");
      if ((boolean) kqueue.getMethod("isAvailable").invoke(null)) {
        logger.info("Using kqueue native transport");
        IoHandlerFactory factory =
            (IoHandlerFactory)
                Class.forName("io.netty.channel.kqueue.KQueueIoHandler")
                    .getMethod("newFactory")
                    .invoke(null);
        Class<? extends ServerSocketChannel> serverClass =
            (Class<? extends ServerSocketChannel>)
                Class.forName("io.netty.channel.kqueue.KQueueServerSocketChannel");
        Class<? extends SocketChannel> clientClass =
            (Class<? extends SocketChannel>)
                Class.forName("io.netty.channel.kqueue.KQueueSocketChannel");
        return new TransportInfo(factory, serverClass, clientClass);
      }
    } catch (ReflectiveOperationException | UnsatisfiedLinkError e) {
      logger.trace("KQueue not available", e);
    }

    logger.info("Using NIO transport (native transport unavailable)");
    return new TransportInfo(
        NioIoHandler.newFactory(), NioServerSocketChannel.class, NioSocketChannel.class);
  }

  /**
   * Applies {@code SO_REUSEPORT} if the transport supports it (epoll only).
   *
   * <p>This distributes incoming connection accept load across multiple threads, improving accept
   * throughput on high-connection-rate servers. Only attempted when the detected transport is epoll
   * — avoids spurious Netty warnings when epoll classes are on the classpath but kqueue/NIO is the
   * active transport.
   */
  @SuppressWarnings("unchecked")
  private static void applySoReusePort(ServerBootstrap bootstrap, TransportInfo transport) {
    // Only attempt SO_REUSEPORT when the actual transport is epoll.
    String channelClassName = transport.serverChannelClass().getName();
    if (!channelClassName.contains("Epoll")) {
      return;
    }
    try {
      Class<?> epollOptionClass = Class.forName("io.netty.channel.epoll.EpollChannelOption");
      ChannelOption<Boolean> soReusePort =
          (ChannelOption<Boolean>) epollOptionClass.getField("SO_REUSEPORT").get(null);
      bootstrap.option(soReusePort, true);
      logger.debug("SO_REUSEPORT enabled");
    } catch (ReflectiveOperationException | UnsatisfiedLinkError e) {
      logger.trace("SO_REUSEPORT not available", e);
    }
  }

  /** Registers this server as the {@link WarpProvider} singleton. */
  private void registerProvider() {
    WarpProvider.set(this);
  }

  /**
   * Holds the detected I/O transport factory and corresponding channel classes.
   *
   * @param ioFactory the Netty I/O handler factory for event loops
   * @param serverChannelClass the server socket channel class matching the transport
   * @param clientChannelClass the client socket channel class matching the transport
   */
  private record TransportInfo(
      IoHandlerFactory ioFactory,
      Class<? extends ServerSocketChannel> serverChannelClass,
      Class<? extends SocketChannel> clientChannelClass) {}
}
