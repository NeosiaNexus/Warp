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

import java.nio.charset.StandardCharsets;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * Answers HAProxy's agent check: on each connection, one line ({@code up ready}, {@code drain} or
 * {@code down}, see {@link ReadinessState}), then the connection closes. Whatever HAProxy sends
 * ({@code agent-send}) is ignored.
 */
final class AgentCheckHandler extends ChannelInboundHandlerAdapter {

  private final ProxyHealth health;
  private final boolean requireServer;

  AgentCheckHandler(ProxyHealth health, boolean requireServer) {
    this.health = health;
    this.requireServer = requireServer;
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    String reply = ReadinessState.of(health, requireServer).agentReply() + "\n";
    ctx.writeAndFlush(Unpooled.copiedBuffer(reply, StandardCharsets.US_ASCII))
        .addListener(ChannelFutureListener.CLOSE);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ReferenceCountUtil.release(msg);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    var _ = ctx.close();
  }
}
