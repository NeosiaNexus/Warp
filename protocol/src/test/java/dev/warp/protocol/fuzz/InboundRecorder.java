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
package dev.warp.protocol.fuzz;

import java.util.ArrayList;
import java.util.List;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * The end of a decoding pipeline under test. It keeps every message that reaches it and, like the
 * proxy, closes the channel on the first exception, which it keeps too, with any that follow.
 */
public final class InboundRecorder extends ChannelInboundHandlerAdapter {

  private final List<Object> messages = new ArrayList<>();
  private final List<Throwable> failures = new ArrayList<>();

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    messages.add(msg);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    failures.add(cause);
    var _ = ctx.close();
  }

  /**
   * Returns the messages received, in order.
   *
   * @return the messages, still owned by this recorder
   */
  public List<Object> messages() {
    return messages;
  }

  /**
   * Returns the exceptions caught, in order.
   *
   * @return the exceptions; the channel was closed on the first
   */
  public List<Throwable> failures() {
    return failures;
  }

  /** Releases the messages received that hold a reference count. */
  public void release() {
    messages.forEach(ReferenceCountUtil::release);
  }
}
