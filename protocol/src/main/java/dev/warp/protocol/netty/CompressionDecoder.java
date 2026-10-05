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
import dev.warp.protocol.compress.PacketCompressor;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

/**
 * Decompresses <em>every</em> frame of a compressed connection into its packet bytes.
 *
 * <p>Input: complete frames from {@link FrameDecoder} ({@code [Length][Data Length][payload]}).
 * Output: {@code [Packet ID][Payload]} — a zero-copy slice for uncompressed frames, a new buffer
 * for compressed ones. All checks of {@link FrameDecompressor} apply.
 *
 * <p>This is the right tool for endpoints that consume every packet: test clients, load generators,
 * traffic recorders. The proxy itself does not use it — its {@link MinecraftDecoder} inflates only
 * the frames it inspects and forwards the rest in their original compressed form.
 */
public final class CompressionDecoder extends MessageToMessageDecoder<ByteBuf> {

  private final FrameDecompressor decompressor;

  /**
   * Creates a decoder that validates frames the way a vanilla server does (below-threshold
   * compressed frames are rejected) with the default size cap.
   *
   * @param threshold the compression threshold
   * @param compressor the zlib implementation; closed with this handler
   */
  public CompressionDecoder(int threshold, PacketCompressor compressor) {
    this(
        new FrameDecompressor(
            threshold, true, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, compressor));
  }

  /**
   * Creates a decoder around a configured decompressor.
   *
   * @param decompressor the decompressor; closed with this handler
   */
  public CompressionDecoder(FrameDecompressor decompressor) {
    this.decompressor = decompressor;
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
    VarInt.skip(frame);
    out.add(decompressor.packetOf(ctx.alloc(), frame));
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    decompressor.close();
    super.handlerRemoved(ctx);
  }

  /**
   * Returns the underlying decompressor, e.g. to tune its decompression budget.
   *
   * @return the decompressor
   */
  public FrameDecompressor decompressor() {
    return decompressor;
  }
}
