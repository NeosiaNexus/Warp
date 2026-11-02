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

import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.warp.jni.LibdeflateCodec;
import dev.warp.protocol.bench.BenchmarkConfig;
import dev.warp.protocol.bench.PacketCorpus;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;

import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.util.Natives;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.jspecify.annotations.Nullable;

/**
 * Native memory that compression holds for a number of players, measured rather than estimated.
 *
 * <ul>
 *   <li><b>Velocity</b> (and Paper): a libdeflate compressor and decompressor per compressed
 *       connection, {@code Natives.compress.get().create(level)};
 *   <li><b>Warp, java.util.zip fallback</b>: per connection, the decoder's {@code Inflater} and the
 *       encoder's {@code Deflater}, each created on first use;
 *   <li><b>Warp, libdeflate</b>: one compressor and one decompressor per event loop thread,
 *       whatever the number of players. The event loops are then shut down, which must give the
 *       memory back.
 * </ul>
 *
 * <p>Each connection or thread compresses and decompresses one chunk packet, so that its tables are
 * touched. Memory is glibc's {@code mallinfo2()} (bytes malloc has handed out, mmapped blocks
 * included: libdeflate and zlib allocate with malloc) and the resident set size, before and after.
 * A first round with few players warms the JIT up so that its own allocations stay out of the
 * measured rounds. Linux with glibc only.
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp protocol/build/libs/protocol-*-jmh.jar \
 *     dev.warp.protocol.compress.NativeMemoryProbe [players, default 1000] [event loops]
 * </pre>
 *
 * <p>Event loops default to Netty's default, twice the available processors.
 */
public final class NativeMemoryProbe {

  private static final StructLayout MALLINFO2 =
      MemoryLayout.structLayout(
          JAVA_LONG.withName("arena"),
          JAVA_LONG.withName("ordblks"),
          JAVA_LONG.withName("smblks"),
          JAVA_LONG.withName("hblks"),
          JAVA_LONG.withName("hblkhd"),
          JAVA_LONG.withName("usmblks"),
          JAVA_LONG.withName("fsmblks"),
          JAVA_LONG.withName("uordblks"),
          JAVA_LONG.withName("fordblks"),
          JAVA_LONG.withName("keepcost"));

  private static final MethodHandle MALLINFO = mallinfo2();

  private static final int LEVEL = 6;

  private NativeMemoryProbe() {}

  /**
   * Prints, as a Markdown table, the native memory of each implementation for the given players.
   *
   * @param args the number of players (default 1000) and of event loop threads (default twice the
   *     processors)
   * @throws Exception if a codec fails
   */
  public static void main(String[] args) throws Exception {
    int players = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
    int loops = args.length > 1 ? Integer.parseInt(args[1]) : 2 * cores();
    ByteBuf chunk = chunkPacket();
    ByteBuf compressedChunk = PooledByteBufAllocator.DEFAULT.directBuffer();
    try (JavaCompressor reference = new JavaCompressor(LEVEL)) {
      reference.deflate(chunk, compressedChunk);
    }

    // One output buffer, allocated before any measurement: buffers are Netty's, not the codecs'.
    ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer(chunk.readableBytes() + 1024);

    // Warm-up: the same work at a small scale, so the JIT is done allocating when we measure.
    measure("warm-up", new Velocity(10, chunk, compressedChunk, out));
    measure("warm-up", new Java(10, chunk, compressedChunk, out));
    measure("warm-up", new Warp(2, chunk, compressedChunk, out));

    List<String[]> rows = new ArrayList<>();
    rows.add(
        measure(
            "Velocity: libdeflate per connection",
            new Velocity(players, chunk, compressedChunk, out)));
    rows.add(
        measure(
            "Warp: java.util.zip per connection (fallback)",
            new Java(players, chunk, compressedChunk, out)));
    rows.add(
        measure(
            "Warp: libdeflate per event loop (" + loops + " loops)",
            new Warp(loops, chunk, compressedChunk, out)));
    out.release();

    System.out.printf(
        Locale.ROOT,
        "%d players, one compressed connection each, level %d, chunk packet of %d bytes%n%n",
        players,
        LEVEL,
        chunk.readableBytes());
    System.out.println(
        "| Implementation | malloc in use | resident set | malloc left after freeing |");
    System.out.println("|---|---|---|---|");
    for (String[] row : rows) {
      System.out.println("| " + String.join(" | ", row) + " |");
    }
    chunk.release();
    compressedChunk.release();
  }

  // ---------------------------------------------------------------------------
  // Scenarios
  // ---------------------------------------------------------------------------

  /** One implementation's compression state for many players. */
  private abstract static class Scenario {
    final ByteBuf chunk;
    final ByteBuf compressed;
    final ByteBuf out;

    Scenario(ByteBuf chunk, ByteBuf compressed, ByteBuf out) {
      this.chunk = chunk;
      this.compressed = compressed;
      this.out = out;
    }

    /** Sets up what is not compression state, such as event loops, before the baseline. */
    void prepare() {}

    /** Allocates the compression state and uses each context once. */
    abstract void allocate() throws Exception;

    /** Frees everything {@link #prepare} and {@link #allocate} set up. */
    abstract void free();

    void exercise(Deflate deflate, Inflate inflate) throws DataFormatException {
      out.clear();
      deflate.deflate(chunk.duplicate(), out);
      out.clear();
      inflate.inflate(compressed.duplicate(), out, chunk.readableBytes());
    }
  }

  private static final class Velocity extends Scenario {
    private final int connections;
    private final List<VelocityCompressor> compressors = new ArrayList<>();

    Velocity(int connections, ByteBuf chunk, ByteBuf compressed, ByteBuf out) {
      super(chunk, compressed, out);
      this.connections = connections;
      if (Natives.compress.getLoadedVariant().toLowerCase(Locale.ROOT).contains("java")) {
        throw new IllegalStateException("Velocity's natives did not load");
      }
    }

    @Override
    void allocate() throws DataFormatException {
      for (int i = 0; i < connections; i++) {
        VelocityCompressor compressor = Natives.compress.get().create(LEVEL);
        exercise(compressor::deflate, compressor::inflate);
        compressors.add(compressor);
      }
    }

    @Override
    void free() {
      compressors.forEach(VelocityCompressor::close);
    }
  }

  private static final class Java extends Scenario {
    private final int connections;
    private final List<JavaCompressor> compressors = new ArrayList<>();

    Java(int connections, ByteBuf chunk, ByteBuf compressed, ByteBuf out) {
      super(chunk, compressed, out);
      this.connections = connections;
    }

    @Override
    void allocate() throws DataFormatException {
      for (int i = 0; i < connections; i++) {
        JavaCompressor decoder = new JavaCompressor(LEVEL);
        JavaCompressor encoder = new JavaCompressor(LEVEL);
        exercise(encoder::deflate, decoder::inflate);
        compressors.add(decoder);
        compressors.add(encoder);
      }
    }

    @Override
    void free() {
      compressors.forEach(JavaCompressor::close);
    }
  }

  private static final class Warp extends Scenario {
    private final int loops;
    private final List<EventLoop> threads = new ArrayList<>();
    private @Nullable EventLoopGroup group;

    Warp(int loops, ByteBuf chunk, ByteBuf compressed, ByteBuf out) {
      super(chunk, compressed, out);
      this.loops = loops;
      if (!dev.warp.jni.Natives.isNativeCompressionAvailable()) {
        throw new IllegalStateException(dev.warp.jni.Natives.compression().detail());
      }
    }

    @Override
    void prepare() {
      EventLoopGroup created = new MultiThreadIoEventLoopGroup(loops, NioIoHandler.newFactory());
      for (int i = 0; i < loops; i++) {
        EventLoop loop = created.next();
        loop.submit(() -> {}).syncUninterruptibly(); // starts its thread
        threads.add(loop);
      }
      group = created;
    }

    @Override
    void allocate() throws Exception {
      LibdeflateCodec codec = LibdeflateCodec.create(LEVEL);
      // Each loop serves all its players with the same contexts: one round per loop allocates
      // everything a loop will ever hold. The rounds run one after the other, so they share the
      // output buffer.
      for (EventLoop loop : threads) {
        loop.submit(
                () -> {
                  exercise(codec::deflate, codec::inflate);
                  return null;
                })
            .get();
      }
    }

    @Override
    void free() {
      if (group != null) {
        group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Measurement
  // ---------------------------------------------------------------------------

  private static String[] measure(String name, Scenario scenario) throws Exception {
    long mallocStart = mallocInUse();
    scenario.prepare();
    long mallocBefore = mallocInUse();
    long rssBefore = residentSetSize();
    scenario.allocate();
    long malloc = mallocInUse() - mallocBefore;
    long rss = residentSetSize() - rssBefore;
    scenario.free();
    Thread.sleep(500); // event loop threads finish terminating
    long left = mallocInUse() - mallocStart;
    return new String[] {name, mebibytes(malloc), mebibytes(rss), mebibytes(left)};
  }

  @FunctionalInterface
  private interface Deflate {
    void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException;
  }

  @FunctionalInterface
  private interface Inflate {
    void inflate(ByteBuf source, ByteBuf destination, int size) throws DataFormatException;
  }

  private static long mallocInUse() {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment info = (MemorySegment) MALLINFO.invokeExact((SegmentAllocator) arena);
      return info.get(
              JAVA_LONG, MALLINFO2.byteOffset(MemoryLayout.PathElement.groupElement("uordblks")))
          + info.get(
              JAVA_LONG, MALLINFO2.byteOffset(MemoryLayout.PathElement.groupElement("hblkhd")));
    } catch (Throwable t) {
      throw new IllegalStateException("mallinfo2 failed", t);
    }
  }

  private static long residentSetSize() throws IOException {
    for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
      if (line.startsWith("VmRSS:")) {
        String kibibytes = line.replaceAll("[^0-9]", "");
        try {
          return Long.parseLong(kibibytes) * 1024;
        } catch (NumberFormatException e) {
          throw new IOException("Unreadable VmRSS line: " + line, e);
        }
      }
    }
    throw new IOException("No VmRSS in /proc/self/status");
  }

  @SuppressWarnings("restricted")
  private static MethodHandle mallinfo2() {
    Linker linker = Linker.nativeLinker();
    MemorySegment function =
        linker
            .defaultLookup()
            .find("mallinfo2")
            .orElseThrow(() -> new IllegalStateException("mallinfo2 needs glibc 2.33 or later"));
    return linker.downcallHandle(function, FunctionDescriptor.of(MALLINFO2));
  }

  private static ByteBuf chunkPacket() {
    ByteBuf packet =
        PacketCorpus.generate(
                PacketCorpus.Workload.CHUNK, 1, BenchmarkConfig.VERSION, BenchmarkConfig.SEED)
            .getFirst();
    ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer().writeBytes(packet);
    packet.release();
    return direct;
  }

  private static int cores() {
    return Runtime.getRuntime().availableProcessors();
  }

  private static String mebibytes(long bytes) {
    return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
  }
}
