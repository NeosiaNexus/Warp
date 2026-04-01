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

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;

/**
 * Decompresses Minecraft protocol frames after the outer length prefix has been stripped by {@link
 * FrameDecoder}.
 *
 * <h3>Wire format (input = one framed packet, outer length already consumed)</h3>
 *
 * <ul>
 *   <li><b>Uncompressed</b> ({@code Data Length = 0}): {@code [0x00][PacketID][Payload]}
 *   <li><b>Compressed</b> ({@code Data Length > 0}): {@code [VarInt: Data
 *       Length][Compressed(PacketID + Payload)]}
 * </ul>
 *
 * <p>When {@code Data Length} is zero, the remaining bytes are uncompressed and passed through
 * as-is via zero-copy {@link ByteBuf#readRetainedSlice(int)}. Uncompressed packets are accepted
 * <b>regardless</b> of whether their size exceeds the threshold — this follows vanilla Minecraft
 * behavior and avoids breaking modded servers (Velocity #1556).
 *
 * <h3>Security</h3>
 *
 * <ul>
 *   <li><b>Decompression bomb protection</b> — rejects claimed sizes exceeding the configurable
 *       maximum (default {@value #DEFAULT_MAX_UNCOMPRESSED_SIZE} bytes = 8 MiB)
 *   <li><b>Below-threshold rejection</b> — a non-zero {@code Data Length} less than the threshold
 *       is a protocol violation (compressed a packet that should have been sent raw)
 *   <li><b>Negative data length</b> — rejected as a protocol violation
 *   <li><b>Size mismatch</b> — verifies decompressed output matches the claimed Data Length exactly
 *   <li><b>Compression ratio check</b> — rejects packets where the ratio of claimed decompressed
 *       size to compressed size exceeds a configurable maximum (default {@value
 *       #DEFAULT_MAX_COMPRESSION_RATIO}). A 50-byte compressed payload claiming 8 MiB is almost
 *       certainly a decompression bomb. No upstream proxy validates this (Velocity #1742).
 *   <li><b>Rate-limited decompression</b> — tracks cumulative decompressed bytes per connection per
 *       second. If a connection exceeds the budget (default {@value
 *       #DEFAULT_MAX_DECOMPRESSION_RATE} bytes/sec = 128 MiB/sec), further decompression is
 *       rejected. This prevents sustained attacks where many individually-valid packets exhaust
 *       memory faster than GC can reclaim.
 * </ul>
 */
public final class CompressionDecoder extends MessageToMessageDecoder<ByteBuf> {

  /** Default maximum decompressed size: 8 MiB. Matches the vanilla server's serverbound limit. */
  public static final int DEFAULT_MAX_UNCOMPRESSED_SIZE = 8 * 1024 * 1024;

  /** Hard maximum: 128 MiB. For modded servers with extremely large packets. */
  public static final int HARD_MAX_UNCOMPRESSED_SIZE = 128 * 1024 * 1024;

  /**
   * Default maximum compression ratio (uncompressed / compressed). Packets exceeding this ratio are
   * rejected as probable decompression bombs. Value of 1024 means a 1 KB compressed payload may
   * claim at most 1 MiB decompressed. Set to 0 to disable ratio checking.
   */
  public static final int DEFAULT_MAX_COMPRESSION_RATIO = 1024;

  /**
   * Default maximum decompressed bytes per second per connection: 128 MiB. This is far above
   * legitimate gameplay traffic (~1–2 MiB/sec) but stops sustained attacks where an attacker sends
   * many packets each claiming large decompressed sizes. Set to 0 to disable rate limiting.
   */
  public static final long DEFAULT_MAX_DECOMPRESSION_RATE = 128L * 1024 * 1024;

  /** Rate-limit window duration in nanoseconds (1 second). */
  private static final long RATE_WINDOW_NANOS = 1_000_000_000L;

  // ---------------------------------------------------------------------------
  // Cached errors
  // ---------------------------------------------------------------------------

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException NEGATIVE_DATA_LENGTH =
      new DecoderException("Negative data length in compressed frame") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException BELOW_THRESHOLD =
      new DecoderException("Compressed data length below compression threshold") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException EXCEEDS_MAX =
      new DecoderException("Claimed uncompressed size exceeds maximum") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException SIZE_MISMATCH =
      new DecoderException("Decompressed size does not match claimed data length") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException SUSPICIOUS_RATIO =
      new DecoderException("Compression ratio exceeds maximum (potential decompression bomb)") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  @SuppressWarnings("StaticAssignmentOfThrowable")
  private static final DecoderException RATE_EXCEEDED =
      new DecoderException("Decompression rate limit exceeded (potential sustained attack)") {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      };

  // ---------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------

  private final PacketCompressor compressor;
  private final int maxUncompressedSize;
  private volatile int threshold;
  private volatile int maxCompressionRatio;
  private volatile long maxDecompressionRate;

  // Rate-limit tracking — only accessed from the EventLoop thread, no volatile needed.
  private long windowDecompressedBytes;
  private long windowStartNanos;

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  /**
   * Creates a compression decoder with the default maximum uncompressed size ({@value
   * #DEFAULT_MAX_UNCOMPRESSED_SIZE} bytes).
   *
   * @param threshold the compression threshold — packets below this size are sent uncompressed
   * @param compressor the compressor to use for decompression
   */
  public CompressionDecoder(int threshold, PacketCompressor compressor) {
    this(threshold, DEFAULT_MAX_UNCOMPRESSED_SIZE, compressor);
  }

  /**
   * Creates a compression decoder with a custom maximum uncompressed size.
   *
   * @param threshold the compression threshold
   * @param maxUncompressedSize the maximum allowed decompressed size (capped internally at {@value
   *     #HARD_MAX_UNCOMPRESSED_SIZE})
   * @param compressor the compressor to use for decompression
   */
  public CompressionDecoder(int threshold, int maxUncompressedSize, PacketCompressor compressor) {
    this.threshold = threshold;
    this.maxUncompressedSize = Math.min(maxUncompressedSize, HARD_MAX_UNCOMPRESSED_SIZE);
    this.maxCompressionRatio = DEFAULT_MAX_COMPRESSION_RATIO;
    this.maxDecompressionRate = DEFAULT_MAX_DECOMPRESSION_RATE;
    this.compressor = compressor;
  }

  // ---------------------------------------------------------------------------
  // Decode
  // ---------------------------------------------------------------------------

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
    // Snapshot volatile configuration fields into locals to avoid TOCTOU races if a setter
    // is called from another thread (e.g., during server-switch pipeline reconfiguration).
    int currentThreshold = this.threshold;
    int currentMaxRatio = this.maxCompressionRatio;
    long currentMaxRate = this.maxDecompressionRate;

    int dataLength = VarInt.read(in);

    if (dataLength == 0) {
      // Uncompressed frame — zero-copy passthrough. Accepted regardless of size relative
      // to threshold (vanilla behavior — Velocity #1556 broke modded servers by rejecting
      // large uncompressed packets).
      out.add(in.readRetainedSlice(in.readableBytes()));
      return;
    }

    if (dataLength < 0) {
      throw NEGATIVE_DATA_LENGTH;
    }

    if (dataLength < currentThreshold) {
      throw BELOW_THRESHOLD;
    }

    if (dataLength > maxUncompressedSize) {
      throw EXCEEDS_MAX;
    }

    // Compression ratio check — a tiny compressed payload claiming massive decompressed size
    // is almost certainly a decompression bomb. No upstream proxy validates this; the Gemstone
    // community fork of Velocity is the only known implementation (Velocity #1742).
    int compressedSize = in.readableBytes();
    if (currentMaxRatio > 0
        && compressedSize > 0
        && dataLength / compressedSize > currentMaxRatio) {
      throw SUSPICIOUS_RATIO;
    }

    // Per-connection decompression rate limit — prevents sustained attacks where an attacker
    // sends many individually-valid packets (each under the size cap, each with acceptable
    // ratio) that collectively exhaust heap faster than GC can reclaim. This is the attack
    // vector behind Velocity #1742, which remains OPEN and actively exploited in production.
    if (currentMaxRate > 0) {
      long now = System.nanoTime();
      if (now - windowStartNanos > RATE_WINDOW_NANOS) {
        windowDecompressedBytes = 0;
        windowStartNanos = now;
      }
      if (windowDecompressedBytes + dataLength > currentMaxRate) {
        throw RATE_EXCEEDED;
      }
      // Count before allocation — even if decompression fails, the attacker consumed resources.
      windowDecompressedBytes += dataLength;
    }

    // Decompress into a direct buffer — direct is preferred for downstream network I/O
    // and for future JNI native compressor compatibility.
    ByteBuf decompressed = ctx.alloc().directBuffer(dataLength);
    try {
      compressor.inflate(in, decompressed, dataLength);

      if (decompressed.readableBytes() != dataLength) {
        throw SIZE_MISMATCH;
      }

      out.add(decompressed);
    } catch (Exception e) {
      decompressed.release();
      throw e;
    }
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

  /**
   * Updates the maximum allowed compression ratio. Set to 0 to disable ratio checking.
   *
   * @param ratio the new maximum ratio (uncompressed / compressed), or 0 to disable
   */
  public void setMaxCompressionRatio(int ratio) {
    this.maxCompressionRatio = ratio;
  }

  /**
   * Updates the maximum decompression rate (bytes per second). Set to 0 to disable rate limiting.
   *
   * @param bytesPerSecond the new rate limit, or 0 to disable
   */
  public void setMaxDecompressionRate(long bytesPerSecond) {
    this.maxDecompressionRate = bytesPerSecond;
  }
}
