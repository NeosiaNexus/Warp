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
import dev.warp.protocol.compress.FrameDecompressor;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.jspecify.annotations.Nullable;

/**
 * Turns the wire frames of a connection into bare packets: strips the length prefix and, once the
 * connection is compressed, the Data Length, inflating compressed payloads.
 *
 * <p>Input: complete frames from {@link FrameDecoder}, {@code [Length][Packet ID][Payload]} or,
 * once compressed, {@code [Length][Data Length][payload]}. Output: {@code [Packet ID][Payload]}, a
 * zero-copy slice for uncompressed packets and a new buffer for compressed ones. Every check of
 * {@link FrameDecompressor} applies.
 *
 * <p>Only connections in packet input have one (see {@link MinecraftDecoder#usePacketInput()}): the
 * connections whose packets a plugin handler reads. Every other connection keeps the {@link
 * MinecraftDecoder} as its only inbound decoder, which inflates nothing it does not inspect.
 *
 * <p>Compression is switched on with {@link #enableCompression}, effective from the next frame,
 * which the connection calls from the event loop while handling the Set Compression packet.
 */
public final class PacketDecompressor extends MessageToMessageDecoder<ByteBuf> {

  /** Compression state, or {@code null} while the connection is uncompressed. */
  private @Nullable FrameDecompressor decompressor;

  /**
   * Creates a decompressor for a connection.
   *
   * @param decompressor the connection's decompressor if it is already compressed, or {@code null};
   *     closed with this handler
   */
  public PacketDecompressor(@Nullable FrameDecompressor decompressor) {
    this.decompressor = decompressor;
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
    // Dead-channel guard, as in MinecraftDecoder: frames split from the read that closed the
    // connection are dropped, not inflated.
    if (!ctx.channel().isActive()) {
      return;
    }
    VarInt.skip(frame);
    FrameDecompressor inflater = decompressor;
    out.add(inflater == null ? frame.retainedSlice() : inflater.packetOf(ctx.alloc(), frame));
  }

  /**
   * Switches to compressed frames, from the next frame on.
   *
   * @param decompressor validates and inflates compressed payloads; closed with this handler
   */
  public void enableCompression(FrameDecompressor decompressor) {
    FrameDecompressor previous = this.decompressor;
    this.decompressor = decompressor;
    if (previous != null) {
      previous.close();
    }
  }

  /**
   * Returns the connection's decompressor.
   *
   * @return it, or {@code null} while the connection is uncompressed
   */
  public @Nullable FrameDecompressor decompressor() {
    return decompressor;
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    FrameDecompressor inflater = decompressor;
    if (inflater != null) {
      decompressor = null;
      inflater.close();
    }
    super.handlerRemoved(ctx);
  }
}
