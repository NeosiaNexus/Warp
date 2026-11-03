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
package dev.warp.proxy.management;

import dev.warp.proxy.config.ManagementConfig;
import dev.warp.proxy.management.console.RemoteConsole;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The management server: the HTTP endpoints of {@link ManagementHttpHandler} and the HAProxy agent
 * check of {@link AgentCheckHandler}, both on one event loop of their own ({@code
 * warp-management}), a daemon thread no player connection and no plugin ever runs on. A proxy whose
 * game event loops or plugins are saturated still answers its probes, and a slow or hostile client
 * of the management port holds no thread.
 *
 * <p>Started before the plugins load, so that probes get an answer (503) while the proxy starts,
 * and stopped last, so that HAProxy's agent and the liveness probe answer until the very end.
 */
public final class ManagementServer {

  private static final Logger logger = LoggerFactory.getLogger(ManagementServer.class);

  /** Largest request line plus headers, and largest body: probes and POST /drain need little. */
  private static final int MAX_LINE = 4096;

  private static final int MAX_HEADERS = 8192;
  private static final int MAX_BODY = 4096;

  /** How long a client may take to send its request. */
  private static final int READ_TIMEOUT_SECONDS = 10;

  private final ManagementConfig config;
  private final ProxyHealth health;
  private final byte @Nullable [] tokenDigest;
  private final @Nullable RemoteConsole console;
  private final @Nullable TlsFiles tls;

  private @Nullable MultiThreadIoEventLoopGroup group;
  private @Nullable Channel http;
  private @Nullable Channel agent;

  /**
   * Creates the server, not started.
   *
   * @param config the {@code management} section
   * @param health the proxy
   */
  public ManagementServer(ManagementConfig config, ProxyHealth health) {
    this(config, health, null);
  }

  /**
   * Creates the server with the remote console, not started.
   *
   * @param config the {@code management} section
   * @param health the proxy
   * @param console the remote console, answering under {@code /console}, or {@code null}
   */
  public ManagementServer(
      ManagementConfig config, ProxyHealth health, @Nullable RemoteConsole console) {
    this.config = config;
    this.health = health;
    this.console = console;
    String token = config.token();
    this.tokenDigest = token == null ? null : ManagementHttpHandler.sha256(token);
    ManagementConfig.Tls files = config.tls();
    this.tls = files == null ? null : new TlsFiles(files.certificate(), files.key());
  }

  /**
   * Binds the HTTP endpoints and the agent check.
   *
   * @throws IllegalStateException if an address cannot be bound; nothing is left running then
   */
  public void start() {
    MultiThreadIoEventLoopGroup loop =
        new MultiThreadIoEventLoopGroup(
            1, new DefaultThreadFactory("warp-management", true), NioIoHandler.newFactory());
    this.group = loop;
    boolean requireServer = config.readyRequiresServer();
    RemoteConsole remote = console;
    TlsFiles certificates = tls;
    try {
      if (certificates != null) {
        certificates.load();
        var _ =
            loop.scheduleAtFixedRate(
                () -> Thread.ofVirtual().name("warp-management-tls").start(certificates::reload),
                TlsFiles.RELOAD_SECONDS,
                TlsFiles.RELOAD_SECONDS,
                TimeUnit.SECONDS);
      }
      this.http =
          new ServerBootstrap()
              .group(loop)
              .channel(NioServerSocketChannel.class)
              .childHandler(
                  new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                      if (certificates != null) {
                        channel.pipeline().addLast(certificates.newHandler(channel.alloc()));
                      }
                      channel
                          .pipeline()
                          .addLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                          .addLast(new HttpServerCodec(MAX_LINE, MAX_HEADERS, MAX_BODY))
                          .addLast(new HttpObjectAggregator(MAX_BODY))
                          .addLast(
                              new ManagementHttpHandler(
                                  health, requireServer, tokenDigest, remote));
                    }
                  })
              .bind(config.bind())
              .sync()
              .channel();
      InetSocketAddress agentAddress = config.agentCheck();
      if (agentAddress != null) {
        this.agent =
            new ServerBootstrap()
                .group(loop)
                .channel(NioServerSocketChannel.class)
                .childHandler(
                    new ChannelInitializer<SocketChannel>() {
                      @Override
                      protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(new AgentCheckHandler(health, requireServer));
                      }
                    })
                .bind(agentAddress)
                .sync()
                .channel();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      stop();
      throw new IllegalStateException("Interrupted while binding the management server", e);
    } catch (Exception e) {
      stop();
      throw new IllegalStateException(
          "Cannot start the management server on "
              + config.bind()
              + (config.agentCheck() == null ? "" : " and " + config.agentCheck())
              + ": "
              + e.getMessage(),
          e);
    }
    InetSocketAddress agentBound = agentCheckAddress();
    logger.info(
        "Management server on {}://{} (/livez, /startupz, /readyz, /drain){}{}",
        certificates == null ? "http" : "https",
        format(httpAddress()),
        agentBound == null ? "" : ", HAProxy agent check on " + format(agentBound),
        tokenDigest == null ? "; no token: POST /drain refused" : "");
    if (remote != null) {
      logger.info(
          "Remote console on {}://{}/console: {}",
          certificates == null ? "http" : "https",
          format(httpAddress()),
          remote.describe(certificates != null));
    }
  }

  /**
   * Returns the address the HTTP endpoints listen on, the port chosen when configured as 0.
   *
   * @return the bound address
   * @throws IllegalStateException if the server is not started
   */
  public InetSocketAddress httpAddress() {
    Channel channel = http;
    if (channel == null) {
      throw new IllegalStateException("The management server is not started");
    }
    return (InetSocketAddress) channel.localAddress();
  }

  /**
   * Returns the address the agent check listens on.
   *
   * @return the bound address, or {@code null} without an agent check
   */
  public @Nullable InetSocketAddress agentCheckAddress() {
    Channel channel = agent;
    return channel == null ? null : (InetSocketAddress) channel.localAddress();
  }

  /** Closes both ports and stops the event loop, letting answers in flight go out. Idempotent. */
  public void stop() {
    Channel channel = http;
    if (channel != null) {
      channel.close().awaitUninterruptibly(1, TimeUnit.SECONDS);
      http = null;
    }
    channel = agent;
    if (channel != null) {
      channel.close().awaitUninterruptibly(1, TimeUnit.SECONDS);
      agent = null;
    }
    MultiThreadIoEventLoopGroup loop = group;
    if (loop != null) {
      loop.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(3, TimeUnit.SECONDS);
      group = null;
    }
  }

  private static String format(InetSocketAddress address) {
    return address.getHostString() + ":" + address.getPort();
  }
}
