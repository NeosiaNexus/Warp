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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the PROXY protocol header a trusted load balancer sends at the start of a connection: the
 * client address it carries becomes the connection's {@link MinecraftConnection#remoteAddress()}.
 *
 * <p>Sits right after the {@link ProxyProtocolDecoder}, ahead of every Minecraft handler. The
 * header is mandatory: a connection that does not deliver a valid one within {@link
 * #HEADER_TIMEOUT} is closed, and nothing it sent reaches the Minecraft pipeline. Once the header
 * is applied, this handler and the decoder remove themselves, so the packets that follow pay
 * nothing for the PROXY protocol.
 *
 * <ul>
 *   <li><b>TCP over IPv4 or IPv6</b>: the source address and port become the client's.
 *   <li><b>v2 {@code LOCAL}</b>: a connection the load balancer opened itself, such as a health
 *       check; it keeps the socket's peer address.
 *   <li><b>v1 {@code UNKNOWN}, v2 {@code UNSPEC}</b>: the load balancer could not tell the client's
 *       address; as the specification requires, the connection is accepted with the socket's peer.
 *   <li><b>UDP and UNIX sockets</b>: not a Minecraft connection; refused.
 * </ul>
 *
 * <p>Refusals are logged at WARN, at most one line per minute and per kind with the count of the
 * others, as {@link ServerChannelInitializer} shares one {@link LogThrottle} between connections.
 */
final class ProxyProtocolHandler extends ChannelInboundHandlerAdapter {

  private static final Logger logger = LoggerFactory.getLogger(ProxyProtocolHandler.class);

  /**
   * How long a trusted proxy has to deliver the header. Load balancers send it as soon as the
   * connection opens, or with the client's first bytes, which a Minecraft client sends at once.
   */
  static final Duration HEADER_TIMEOUT = Duration.ofSeconds(5);

  private final MinecraftConnection connection;
  private final LogThrottle warnings;

  private @Nullable ScheduledFuture<?> timeout;

  /** Whether the connection was refused: anything still arriving is dropped. */
  private boolean refused;

  /**
   * Creates the handler of one connection.
   *
   * @param connection the connection whose address the header sets
   * @param warnings the throttle of refusal warnings, shared by every connection
   */
  ProxyProtocolHandler(MinecraftConnection connection, LogThrottle warnings) {
    this.connection = connection;
    this.warnings = warnings;
  }

  // ---------------------------------------------------------------------------
  // Netty inbound handler
  // ---------------------------------------------------------------------------

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    timeout =
        ctx.executor()
            .schedule(
                () ->
                    refuse(
                        ctx,
                        "it sent no complete PROXY protocol header within "
                            + HEADER_TIMEOUT.toSeconds()
                            + " s"),
                HEADER_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS);
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) {
    cancelTimeout();
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (!refused && msg instanceof HAProxyMessage header) {
      try {
        apply(ctx, header);
      } finally {
        header.release();
      }
      return;
    }
    // Nothing else may get past before the header: the decoder holds the bytes until it has one,
    // and what follows a refused header must not reach the Minecraft pipeline.
    ReferenceCountUtil.release(msg);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    cancelTimeout();
    if (!refused) {
      logger.debug("{} closed the connection before its PROXY protocol header", ctx.channel());
    }
    ctx.fireChannelInactive();
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    if (refused) {
      return; // Closing already; the problem was logged.
    }
    if (cause instanceof DecoderException) {
      // Thrown by the decoder: the header is malformed (HAProxyProtocolException), or the
      // connection does not start with one.
      refuse(ctx, "invalid PROXY protocol header: " + cause.getMessage());
      return;
    }
    ctx.fireExceptionCaught(cause);
  }

  // ---------------------------------------------------------------------------
  // Header
  // ---------------------------------------------------------------------------

  private void apply(ChannelHandlerContext ctx, HAProxyMessage header) {
    if (header.command() == HAProxyCommand.LOCAL) {
      logger.debug("{}: PROXY protocol LOCAL connection (health check)", ctx.channel());
    } else {
      switch (header.proxiedProtocol()) {
        case TCP4, TCP6 -> {
          InetSocketAddress client = clientAddress(header);
          if (client == null) {
            refuse(ctx, "invalid source address in its PROXY protocol header");
            return;
          }
          connection.setProxiedAddress(client);
          logger.debug("{}: client {} (PROXY protocol)", ctx.channel(), client);
        }
        case UNKNOWN ->
            logger.debug(
                "{}: PROXY protocol header without the client's address, keeping the proxy's",
                ctx.channel());
        case UDP4, UDP6, UNIX_STREAM, UNIX_DGRAM -> {
          refuse(
              ctx,
              "its PROXY protocol header is for a "
                  + header.proxiedProtocol()
                  + " connection, and Warp accepts TCP over IPv4 or IPv6 only");
          return;
        }
      }
    }
    // Done: the decoder removes itself after this read and hands what followed the header to the
    // Minecraft pipeline.
    cancelTimeout();
    ctx.pipeline().remove(this);
  }

  /**
   * Returns the client's address from a TCP header, or {@code null} if it is not an IP literal.
   * Netty has validated it; this never resolves a name.
   */
  private static @Nullable InetSocketAddress clientAddress(HAProxyMessage header) {
    byte @Nullable [] bytes = NetUtil.createByteArrayFromIpAddressString(header.sourceAddress());
    if (bytes == null) {
      return null;
    }
    try {
      // An IPv4-mapped IPv6 source becomes an Inet4Address, so that a client has one address.
      return new InetSocketAddress(InetAddress.getByAddress(bytes), header.sourcePort());
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private void refuse(ChannelHandlerContext ctx, String problem) {
    if (refused) {
      return;
    }
    refused = true;
    cancelTimeout();
    long suppressed = warnings.tryAcquire();
    if (suppressed >= 0) {
      logger.warn(
          "Refused a connection from trusted proxy {}: {}{}",
          ctx.channel().remoteAddress(),
          problem,
          LogThrottle.suffix(suppressed, "connections refused over their PROXY protocol header"));
    }
    var _ = ctx.close();
  }

  private void cancelTimeout() {
    ScheduledFuture<?> pending = timeout;
    if (pending != null) {
      pending.cancel(false);
      timeout = null;
    }
  }
}
