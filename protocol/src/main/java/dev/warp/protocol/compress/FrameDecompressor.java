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
package dev.warp.protocol.compress;

import dev.warp.protocol.codec.VarInt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.DecoderException;

/**
 * Validates and decompresses the payloads of compressed Minecraft frames received on one
 * connection.
 *
 * <p>A compressed frame is {@code [VarInt Data Length][zlib(Packet ID + Payload)]} once its outer
 * length prefix has been stripped. This class separates the three things a proxy may do with one:
 *
 * <ol>
 *   <li>{@link #checkHeader} — cheap validation of the declared size against the compressed size,
 *       before anything else happens to the frame;
 *   <li>{@link #peekPacketId} — read the packet id without inflating, so frames the proxy does not
 *       inspect can be forwarded in their original compressed form;
 *   <li>{@link #inflate} — full decompression, with allocation and rate limits.
 * </ol>
 *
 * <h3>Security</h3>
 *
 * <ul>
 *   <li><b>Impossible ratios</b> — DEFLATE cannot expand data by more than {@value
 *       #MAX_DEFLATE_RATIO}:1 (two-bit codes for 258-byte matches), so a declared size above that
 *       bound is provably a lie and is rejected without inflating. Unlike tighter ratio caps, this
 *       can never reject a legitimate frame: Velocity's 64:1 limiter had to be reverted because
 *       vanilla book edits compress at ~139:1 (Velocity #1792).
 *   <li><b>Size cap</b> — declared sizes above {@link #DEFAULT_MAX_UNCOMPRESSED_SIZE} are refused
 *       before allocating, as the vanilla server does.
 *   <li><b>Decompression budget</b> — inflated bytes per connection per second are capped, which
 *       stops floods of individually valid frames from exhausting memory (Velocity #1742).
 *   <li><b>Exact size</b> — the inflated size must equal the declared size.
 *   <li><b>Below-threshold frames</b> — rejected only when {@code validateThreshold} is set,
 *       mirroring vanilla: servers validate, 1.17.1+ clients do not.
 * </ul>
 *
 * <p>Not thread-safe: one instance per connection, used from its event loop.
 */
public final class FrameDecompressor implements AutoCloseable {

  /** Default maximum declared uncompressed size: 8 MiB, the vanilla server's limit. */
  public static final int DEFAULT_MAX_UNCOMPRESSED_SIZE = 8 * 1024 * 1024;

  /** Hard maximum declared uncompressed size: 128 MiB, for modded servers with huge packets. */
  public static final int HARD_MAX_UNCOMPRESSED_SIZE = 128 * 1024 * 1024;

  /**
   * Default inflated-bytes budget per connection per second: 128 MiB, far above legitimate gameplay
   * traffic (~1–2 MiB/s) yet bounded.
   */
  public static final long DEFAULT_MAX_DECOMPRESSION_RATE = 128L * 1024 * 1024;

  /** DEFLATE's theoretical maximum expansion ratio (zlib technical details, "1032:1"). */
  static final int MAX_DEFLATE_RATIO = 1032;

  /** Slack covering the first literal and a maximal match before the ratio bound applies. */
  private static final int RATIO_SLACK = 258;

  private static final long RATE_WINDOW_NANOS = 1_000_000_000L;

  // ---------------------------------------------------------------------------
  // Cached errors
  // ---------------------------------------------------------------------------

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException NEGATIVE_DATA_LENGTH =
      stackless("Negative data length in compressed frame");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException BELOW_THRESHOLD =
      stackless("Compressed data length below compression threshold");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException IMPOSSIBLE_RATIO =
      stackless("Declared size exceeds what DEFLATE can produce from the compressed size");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException EXCEEDS_MAX =
      stackless("Claimed uncompressed size exceeds maximum");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException SIZE_MISMATCH =
      stackless("Decompressed size does not match claimed data length");

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException RATE_EXCEEDED =
      stackless("Decompression rate limit exceeded (potential sustained attack)");

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------

  private final PacketCompressor compressor;
  private final DeflatePeek peek = new DeflatePeek();
  private final int threshold;
  private final boolean validateThreshold;
  private final int maxUncompressedSize;
  private volatile long maxDecompressionRate = DEFAULT_MAX_DECOMPRESSION_RATE;

  // Rate-limit window — event loop only.
  private long windowInflatedBytes;
  private long windowStartNanos;

  /**
   * Creates a decompressor.
   *
   * @param threshold the compression threshold announced for this connection
   * @param validateThreshold whether compressed frames declaring fewer bytes than the threshold are
   *     rejected — {@code true} when the peer is a client, as on a vanilla server
   * @param maxUncompressedSize the maximum declared size accepted (capped at {@value
   *     #HARD_MAX_UNCOMPRESSED_SIZE})
   * @param compressor the zlib implementation; closed by {@link #close()}
   */
  public FrameDecompressor(
      int threshold,
      boolean validateThreshold,
      int maxUncompressedSize,
      PacketCompressor compressor) {
    this.threshold = threshold;
    this.validateThreshold = validateThreshold;
    this.maxUncompressedSize = Math.min(maxUncompressedSize, HARD_MAX_UNCOMPRESSED_SIZE);
    this.compressor = compressor;
  }

  // ---------------------------------------------------------------------------
  // Operations
  // ---------------------------------------------------------------------------

  /**
   * Validates a compressed frame's declared size against its compressed size. Allocation-free.
   *
   * @param dataLength the declared uncompressed size (non-zero: the frame is compressed)
   * @param compressedLength the number of compressed bytes following the Data Length VarInt
   * @throws DecoderException if the frame is invalid
   */
  public void checkHeader(int dataLength, int compressedLength) {
    if (dataLength < 0) {
      throw NEGATIVE_DATA_LENGTH;
    }
    if (validateThreshold && dataLength < threshold) {
      throw BELOW_THRESHOLD;
    }
    if (dataLength > (long) compressedLength * MAX_DEFLATE_RATIO + RATIO_SLACK) {
      throw IMPOSSIBLE_RATIO;
    }
  }

  /**
   * Reads the packet id of a compressed payload without inflating it.
   *
   * @param payload the zlib stream (readable bytes); indices untouched
   * @return the packet id, or {@link DeflatePeek#UNKNOWN} if the payload must be inflated to know
   */
  public int peekPacketId(ByteBuf payload) {
    return peek.peekVarInt(payload);
  }

  /**
   * Inflates a compressed payload, enforcing the size cap, the rate budget and the exact size.
   *
   * @param alloc the allocator for the output buffer
   * @param payload the zlib stream (readable bytes); indices untouched
   * @param dataLength the declared uncompressed size
   * @return a new buffer holding {@code [Packet ID][Payload]}, owned by the caller
   * @throws DecoderException if a limit is exceeded or the stream is malformed
   */
  public ByteBuf inflate(ByteBufAllocator alloc, ByteBuf payload, int dataLength) {
    if (dataLength > maxUncompressedSize) {
      throw EXCEEDS_MAX;
    }
    long rate = maxDecompressionRate;
    if (rate > 0) {
      long now = System.nanoTime();
      if (now - windowStartNanos > RATE_WINDOW_NANOS) {
        windowInflatedBytes = 0;
        windowStartNanos = now;
      }
      if (windowInflatedBytes + dataLength > rate) {
        throw RATE_EXCEEDED;
      }
      // Count before inflating: a failed attempt still cost the attacker's target resources.
      windowInflatedBytes += dataLength;
    }
    ByteBuf inflated = alloc.directBuffer(dataLength);
    try {
      compressor.inflate(payload, inflated, dataLength);
      if (inflated.readableBytes() != dataLength) {
        throw SIZE_MISMATCH;
      }
      return inflated;
    } catch (Exception e) {
      inflated.release();
      throw e instanceof DecoderException d ? d : new DecoderException(e);
    }
  }

  /**
   * Strips a frame's Data Length and returns its packet bytes, inflating only if needed.
   *
   * @param alloc the allocator used when the payload is compressed
   * @param body the frame body after the outer length prefix: {@code [Data Length][payload]};
   *     indices untouched
   * @return {@code [Packet ID][Payload]}: a retained slice when uncompressed, a new buffer when
   *     inflated; owned by the caller
   */
  public ByteBuf packetOf(ByteBufAllocator alloc, ByteBuf body) {
    ByteBuf cursor = body.duplicate();
    int dataLength = VarInt.read(cursor);
    if (dataLength == 0) {
      return cursor.retainedSlice();
    }
    checkHeader(dataLength, cursor.readableBytes());
    return inflate(alloc, cursor, dataLength);
  }

  // ---------------------------------------------------------------------------
  // Configuration and lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Returns the compression threshold this decompressor validates against.
   *
   * @return the threshold
   */
  public int threshold() {
    return threshold;
  }

  /**
   * Sets the inflated-bytes budget per second; {@code 0} disables the budget.
   *
   * @param bytesPerSecond the budget
   */
  public void setMaxDecompressionRate(long bytesPerSecond) {
    this.maxDecompressionRate = bytesPerSecond;
  }

  @Override
  public void close() {
    compressor.close();
  }

  /** Pre-allocated, stackless: the error paths of a hot decoder must not allocate. */
  private static DecoderException stackless(String message) {
    return new DecoderException(message) {
      @Override
      public synchronized Throwable fillInStackTrace() {
        return this;
      }
    };
  }
}
