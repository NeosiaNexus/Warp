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

import dev.warp.protocol.bench.BenchmarkConfig;
import dev.warp.protocol.bench.EventLoopLikeExecutor;
import dev.warp.protocol.bench.PacketCorpus;
import dev.warp.protocol.bench.PacketCorpus.Workload;
import dev.warp.protocol.bench.WireStreams;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.encryption.VelocityCipher;
import com.velocitypowered.natives.util.Natives;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Cost of the codec primitives a proxy may run for every relayed packet, per packet of the corpus.
 *
 * <p>Compares Warp's JDK-backed implementations ({@link JavaCompressor}, {@code AES/CFB8} from the
 * JDK provider, as used by {@link dev.warp.protocol.netty.CipherEncoder}) with Velocity's natives —
 * libdeflate and OpenSSL, the exact code Velocity and Paper run in production. Scores use the same
 * corpus and per-packet normalisation as {@link dev.warp.protocol.netty.ForwardingPathBenchmark},
 * so they decompose its result and price what a Velocity-style relay pays per packet:
 *
 * <ul>
 *   <li>{@code inflate} / {@code deflate} — every packet at or above the compression threshold;
 *   <li>{@code encrypt} — every frame of the compressed wire stream, i.e. the client-bound bytes.
 * </ul>
 *
 * <p>A trial aborts if Velocity's natives fall back to Java, so a "native" score is always native.
 */
@State(Scope.Thread)
@SuppressWarnings("checkstyle:VisibilityModifier") // JMH injects @Param fields directly
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(
    value = 3,
    jvmArgsAppend = {
      "-Xms2g",
      "-Xmx2g",
      "-XX:+AlwaysPreTouch",
      "--sun-misc-unsafe-memory-access=allow",
      "-Dio.netty.leakDetection.level=disabled",
      EventLoopLikeExecutor.JMH_EXECUTOR,
      EventLoopLikeExecutor.JMH_EXECUTOR_CLASS
    })
@OperationsPerInvocation(BenchmarkConfig.PACKETS)
public class CodecCostBenchmark {

  /** Codec implementations under comparison. */
  public enum Implementation {
    /** Warp today: {@code java.util.zip} and the JDK's AES provider. */
    JDK,
    /** Velocity and Paper in production: libdeflate and OpenSSL via JNI. */
    VELOCITY_NATIVE
  }

  @Param({"CHUNK", "MIXED"})
  Workload workload;

  @Param({"JDK", "VELOCITY_NATIVE"})
  Implementation implementation;

  private final List<ByteBuf> payloads = new ArrayList<>();
  private final List<ByteBuf> compressed = new ArrayList<>();
  private final List<ByteBuf> frames = new ArrayList<>();
  private int[] uncompressedSizes;
  private ByteBuf scratch;
  private Codec codec;

  /**
   * Builds the corpus in direct buffers and initialises the selected implementation.
   *
   * @throws GeneralSecurityException if a cipher cannot be initialised
   * @throws DataFormatException if the reference compression fails
   */
  @Setup(Level.Trial)
  public void setUp() throws GeneralSecurityException, DataFormatException {
    PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;
    List<ByteBuf> corpus =
        PacketCorpus.generate(
            workload, BenchmarkConfig.PACKETS, BenchmarkConfig.VERSION, BenchmarkConfig.SEED);
    JavaCompressor reference = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
    for (ByteBuf packet : corpus) {
      if (packet.readableBytes() >= BenchmarkConfig.THRESHOLD) {
        ByteBuf payload =
            alloc
                .directBuffer(packet.readableBytes())
                .writeBytes(packet, 0, packet.readableBytes());
        ByteBuf deflated = alloc.directBuffer();
        reference.deflate(payload, deflated);
        payloads.add(payload);
        compressed.add(deflated);
      }
    }
    reference.close();
    uncompressedSizes = payloads.stream().mapToInt(ByteBuf::readableBytes).toArray();

    // One buffer per wire frame of the compressed stream: what the client leg encrypts.
    ByteBuf stream =
        WireStreams.encode(corpus, BenchmarkConfig.THRESHOLD, Deflater.DEFAULT_COMPRESSION);
    frames.addAll(WireStreams.splitFrames(stream, alloc));
    stream.release();
    corpus.forEach(ByteBuf::release);

    scratch = alloc.directBuffer(256 * 1024);
    SecretKey key = new SecretKeySpec(new byte[16], "AES");
    codec =
        switch (implementation) {
          case JDK -> new JdkCodec(key);
          case VELOCITY_NATIVE -> new VelocityNativeCodec(key);
        };
  }

  /**
   * Compresses every packet at or above the threshold.
   *
   * @return total compressed bytes, consumed by JMH
   * @throws DataFormatException if compression fails
   */
  @Benchmark
  public long deflate() throws DataFormatException {
    long produced = 0;
    for (ByteBuf payload : payloads) {
      payload.readerIndex(0);
      scratch.clear();
      codec.deflate(payload, scratch);
      produced += scratch.readableBytes();
    }
    return produced;
  }

  /**
   * Decompresses every compressed packet.
   *
   * @return total decompressed bytes, consumed by JMH
   * @throws DataFormatException if decompression fails
   */
  @Benchmark
  public long inflate() throws DataFormatException {
    long produced = 0;
    for (int i = 0; i < compressed.size(); i++) {
      ByteBuf source = compressed.get(i);
      source.readerIndex(0);
      scratch.clear();
      codec.inflate(source, scratch, uncompressedSizes[i]);
      produced += scratch.readableBytes();
    }
    return produced;
  }

  /**
   * Encrypts every frame of the compressed wire stream, each implementation the way its proxy does:
   * Warp's encoder writes into a separate output buffer, Velocity's OpenSSL cipher works in place.
   * Re-encrypting the same bytes costs exactly the same, so frames are not reset between runs.
   *
   * @return total encrypted bytes, consumed by JMH
   * @throws GeneralSecurityException if encryption fails
   */
  @Benchmark
  public long encrypt() throws GeneralSecurityException {
    long produced = 0;
    for (ByteBuf frame : frames) {
      scratch.clear();
      produced += codec.encrypt(frame, scratch);
    }
    return produced;
  }

  /** Releases native resources and buffers. */
  @TearDown(Level.Trial)
  public void tearDown() {
    codec.close();
    payloads.forEach(ByteBuf::release);
    compressed.forEach(ByteBuf::release);
    frames.forEach(ByteBuf::release);
    scratch.release();
  }

  // ---------------------------------------------------------------------------
  // Implementations
  // ---------------------------------------------------------------------------

  private interface Codec extends AutoCloseable {
    void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException;

    void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
        throws DataFormatException;

    int encrypt(ByteBuf frame, ByteBuf scratch) throws GeneralSecurityException;

    @Override
    void close();
  }

  /** Mirrors {@link JavaCompressor} and {@link dev.warp.protocol.netty.CipherEncoder}. */
  private static final class JdkCodec implements Codec {
    private final JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
    private final Cipher cipher;

    JdkCodec(SecretKey key) throws GeneralSecurityException {
      cipher = Cipher.getInstance("AES/CFB8/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(key.getEncoded()));
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
      compressor.deflate(source, destination);
    }

    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
        throws DataFormatException {
      compressor.inflate(source, destination, uncompressedSize);
    }

    @Override
    public int encrypt(ByteBuf frame, ByteBuf scratch) throws GeneralSecurityException {
      int length = frame.readableBytes();
      ByteBuffer destination = scratch.nioBuffer(0, length);
      cipher.update(frame.nioBuffer(), destination);
      return destination.position();
    }

    @Override
    public void close() {
      compressor.close();
    }
  }

  /** Velocity's production natives; refuses to run on their Java fallback. */
  private static final class VelocityNativeCodec implements Codec {
    private final VelocityCompressor compressor;
    private final VelocityCipher cipher;

    VelocityNativeCodec(SecretKey key) throws GeneralSecurityException {
      requireNative(Natives.compress.getLoadedVariant());
      requireNative(Natives.cipher.getLoadedVariant());
      compressor = Natives.compress.get().create(Deflater.DEFAULT_COMPRESSION);
      cipher = Natives.cipher.get().forEncryption(key);
    }

    private static void requireNative(String variant) {
      if (variant.toLowerCase(java.util.Locale.ROOT).contains("java")) {
        throw new IllegalStateException("Velocity natives unavailable, loaded: " + variant);
      }
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
      compressor.deflate(source, destination);
    }

    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
        throws DataFormatException {
      compressor.inflate(source, destination, uncompressedSize);
    }

    @Override
    public int encrypt(ByteBuf frame, ByteBuf scratch) {
      cipher.process(frame);
      return frame.readableBytes();
    }

    @Override
    public void close() {
      compressor.close();
      cipher.close();
    }
  }
}
