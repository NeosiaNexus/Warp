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

import dev.warp.jni.LibdeflateCodec;
import dev.warp.jni.NativeStatus;
import dev.warp.protocol.bench.AbstractMicrobenchmark;
import dev.warp.protocol.bench.BenchmarkConfig;
import dev.warp.protocol.bench.PacketCorpus;
import dev.warp.protocol.bench.PacketCorpus.Workload;
import dev.warp.protocol.bench.WireStreams;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Cost of the codec primitives a proxy may run for every relayed packet, per packet of the corpus.
 *
 * <p>Compares the three implementations a proxy can run: {@code java.util.zip} and the JDK's AES
 * provider ({@link JavaCompressor}, {@link dev.warp.protocol.netty.CipherEncoder}), Warp's bundled
 * libdeflate through the FFM API ({@link LibdeflateCodec}, with the JDK's cipher), and Velocity's
 * natives, libdeflate and OpenSSL through JNI: the exact code Velocity and Paper run in production.
 * Scores use the same corpus and per-packet normalisation as {@link
 * dev.warp.protocol.netty.ForwardingPathBenchmark}, so they decompose its result and price what a
 * relay pays per packet:
 *
 * <ul>
 *   <li>{@code inflate} / {@code deflate}: every packet at or above the compression threshold
 *       ({@code deflate} alone is what a proxy pays for every packet when its backends send
 *       uncompressed, as Velocity recommends);
 *   <li>{@code encrypt} / {@code decrypt}: every frame of the compressed wire stream, i.e. the
 *       client-bound bytes.
 * </ul>
 *
 * <p>A trial aborts if a native implementation falls back to Java, so a "native" score is always
 * native.
 */
@State(Scope.Thread)
@SuppressWarnings("checkstyle:VisibilityModifier") // JMH injects @Param fields directly
@OperationsPerInvocation(BenchmarkConfig.PACKETS)
public class CodecCostBenchmark extends AbstractMicrobenchmark {

  /** Codec implementations under comparison. */
  public enum Implementation {
    /** {@code java.util.zip} and the JDK's AES provider: Warp's fallback. */
    JDK,
    /** Velocity and Paper in production: libdeflate and OpenSSL via JNI. */
    VELOCITY_NATIVE,
    /** Warp: its bundled libdeflate via the FFM API, and the JDK's AES provider. */
    WARP_NATIVE
  }

  @Param({"CHUNK", "MIXED", "SMALL"})
  Workload workload;

  @Param({"JDK", "VELOCITY_NATIVE", "WARP_NATIVE"})
  Implementation implementation;

  /** Compression level of {@code deflate}; 6 is the default of vanilla, Paper and Velocity. */
  @Param({"6"})
  int level;

  /**
   * Connections the packets are spread over, one after the other. Velocity and {@code
   * java.util.zip} keep a context per connection, so each packet finds a different one, cold in the
   * CPU's caches, as on a busy proxy; Warp's contexts belong to the thread, whatever the number.
   */
  @Param({"1"})
  int connections;

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
    // Minecraft uses the shared secret as the IV as well; a random one, as a real session has.
    byte[] secret = new byte[16];
    new SecureRandom().nextBytes(secret);
    SecretKey key = new SecretKeySpec(secret, "AES");
    codec =
        switch (implementation) {
          case JDK -> new JdkCodec(level, connections, key);
          case VELOCITY_NATIVE -> new VelocityNativeCodec(level, connections, key);
          case WARP_NATIVE -> new WarpNativeCodec(level, key);
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

  /**
   * Decrypts every frame of the compressed wire stream, as the receiving leg of each proxy does.
   * CFB8 decryption costs the same whatever the bytes, so the frames serve as ciphertext.
   *
   * @return total decrypted bytes, consumed by JMH
   * @throws GeneralSecurityException if decryption fails
   */
  @Benchmark
  public long decrypt() throws GeneralSecurityException {
    long produced = 0;
    for (ByteBuf frame : frames) {
      scratch.clear();
      produced += codec.decrypt(frame, scratch);
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

    int decrypt(ByteBuf frame, ByteBuf scratch) throws GeneralSecurityException;

    @Override
    void close();
  }

  /**
   * The JDK's AES/CFB8, as {@link dev.warp.protocol.netty.CipherEncoder} and its decoder use it.
   */
  private static final class JdkCipher {
    private final Cipher encryptor;
    private final Cipher decryptor;

    JdkCipher(SecretKey key) throws GeneralSecurityException {
      encryptor = Cipher.getInstance("AES/CFB8/NoPadding");
      encryptor.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(key.getEncoded()));
      decryptor = Cipher.getInstance("AES/CFB8/NoPadding");
      decryptor.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(key.getEncoded()));
    }

    int encrypt(ByteBuf frame, ByteBuf scratch) throws GeneralSecurityException {
      return update(encryptor, frame, scratch);
    }

    int decrypt(ByteBuf frame, ByteBuf scratch) throws GeneralSecurityException {
      return update(decryptor, frame, scratch);
    }

    private static int update(Cipher cipher, ByteBuf frame, ByteBuf scratch)
        throws GeneralSecurityException {
      int length = frame.readableBytes();
      ByteBuffer destination = scratch.nioBuffer(0, length);
      cipher.update(frame.nioBuffer(), destination);
      return destination.position();
    }
  }

  /** Mirrors {@link JavaCompressor} and {@link dev.warp.protocol.netty.CipherEncoder}. */
  private static final class JdkCodec implements Codec {
    private final JavaCompressor[] compressors;
    private final JdkCipher cipher;
    private int next;

    JdkCodec(int level, int connections, SecretKey key) throws GeneralSecurityException {
      compressors = new JavaCompressor[connections];
      for (int i = 0; i < connections; i++) {
        compressors[i] = new JavaCompressor(level);
      }
      cipher = new JdkCipher(key);
    }

    private JavaCompressor next() {
      JavaCompressor compressor = compressors[next];
      next = next + 1 == compressors.length ? 0 : next + 1;
      return compressor;
    }

    @Override
    public int encrypt(ByteBuf frame, ByteBuf scratch) throws GeneralSecurityException {
      return cipher.encrypt(frame, scratch);
    }

    @Override
    public int decrypt(ByteBuf frame, ByteBuf scratch) throws GeneralSecurityException {
      return cipher.decrypt(frame, scratch);
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
      next().deflate(source, destination);
    }

    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
        throws DataFormatException {
      next().inflate(source, destination, uncompressedSize);
    }

    @Override
    public void close() {
      for (JavaCompressor compressor : compressors) {
        compressor.close();
      }
    }
  }

  /** Warp's native codec, as the proxy's compressor calls it; refuses to run on a fallback. */
  private static final class WarpNativeCodec implements Codec {
    private final LibdeflateCodec codec;
    private final JdkCipher cipher;

    WarpNativeCodec(int level, SecretKey key) throws GeneralSecurityException {
      cipher = new JdkCipher(key);
      NativeStatus status = dev.warp.jni.Natives.compression();
      if (!status.available()) {
        throw new IllegalStateException("Warp's native libdeflate is unavailable: " + status);
      }
      codec = LibdeflateCodec.create(level);
    }

    @Override
    public int encrypt(ByteBuf frame, ByteBuf scratch) throws GeneralSecurityException {
      return cipher.encrypt(frame, scratch);
    }

    @Override
    public int decrypt(ByteBuf frame, ByteBuf scratch) throws GeneralSecurityException {
      return cipher.decrypt(frame, scratch);
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) {
      codec.deflate(source, destination);
    }

    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
        throws DataFormatException {
      codec.inflate(source, destination, uncompressedSize);
    }

    @Override
    public void close() {}
  }

  /** Velocity's production natives; refuses to run on their Java fallback. */
  private static final class VelocityNativeCodec implements Codec {
    private final VelocityCompressor[] compressors;
    private final VelocityCipher encryptor;
    private final VelocityCipher decryptor;
    private int next;

    VelocityNativeCodec(int level, int connections, SecretKey key) throws GeneralSecurityException {
      requireNative(Natives.compress.getLoadedVariant());
      requireNative(Natives.cipher.getLoadedVariant());
      compressors = new VelocityCompressor[connections];
      for (int i = 0; i < connections; i++) {
        compressors[i] = Natives.compress.get().create(level);
      }
      encryptor = Natives.cipher.get().forEncryption(key);
      decryptor = Natives.cipher.get().forDecryption(key);
    }

    private VelocityCompressor next() {
      VelocityCompressor compressor = compressors[next];
      next = next + 1 == compressors.length ? 0 : next + 1;
      return compressor;
    }

    private static void requireNative(String variant) {
      if (variant.toLowerCase(Locale.ROOT).contains("java")) {
        throw new IllegalStateException("Velocity natives unavailable, loaded: " + variant);
      }
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
      next().deflate(source, destination);
    }

    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
        throws DataFormatException {
      next().inflate(source, destination, uncompressedSize);
    }

    @Override
    public int encrypt(ByteBuf frame, ByteBuf scratch) {
      encryptor.process(frame);
      return frame.readableBytes();
    }

    @Override
    public int decrypt(ByteBuf frame, ByteBuf scratch) {
      decryptor.process(frame);
      return frame.readableBytes();
    }

    @Override
    public void close() {
      for (VelocityCompressor compressor : compressors) {
        compressor.close();
      }
      encryptor.close();
      decryptor.close();
    }
  }
}
