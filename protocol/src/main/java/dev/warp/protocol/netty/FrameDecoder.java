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

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import org.jspecify.annotations.Nullable;

/**
 * Splits a TCP byte stream into Minecraft protocol frames based on VarInt length prefixes.
 *
 * <p>Wire format: {@code [VarInt: Length][Length bytes of payload]}. The length field covers the
 * packet ID and data — everything after the length prefix itself. Per the Minecraft protocol, the
 * length VarInt is at most 3 bytes (max value {@value dev.warp.protocol.codec.VarInt#MAX_21_BIT}).
 *
 * <h3>Output: complete wire frames</h3>
 *
 * <p>Each emitted buffer is one <b>complete frame, length prefix included</b>, positioned at the
 * prefix (reader index 0). Downstream decoders skip the prefix themselves; in exchange, a frame the
 * proxy does not inspect can be written to the other connection byte for byte — no re-framing, no
 * copy, and the original encoding preserved (including the padded 3-byte length VarInts some
 * servers write, such as Minestom and Velocity). Empty frames are dropped, never emitted: since
 * 1.21.9 the vanilla client rejects them.
 *
 * <h3>Improvements over existing proxies</h3>
 *
 * <ul>
 *   <li><b>Dead-channel guard</b> — prevents wasted CPU on connections that closed mid-decode.
 *       Fixes BungeeCord's 550% CPU regression where stale data was repeatedly decoded on dead
 *       channels (SpigotMC/BungeeCord#2908).
 *   <li><b>Branchless VarInt decode</b> — the fast path reads 4 bytes in a single {@link
 *       ByteBuf#getIntLE(int)} call and uses the BLSMSK bit trick (Netty PR #14050, by @franz1981
 *       and @bonzini) to decode without branching. Constant ~3 ns/op regardless of VarInt length,
 *       vs ~6.5 ns/op for branch-per-byte approaches with unpredictable inputs.
 *   <li><b>Zero-copy extraction</b> — complete frames are emitted as retained slices via {@link
 *       ByteBuf#retainedSlice(int, int)}, sharing the underlying memory with no copy.
 *   <li><b>Cached exceptions</b> — protocol violations throw pre-allocated {@link DecoderException}
 *       instances with no-op {@code fillInStackTrace()} to avoid allocation on error paths.
 *   <li><b>Pre-login frame limits</b>: with {@link FrameLimits}, from a client that is not logged
 *       in, each frame is checked against the packet it announces as soon as its length and packet
 *       identifier are readable, and refused with a {@link FrameLimitException} before the rest of
 *       it is buffered: a connection that has not even sent its handshake can no longer make the
 *       proxy hold 2 MiB (Velocity {@code 374acad83}, Waterfall #607).
 * </ul>
 *
 * <h3>One frame at a time before login</h3>
 *
 * <p>While limits apply, each call emits at most one frame: the next frame's header is read only
 * once the previous frame went through the pipeline, which may have changed the state (a handshake
 * moves the client to STATUS or LOGIN, a Login Acknowledged to CONFIGURATION) and so the limits.
 * Without limits, every complete frame of a read is emitted in one pass.
 */
public final class FrameDecoder extends ByteToMessageDecoder {

  /** Maximum frame payload: 3-byte VarInt max = 2,097,151 bytes. */
  private static final int MAX_FRAME_LENGTH = VarInt.MAX_21_BIT;

  /** {@link #readFrameVarInt} result: the length prefix is not complete yet. */
  private static final int INCOMPLETE = -1;

  /** {@link #readFrameVarInt} result: the length prefix is wider than 3 bytes. */
  private static final int TOO_WIDE = -2;

  // ---------------------------------------------------------------------------
  // Cached errors — zero-alloc on the hot error path
  // ---------------------------------------------------------------------------

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException VARINT_TOO_WIDE =
      new DecoderException("Frame-length VarInt exceeds 3 bytes") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException OVERSIZED_FRAME =
      new DecoderException("Frame exceeds maximum length of " + MAX_FRAME_LENGTH + " bytes") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final FrameLimitException LONGER_THAN_STATE_ALLOWS =
      new FrameLimitException(
          FrameLimitException.Reason.TOO_LONG,
          "Frame longer than any packet a client may send in this state");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final FrameLimitException PACKET_NOT_ALLOWED =
      new FrameLimitException(
          FrameLimitException.Reason.PACKET_NOT_ALLOWED,
          "Packet a client may not send in this state");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final FrameLimitException LENGTH_OUTSIDE_PACKET_BOUNDS =
      new FrameLimitException(
          FrameLimitException.Reason.WRONG_LENGTH,
          "Frame length outside the bounds of the packet it announces");

  /** The limits of the state the client is read in, or {@code null} for none. */
  private @Nullable FrameLimits limits;

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  /** Creates a decoder bounded by the protocol's 3-byte length alone: for logged-in peers. */
  public FrameDecoder() {
    this(null);
  }

  /**
   * Creates a decoder bounded by {@code limits}.
   *
   * @param limits the limits of the state the client starts in, or {@code null} for none
   */
  public FrameDecoder(@Nullable FrameLimits limits) {
    this.limits = limits;
  }

  /**
   * Replaces the limits, from the next frame on: the proxy sets those of each state the client
   * enters, and none once it is logged in. Called on the channel's event loop.
   *
   * @param limits the limits, or {@code null} for none
   */
  public void limits(@Nullable FrameLimits limits) {
    this.limits = limits;
  }

  /**
   * Returns the limits frames are checked against.
   *
   * @return the limits, or {@code null} for none
   */
  public @Nullable FrameLimits limits() {
    return limits;
  }

  // ---------------------------------------------------------------------------
  // Decode
  // ---------------------------------------------------------------------------

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
    // Dead-channel guard: short-circuit if the connection closed but buffered data remains in
    // the pipeline. Without this, ByteToMessageDecoder keeps invoking decode() on stale data,
    // burning CPU for nothing (BungeeCord #2908).
    if (!ctx.channel().isActive()) {
      in.skipBytes(in.readableBytes());
      return;
    }

    FrameLimits bounds = this.limits;
    if (bounds != null) {
      decodeLimited(in, out, bounds);
      return;
    }

    // Decode as many complete frames as possible in a single pass. The inner loop avoids
    // repeated callDecode() overhead from the base class when multiple frames arrived in one
    // TCP segment (common under load).
    while (in.isReadable()) {
      int startIndex = in.readerIndex();
      int length = readFrameVarInt(in);

      if (length < 0) {
        // Incomplete VarInt: wait for more data. A malformed one is rejected only once the frames
        // before it are delivered: if one of them closes the connection, the next call drops these
        // bytes (dead-channel guard) instead of reporting a second error.
        if (length == TOO_WIDE && out.isEmpty()) {
          throw VARINT_TOO_WIDE;
        }
        in.readerIndex(startIndex);
        return;
      }

      if (length > MAX_FRAME_LENGTH) {
        throw OVERSIZED_FRAME;
      }

      if (length == 0) {
        // Empty frame — skip. The protocol doesn't define these, but silently discarding
        // is safer than throwing (could be padding from a buggy client/server).
        continue;
      }

      if (in.readableBytes() < length) {
        // Incomplete frame payload — reset to before the VarInt and wait.
        in.readerIndex(startIndex);
        return;
      }

      // Zero-copy: a retained slice of the whole frame, length prefix included.
      int frameLength = in.readerIndex() - startIndex + length;
      out.add(in.retainedSlice(startIndex, frameLength));
      in.readerIndex(startIndex + frameLength);
    }
  }

  /**
   * Decodes at most one frame, checked against {@code bounds} from its header.
   *
   * @param in the cumulated bytes
   * @param out receives the frame
   * @param bounds the limits of the state
   */
  private static void decodeLimited(ByteBuf in, List<Object> out, FrameLimits bounds) {
    int startIndex = in.readerIndex();
    int length = readFrameVarInt(in);
    if (length < 0) {
      if (length == TOO_WIDE) {
        throw VARINT_TOO_WIDE;
      }
      in.readerIndex(startIndex);
      return;
    }
    if (length == 0) {
      return; // Empty frame: skipped; the base class calls again for the next one.
    }
    if (length > bounds.largest()) {
      throw LONGER_THAN_STATE_ALLOWS;
    }
    if (!in.isReadable()) {
      in.readerIndex(startIndex);
      return;
    }
    // Every packet identifier of these states is one VarInt byte below 0x80.
    int packetId = in.getUnsignedByte(in.readerIndex());
    if (!bounds.allows(packetId)) {
      throw PACKET_NOT_ALLOWED;
    }
    if (!bounds.allows(packetId, length)) {
      throw LENGTH_OUTSIDE_PACKET_BOUNDS;
    }
    if (in.readableBytes() < length) {
      in.readerIndex(startIndex);
      return;
    }
    int frameLength = in.readerIndex() - startIndex + length;
    out.add(in.retainedSlice(startIndex, frameLength));
    in.readerIndex(startIndex + frameLength);
  }

  // ---------------------------------------------------------------------------
  // 3-byte VarInt reader (frame-length specific)
  // ---------------------------------------------------------------------------

  /**
   * Reads a VarInt of at most 3 bytes from the buffer.
   *
   * <p>When ≥ 4 bytes are readable, the branchless fast path reads all bytes in a single {@link
   * ByteBuf#getIntLE(int)} call and decodes via the BLSMSK bit trick (Netty PR #14050, by franz1981
   * and bonzini). This produces constant ~3 ns/op regardless of VarInt length, vs ~6.5 ns/op for
   * branch-per-byte approaches with unpredictable inputs.
   *
   * <p>The safe path handles the rare case of fewer than 4 readable bytes (TCP fragmentation at the
   * frame boundary).
   *
   * @return the decoded value (0 to {@value dev.warp.protocol.codec.VarInt#MAX_21_BIT}), {@link
   *     #INCOMPLETE} if not enough bytes are available yet, or {@link #TOO_WIDE} if the VarInt
   *     exceeds 3 bytes, the reader index then left unchanged
   */
  private static int readFrameVarInt(ByteBuf buf) {
    int readable = buf.readableBytes();
    if (readable == 0) {
      return INCOMPLETE;
    }

    int index = buf.readerIndex();

    // -----------------------------------------------------------------------
    // Branchless fast path (≥ 4 readable bytes)
    //
    // Reads 4 bytes in one getIntLE() call. The 4th byte may belong to the
    // payload — it is read but masked out during extraction. The technique:
    //   1. Invert the raw int, mask with 0x808080 → isolate bytes whose high
    //      bit is 0 (= stop bytes, no continuation flag).
    //   2. If no stop byte found in the first 3 bytes → VarInt > 3 bytes.
    //   3. BLSMSK (atStop ^ (atStop - 1)) creates a mask from the first stop
    //      byte down to bit 0 — zeroes everything after.
    //   4. Two shift-merge steps strip the continuation bits, packing the
    //      7-bit groups into a contiguous value.
    // -----------------------------------------------------------------------
    if (readable >= 4) {
      int raw = buf.getIntLE(index);

      int atStop = ~raw & 0x808080;
      if (atStop == 0) {
        return TOO_WIDE;
      }

      int bitsToKeep = Integer.numberOfTrailingZeros(atStop) + 1;
      buf.readerIndex(index + (bitsToKeep >> 3));

      int preserved = raw & (atStop ^ (atStop - 1));
      preserved = (preserved & 0x007F007F) | ((preserved & 0x00007F00) >> 1);
      preserved = (preserved & 0x00003FFF) | ((preserved & 0x3FFF0000) >> 2);
      return preserved;
    }

    // -----------------------------------------------------------------------
    // Safe path (< 4 readable bytes) — TCP fragmentation at frame boundary.
    // -----------------------------------------------------------------------
    byte b0 = buf.getByte(index);
    if (b0 >= 0) {
      buf.readerIndex(index + 1);
      return b0;
    }

    if (readable < 2) {
      return INCOMPLETE;
    }

    byte b1 = buf.getByte(index + 1);
    if (b1 >= 0) {
      buf.readerIndex(index + 2);
      return (b0 & 0x7F) | (b1 << 7);
    }

    if (readable < 3) {
      return INCOMPLETE;
    }

    byte b2 = buf.getByte(index + 2);
    if (b2 < 0) {
      return TOO_WIDE;
    }
    buf.readerIndex(index + 3);
    return (b0 & 0x7F) | ((b1 & 0x7F) << 7) | (b2 << 14);
  }
}
