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
package dev.warp.protocol.netty;

import dev.warp.protocol.codec.VarInt;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Prepends a VarInt length prefix to outgoing Minecraft protocol frames.
 *
 * <p>Wire format: {@code [VarInt: Length][Payload]}. Uses the minimum VarInt encoding (1–3 bytes)
 * for optimal bandwidth. Payloads exceeding the 3-byte VarInt maximum ({@value
 * dev.warp.protocol.codec.VarInt#MAX_21_BIT} bytes) are rejected.
 *
 * <p>This handler is stateless and {@link ChannelHandler.Sharable} — a single instance may be used
 * across multiple channels. Use the {@link #INSTANCE} singleton to avoid redundant allocations.
 *
 * <p>When compression is enabled, this handler is <b>replaced</b> by {@link CompressionEncoder},
 * which combines compression and frame-length encoding in a single pass.
 */
@ChannelHandler.Sharable
public final class FrameEncoder extends MessageToByteEncoder<ByteBuf> {

  /** Shared singleton — safe because this handler is stateless. */
  public static final FrameEncoder INSTANCE = new FrameEncoder();

  private static final int MAX_FRAME_LENGTH = VarInt.MAX_21_BIT;

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final EncoderException OVERSIZED_FRAME =
      new EncoderException(
          "Frame payload exceeds maximum length of " + MAX_FRAME_LENGTH + " bytes") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  @Override
  protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
    int payloadLength = msg.readableBytes();
    if (payloadLength > MAX_FRAME_LENGTH) {
      throw OVERSIZED_FRAME;
    }
    // Pre-size to avoid intermediate growth.
    out.ensureWritable(VarInt.size(payloadLength) + payloadLength);
    VarInt.write(out, payloadLength);
    out.writeBytes(msg);
  }
}
