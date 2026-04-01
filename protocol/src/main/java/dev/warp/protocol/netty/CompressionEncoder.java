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
import dev.warp.protocol.compress.PacketCompressor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Compresses outgoing packets and prepends the frame length prefix.
 *
 * <p>This handler <b>replaces</b> {@link FrameEncoder} when compression is enabled, combining
 * compression and frame-length encoding in a single pass. This avoids an extra pipeline handler and
 * the overhead of measuring compressed output in a separate step.
 *
 * <h3>Wire format produced</h3>
 *
 * <ul>
 *   <li><b>Below threshold</b>: {@code [3-byte VarInt: Packet Length][0x00][PacketID + Payload]} —
 *       where Packet Length = 1 + payload size
 *   <li><b>At/above threshold</b>: {@code [3-byte VarInt: Packet Length][VarInt: Data
 *       Length][Compressed(PacketID + Payload)]} — where Packet Length = VarInt.size(dataLength) +
 *       compressed size
 * </ul>
 *
 * <h3>Reserve-and-backfill strategy</h3>
 *
 * <p>The outer Packet Length VarInt is always written as 3 bytes (overlong encoding via {@link
 * VarInt#encode21Bit(int)}). Three bytes are <b>reserved</b> at the start of the output, the
 * payload is written (compressed or raw), and then the actual length is <b>backfilled</b> via
 * {@link ByteBuf#setMedium(int, int)}. This avoids buffering the compressed output separately just
 * to measure its size.
 */
public final class CompressionEncoder extends MessageToByteEncoder<ByteBuf> {

  /** The outer frame-length VarInt is always encoded as 3 bytes for backfill simplicity. */
  private static final int FRAME_HEADER_SIZE = 3;

  private static final int MAX_COMPRESSED_LENGTH = VarInt.MAX_21_BIT;

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final EncoderException OVERSIZED_COMPRESSED =
      new EncoderException(
          "Compressed frame exceeds maximum length of " + MAX_COMPRESSED_LENGTH + " bytes") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  private final PacketCompressor compressor;
  private volatile int threshold;

  /**
   * Creates a compression encoder.
   *
   * @param threshold the compression threshold — packets below this size are sent uncompressed
   * @param compressor the compressor to use for compression
   */
  public CompressionEncoder(int threshold, PacketCompressor compressor) {
    this.threshold = threshold;
    this.compressor = compressor;
  }

  // ---------------------------------------------------------------------------
  // Encode
  // ---------------------------------------------------------------------------

  @Override
  protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
    int uncompressedSize = msg.readableBytes();

    // Read volatile once to avoid TOCTOU. Negative threshold = compression disabled.
    // Empty payload always goes uncompressed — Data Length = 0 means "uncompressed" in the
    // protocol, so compressing 0 bytes would produce zlib headers that the decoder would
    // misinterpret as raw packet data.
    int currentThreshold = this.threshold;
    if (currentThreshold < 0 || uncompressedSize == 0 || uncompressedSize < currentThreshold) {
      encodeUncompressed(msg, out, uncompressedSize);
    } else {
      encodeCompressed(msg, out, uncompressedSize);
    }
  }

  /**
   * Below threshold — write raw with Data Length = 0.
   *
   * <p>Format: {@code [3-byte Packet Length][0x00][raw payload]}
   */
  private static void encodeUncompressed(ByteBuf msg, ByteBuf out, int uncompressedSize) {
    int packetLength = 1 + uncompressedSize; // 1 byte for the 0x00 Data Length marker

    out.ensureWritable(FRAME_HEADER_SIZE + packetLength);
    int startIndex = out.writerIndex();
    out.writerIndex(startIndex + FRAME_HEADER_SIZE); // reserve header
    out.writeByte(0); // Data Length = 0 (uncompressed marker)
    out.writeBytes(msg);

    // Backfill the 3-byte frame length.
    out.setMedium(startIndex, VarInt.encode21Bit(packetLength));
  }

  /**
   * At/above threshold — compress with zlib and write Data Length = uncompressed size.
   *
   * <p>Format: {@code [3-byte Packet Length][VarInt Data Length][compressed data]}
   */
  private void encodeCompressed(ByteBuf msg, ByteBuf out, int uncompressedSize) throws Exception {
    int dataLengthSize = VarInt.size(uncompressedSize);

    // Estimate: header + data length VarInt + payload (worst case: incompressible data).
    out.ensureWritable(FRAME_HEADER_SIZE + dataLengthSize + uncompressedSize);
    int startIndex = out.writerIndex();
    out.writerIndex(startIndex + FRAME_HEADER_SIZE); // reserve header

    // Write Data Length = uncompressed size.
    VarInt.write(out, uncompressedSize);

    // Compress the payload directly into the output buffer.
    compressor.deflate(msg, out);

    // Calculate the actual Packet Length and backfill.
    int packetLength = out.writerIndex() - startIndex - FRAME_HEADER_SIZE;
    if (packetLength > MAX_COMPRESSED_LENGTH) {
      throw OVERSIZED_COMPRESSED;
    }
    out.setMedium(startIndex, VarInt.encode21Bit(packetLength));
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    compressor.close();
    super.handlerRemoved(ctx);
  }

  // ---------------------------------------------------------------------------
  // Configuration
  // ---------------------------------------------------------------------------

  /**
   * Updates the compression threshold. Called when the backend server sends a new SetCompression
   * packet (e.g., during server switch).
   *
   * @param threshold the new threshold
   */
  public void setThreshold(int threshold) {
    this.threshold = threshold;
  }
}
