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

import dev.warp.protocol.bench.AbstractMicrobenchmark;
import dev.warp.protocol.bench.BenchmarkConfig;
import dev.warp.protocol.bench.PacketCorpus;
import dev.warp.protocol.bench.PacketCorpus.Workload;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Cost of learning the packet id of one compressed frame, three ways:
 *
 * <ul>
 *   <li>{@code peek} — {@link DeflatePeek}, what Warp does for uninspected backend frames;
 *   <li>{@code partialInflate} — the JDK {@link Inflater} asked for three bytes, the approach of
 *       the unmerged Velocity PR #1078;
 *   <li>{@code fullInflate} — inflating the whole payload, what every other proxy does.
 * </ul>
 *
 * <p>Payloads are the compressed packets of the benchmark corpus (zlib level 6, as vanilla, Paper
 * and Minestom emit them). Scores are per compressed frame.
 */
@State(Scope.Thread)
@SuppressWarnings("checkstyle:VisibilityModifier") // JMH injects @Param fields directly
@OperationsPerInvocation(PacketIdPeekBenchmark.FRAMES)
public class PacketIdPeekBenchmark extends AbstractMicrobenchmark {

  /** Compressed frames processed per invocation; scores are per frame. */
  static final int FRAMES = 64;

  @Param({"CHUNK", "MIXED"})
  Workload workload;

  private final ByteBuf[] payloads = new ByteBuf[FRAMES];
  private final int[] sizes = new int[FRAMES];
  private final DeflatePeek peek = new DeflatePeek();
  private final Inflater inflater = new Inflater();
  private final ByteBuffer idBytes = ByteBuffer.allocateDirect(3);
  private ByteBuf scratch;

  /**
   * Compresses the corpus packets at or above the threshold, cycling to fill {@value #FRAMES}.
   *
   * @throws DataFormatException if compression fails
   */
  @Setup(Level.Trial)
  public void setUp() throws DataFormatException {
    PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;
    List<ByteBuf> corpus =
        PacketCorpus.generate(
            workload, BenchmarkConfig.PACKETS, BenchmarkConfig.VERSION, BenchmarkConfig.SEED);
    List<ByteBuf> compressible = new ArrayList<>();
    for (ByteBuf packet : corpus) {
      if (packet.readableBytes() >= BenchmarkConfig.THRESHOLD) {
        compressible.add(packet);
      }
    }
    try (JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION)) {
      for (int i = 0; i < FRAMES; i++) {
        ByteBuf packet = compressible.get(i % compressible.size());
        ByteBuf payload = alloc.directBuffer();
        compressor.deflate(packet, payload);
        payloads[i] = payload;
        sizes[i] = packet.readableBytes();
      }
    }
    corpus.forEach(ByteBuf::release);
    scratch = alloc.directBuffer(256 * 1024);
  }

  /**
   * Peeks every packet id without inflating.
   *
   * @return the sum of the ids, consumed by JMH
   */
  @Benchmark
  public long peek() {
    long sum = 0;
    for (ByteBuf payload : payloads) {
      sum += peek.peekVarInt(payload);
    }
    return sum;
  }

  /**
   * Inflates the first three bytes of every payload with the JDK's zlib.
   *
   * @return the sum of the first bytes, consumed by JMH
   * @throws DataFormatException if a stream is malformed
   */
  @Benchmark
  public long partialInflate() throws DataFormatException {
    long sum = 0;
    for (ByteBuf payload : payloads) {
      inflater.setInput(payload.nioBuffer());
      idBytes.clear();
      inflater.inflate(idBytes);
      sum += idBytes.get(0);
      inflater.reset();
    }
    return sum;
  }

  /**
   * Inflates every payload completely with the JDK's zlib.
   *
   * @return the sum of the first bytes, consumed by JMH
   * @throws DataFormatException if a stream is malformed
   */
  @Benchmark
  public long fullInflate() throws DataFormatException {
    long sum = 0;
    for (int i = 0; i < FRAMES; i++) {
      inflater.setInput(payloads[i].nioBuffer());
      ByteBuffer out = scratch.nioBuffer(0, sizes[i]);
      inflater.inflate(out);
      sum += out.get(0);
      inflater.reset();
    }
    return sum;
  }

  /** Releases buffers and native zlib state. */
  @TearDown(Level.Trial)
  public void tearDown() {
    for (ByteBuf payload : payloads) {
      payload.release();
    }
    scratch.release();
    inflater.end();
  }
}
