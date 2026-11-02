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

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolException;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;

/**
 * Netty's PROXY protocol decoder, made to refuse a connection from its first byte that cannot begin
 * a header.
 *
 * <p>{@link HAProxyMessageDecoder} decodes v1 and v2 headers and removes itself once it has one,
 * handing the bytes that followed to the next handler. But it takes anything that is not a v2
 * signature for the start of a v1 line and waits for its end: a Minecraft client that a
 * misconfigured load balancer lets through without a header would sit there until the header
 * timeout. This decoder checks each byte against the v1 ({@code "PROXY "}) or v2 signature as it
 * arrives, so the usual mistake, a load balancer that does not send the header, is reported at once
 * and for what it is.
 *
 * <p>Failures reach the next handler as a {@link HAProxyProtocolException}, the decoder's own ones
 * included; it is that handler's job to log them and close the connection.
 */
final class ProxyProtocolDecoder extends HAProxyMessageDecoder {

  /** The start of every v1 header, {@code "PROXY UNKNOWN"} included. */
  private static final byte[] V1_SIGNATURE = "PROXY ".getBytes(StandardCharsets.US_ASCII);

  /** The 12-byte signature that starts every v2 header. */
  private static final byte[] V2_SIGNATURE = {
    0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
  };

  /** The signature the first byte chose, or {@code null} before any byte. */
  private byte @Nullable [] signature;

  /** How many bytes of {@link #signature} have been checked. */
  private int checked;

  /** Whether the connection was found not to start with a header: everything else is dropped. */
  private boolean refused;

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (refused) {
      ReferenceCountUtil.release(msg);
      return;
    }
    if (msg instanceof ByteBuf bytes && !startsLikeHeader(bytes)) {
      refused = true;
      bytes.release();
      ctx.fireExceptionCaught(
          new HAProxyProtocolException(
              "the connection does not start with a PROXY protocol header"));
      return;
    }
    super.channelRead(ctx, msg);
  }

  /**
   * Checks the bytes that extend the signature checked so far.
   *
   * @return {@code false} as soon as a byte departs from both signatures
   */
  private boolean startsLikeHeader(ByteBuf bytes) {
    byte[] expected = signature;
    if (expected != null && checked == expected.length) {
      return true;
    }
    int index = bytes.readerIndex();
    int end = bytes.writerIndex();
    while (index < end) {
      byte value = bytes.getByte(index++);
      if (expected == null) {
        expected = value == V1_SIGNATURE[0] ? V1_SIGNATURE : V2_SIGNATURE;
        signature = expected;
      }
      if (value != expected[checked]) {
        return false;
      }
      if (++checked == expected.length) {
        return true;
      }
    }
    return true;
  }
}
