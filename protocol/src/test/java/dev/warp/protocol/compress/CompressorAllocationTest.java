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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.warp.protocol.codec.VarInt;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

import com.sun.management.ThreadMXBean;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.FastThreadLocalThread;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Inflating and deflating frames between pooled direct buffers, as the proxy does for every
 * compressed packet it reads or writes, must not allocate on the heap (issue #90).
 *
 * <p>Measured on a {@link FastThreadLocalThread}, as Netty's event loops are: a plain thread gets
 * no allocator thread cache, so each pooled buffer it takes comes without a cached NIO view and
 * costs one. Netty's leak detector is off while measuring: it records a stack trace for one buffer
 * in 128, which is its cost and not the codec's.
 */
@DisplayName("Compression allocations")
class CompressorAllocationTest {

  /** A tab list latency update for 40 players, the packet of issue #90. */
  private static final int PACKET_SIZE = 740;

  private static final int THRESHOLD = 256;
  private static final int WARMUP_CALLS = 20_000;
  private static final int MEASURED_CALLS = 20_000;
  private static final int ROUNDS = 3;

  /** The most heap a call may allocate on average: enough for nothing but measurement noise. */
  private static final long MAX_BYTES_PER_CALL = 16;

  private static final ByteBufAllocator ALLOC = PooledByteBufAllocator.DEFAULT;
  private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

  private JavaCompressor compressor;
  private ByteBuf plain;
  private ByteBuf compressed;

  @BeforeEach
  void setUp() throws DataFormatException {
    assumeTrue(
        THREADS.isThreadAllocatedMemorySupported() && THREADS.isThreadAllocatedMemoryEnabled(),
        "per-thread allocation accounting unsupported");
    compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
    plain = ALLOC.directBuffer(PACKET_SIZE);
    plain.writeBytes(packet());
    compressed = ALLOC.directBuffer(PACKET_SIZE);
    try (JavaCompressor reference = new JavaCompressor(Deflater.DEFAULT_COMPRESSION)) {
      reference.deflate(plain, compressed);
    }
  }

  @AfterEach
  void tearDown() {
    if (compressor != null) {
      compressor.close();
      plain.release();
      compressed.release();
    }
  }

  @Nested
  @DisplayName("JavaCompressor")
  class Compressor {

    @Test
    @DisplayName("should inflate between pooled direct buffers without allocating")
    void inflateAllocatesNothing() {
      ByteBuf out = ALLOC.directBuffer(PACKET_SIZE);
      try {
        assertNoAllocation(
            "JavaCompressor.inflate",
            () -> {
              out.clear();
              compressor.inflate(compressed, out, PACKET_SIZE);
            });
      } finally {
        out.release();
      }
    }

    @Test
    @DisplayName("should deflate between pooled direct buffers without allocating")
    void deflateAllocatesNothing() {
      ByteBuf out = ALLOC.directBuffer(compressor.deflateBound(PACKET_SIZE));
      try {
        assertNoAllocation(
            "JavaCompressor.deflate",
            () -> {
              out.clear();
              compressor.deflate(plain, out);
            });
      } finally {
        out.release();
      }
    }
  }

  @Nested
  @DisplayName("FrameDecompressor")
  class Decompressor {

    @Test
    @DisplayName("should inflate a payload into a pooled buffer without allocating")
    void inflateAllocatesNothing() {
      FrameDecompressor decompressor = decompressor();
      assertNoAllocation(
          "FrameDecompressor.inflate",
          () -> decompressor.inflate(ALLOC, compressed, PACKET_SIZE).release());
    }

    @Test
    @DisplayName("should read a compressed frame body without allocating")
    void packetOfAllocatesNothing() {
      FrameDecompressor decompressor = decompressor();
      ByteBuf body = ALLOC.directBuffer(VarInt.size(PACKET_SIZE) + compressed.readableBytes());
      VarInt.write(body, PACKET_SIZE);
      body.writeBytes(compressed, compressed.readerIndex(), compressed.readableBytes());
      try {
        assertNoAllocation(
            "FrameDecompressor.packetOf", () -> decompressor.packetOf(ALLOC, body).release());
      } finally {
        body.release();
      }
    }

    private FrameDecompressor decompressor() {
      return new FrameDecompressor(
          THRESHOLD, true, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, compressor);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private interface Call {
    void run() throws DataFormatException;
  }

  /**
   * Warms {@code call} up, then measures the heap it allocates per call on a {@link
   * FastThreadLocalThread}. The lowest of {@value #ROUNDS} rounds counts: a cost the code pays on
   * every call shows in all of them, a one-time cost (class loading, compilation) does not.
   */
  private static void assertNoAllocation(String what, Call call) {
    long[] perCall = new long[1];
    Throwable[] failure = new Throwable[1];
    Thread thread =
        new FastThreadLocalThread(
            () -> {
              try {
                perCall[0] = bytesPerCall(call);
              } catch (Throwable t) {
                failure[0] = t;
              }
            });
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
    if (failure[0] != null) {
      throw new AssertionError(what + " failed", failure[0]);
    }
    System.out.println(what + ": " + perCall[0] + " B per call");
    assertTrue(
        perCall[0] <= MAX_BYTES_PER_CALL,
        () ->
            what
                + " allocated "
                + perCall[0]
                + " B per call, expected at most "
                + MAX_BYTES_PER_CALL);
  }

  private static long bytesPerCall(Call call) throws DataFormatException {
    ResourceLeakDetector.Level leakDetection = ResourceLeakDetector.getLevel();
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
    try {
      for (int i = 0; i < WARMUP_CALLS; i++) {
        call.run();
      }
      long lowest = Long.MAX_VALUE;
      for (int round = 0; round < ROUNDS; round++) {
        long before = THREADS.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < MEASURED_CALLS; i++) {
          call.run();
        }
        long allocated = THREADS.getCurrentThreadAllocatedBytes() - before;
        lowest = Math.min(lowest, allocated / MEASURED_CALLS);
      }
      return lowest;
    } finally {
      ResourceLeakDetector.setLevel(leakDetection);
    }
  }

  /** Base64 text, as the skins and names of a tab list, so that the packet compresses. */
  private static byte[] packet() {
    byte[] alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            .getBytes(StandardCharsets.US_ASCII);
    Random random = new Random(90);
    byte[] packet = new byte[PACKET_SIZE];
    for (int i = 0; i < packet.length; i++) {
      packet[i] = alphabet[random.nextInt(alphabet.length)];
    }
    return packet;
  }
}
