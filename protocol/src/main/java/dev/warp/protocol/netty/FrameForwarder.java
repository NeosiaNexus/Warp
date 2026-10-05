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

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import org.jspecify.annotations.Nullable;

/**
 * Writes frames received on another connection to this one, in their original form whenever this
 * connection's peer accepts it.
 *
 * <p>{@link MinecraftDecoder} hands uninspected frames over exactly as they arrived: length prefix,
 * Data Length and (possibly compressed) payload. Forwarding one is a choice between two paths:
 *
 * <ul>
 *   <li><b>Verbatim</b> — the frame is written below this connection's framing and compression
 *       encoders, straight to encryption and the socket: no inflate, no deflate, no copy;
 *   <li><b>Re-encode</b> — the packet bytes are extracted (inflating only if the frame is
 *       compressed) and written through the normal outbound pipeline, which frames and, at or above
 *       the threshold, compresses them.
 * </ul>
 *
 * <h3>When verbatim is legal</h3>
 *
 * <p>Both connections must agree on the frame format (compressed or not), and the frame must be
 * <em>canonical</em> for this connection's threshold — exactly what vanilla's own encoder would
 * send: compressed if and only if the packet is at least the threshold. Receivers disagree on
 * everything else, so this is the one rule every known receiver accepts (verified against vanilla
 * from 1.8 to 26.3 and against client and server mods):
 *
 * <ul>
 *   <li>Vanilla servers, and clients up to 1.17 (protocol 755), reject compressed frames below
 *       their threshold; so do ViaProxy and ViaFabricPlus whatever the version. Vanilla clients
 *       since 1.17.1 (protocol 756) do not.
 *   <li>Vanilla never rejects an uncompressed frame above the threshold, but the Krypton network
 *       mod (0.2.9+ and Krypton Reno, on clients and Fabric servers) does, with no opt-out.
 * </ul>
 *
 * <p>Size caps are deliberately not checked here: a packet above a peer's cap cannot be delivered
 * to it in any form, so re-encoding it would only waste CPU.
 *
 * <p>So with equal thresholds — the recommended configuration — every frame is forwarded verbatim
 * in both directions. With unequal ones, only the frames between the two thresholds are re-encoded:
 * inflated and sent uncompressed when below this connection's threshold (never re-deflated),
 * compressed when an uncompressed frame reaches it.
 *
 * <p>All methods must be called from this connection's event loop. A player's two connections share
 * one event loop, so the source connection's decompressor can be used here directly.
 */
public final class FrameForwarder {

  private final Channel channel;
  private boolean verbatimEnabled = true;
  private int threshold = -1;
  private @Nullable ChannelHandlerContext frameLayer;

  /**
   * Creates a forwarder writing to the given channel.
   *
   * @param channel the channel frames are written to
   */
  public FrameForwarder(Channel channel) {
    this.channel = channel;
  }

  // ---------------------------------------------------------------------------
  // Configuration
  // ---------------------------------------------------------------------------

  /**
   * Records that this connection now uses compressed frames.
   *
   * @param threshold the compression threshold announced on this connection
   */
  public void compressionEnabled(int threshold) {
    this.threshold = threshold;
    this.frameLayer = null; // the framing handler was replaced; resolved again on next use
  }

  /**
   * Enables or disables verbatim forwarding. When disabled, every frame is re-encoded — the
   * behaviour of proxies without compression passthrough, kept as a kill switch and as the baseline
   * of A/B benchmarks.
   *
   * @param enabled whether frames may be forwarded verbatim
   */
  public void setVerbatimEnabled(boolean enabled) {
    this.verbatimEnabled = enabled;
  }

  // ---------------------------------------------------------------------------
  // Forwarding
  // ---------------------------------------------------------------------------

  /**
   * Forwards a frame received on another connection. Does not flush.
   *
   * @param frame the complete frame as emitted by {@code source} (reader index at the length
   *     prefix); ownership is transferred
   * @param source the decoder of the connection the frame came from
   */
  public void forward(ByteBuf frame, MinecraftDecoder source) {
    forward(frame, source.decompressor(), source.takeInflatedPacket(frame));
  }

  /**
   * Forwards a frame received on another connection. Does not flush.
   *
   * @param frame the complete frame (reader index at the length prefix); ownership is transferred
   * @param source the decompressor of the connection the frame came from, or {@code null} if that
   *     connection is uncompressed
   * @param inflated the frame's packet bytes if the source already inflated it, or {@code null};
   *     ownership is transferred
   */
  public void forward(
      ByteBuf frame, @Nullable FrameDecompressor source, @Nullable ByteBuf inflated) {
    if (!channel.isActive()) {
      frame.release();
      if (inflated != null) {
        inflated.release();
      }
      return;
    }
    if (inflated != null) {
      if (verbatimEnabled && threshold >= 0 && source != null && accepts(frame)) {
        inflated.release();
        writeVerbatim(frame);
      } else {
        frame.release();
        writePacket(inflated);
      }
      return;
    }
    int start = frame.readerIndex();
    VarInt.skip(frame);
    if (source == null) {
      if (verbatimEnabled && threshold < 0) {
        writeVerbatim(frame.readerIndex(start));
      } else {
        writePacket(frame); // reader index at the packet ID
      }
      return;
    }

    int dataLength = VarInt.read(frame);
    if (verbatimEnabled && threshold >= 0 && accepts(dataLength, frame.readableBytes())) {
      writeVerbatim(frame.readerIndex(start));
      return;
    }
    if (dataLength == 0) {
      writePacket(frame); // reader index at the packet ID
      return;
    }
    ByteBuf packet;
    try {
      source.checkHeader(dataLength, frame.readableBytes());
      packet = source.inflate(channel.alloc(), frame, dataLength);
    } finally {
      frame.release();
    }
    writePacket(packet);
  }

  /**
   * Returns whether a compressed-format frame is canonical for this connection's threshold.
   *
   * @param dataLength the frame's Data Length ({@code 0} for an uncompressed packet)
   * @param payloadLength the bytes after the Data Length: the packet itself when {@code dataLength}
   *     is {@code 0}, the zlib stream otherwise
   * @return {@code true} if the frame may be written verbatim
   */
  boolean accepts(int dataLength, int payloadLength) {
    return dataLength == 0 ? payloadLength < threshold : dataLength >= threshold;
  }

  private boolean accepts(ByteBuf frame) {
    int index = frame.readerIndex();
    VarInt.skip(frame);
    int dataLength = VarInt.read(frame);
    boolean accepted = accepts(dataLength, frame.readableBytes());
    frame.readerIndex(index);
    return accepted;
  }

  private void writeVerbatim(ByteBuf frame) {
    ChannelHandlerContext ctx = frameLayer;
    if (ctx == null || ctx.isRemoved()) {
      ctx = resolveFrameLayer(channel.pipeline());
      frameLayer = ctx;
    }
    // Writing from the framing encoder's context skips it and everything above it: the frame goes
    // straight to the cipher (if any) and the socket.
    var _ = ctx.write(frame, channel.voidPromise());
  }

  private void writePacket(ByteBuf packet) {
    var _ = channel.write(packet, channel.voidPromise());
  }

  private static ChannelHandlerContext resolveFrameLayer(ChannelPipeline pipeline) {
    ChannelHandlerContext ctx = pipeline.context(CompressionEncoder.class);
    if (ctx == null) {
      ctx = pipeline.context(FrameEncoder.class);
    }
    if (ctx == null) {
      throw new IllegalStateException("No framing encoder in pipeline " + pipeline.names());
    }
    return ctx;
  }
}
