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

import dev.warp.protocol.bench.AbstractMicrobenchmark;

import java.security.GeneralSecurityException;
import java.util.SplittableRandom;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Cost of encrypting and decrypting one frame with {@link CipherEncoder} and {@link CipherDecoder},
 * the AES/CFB8 stream cipher of an online-mode connection.
 *
 * <p>Each handler sits alone in a channel using the pooled allocator, as in the proxy, and the
 * frame goes through the pipeline with the void promise, so the score holds only what the handler
 * itself costs. {@code buffer} is the kind of buffer the frame arrives in: direct, as socket reads
 * and Warp's own encoders produce them, or heap. A 16 KiB frame is larger than the handlers process
 * in one cipher call, so it also covers a frame split across several calls.
 */
@State(Scope.Thread)
@SuppressWarnings("checkstyle:VisibilityModifier") // JMH injects @Param fields directly
public class CipherBenchmark extends AbstractMicrobenchmark {

  /** Where the frame's bytes live. */
  public enum BufferKind {
    /** Off-heap memory, as socket reads and Warp's encoders produce it. */
    DIRECT,
    /** A Java array. */
    HEAP
  }

  @Param({"DIRECT", "HEAP"})
  BufferKind buffer;

  @Param({"1024", "16384"})
  int frameSize;

  private EmbeddedChannel encoder;
  private EmbeddedChannel decoder;
  private ByteBuf frame;

  /**
   * Builds both channels and a frame of random bytes.
   *
   * @throws GeneralSecurityException if the AES cipher is unavailable
   */
  @Setup(Level.Trial)
  public void setUp() throws GeneralSecurityException {
    SecretKey key = new SecretKeySpec(new byte[16], "AES");
    encoder = channel(new CipherEncoder(key));
    decoder = channel(new CipherDecoder(key));
    PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;
    frame =
        buffer == BufferKind.DIRECT ? alloc.directBuffer(frameSize) : alloc.heapBuffer(frameSize);
    byte[] bytes = new byte[frameSize];
    new SplittableRandom(frameSize).nextBytes(bytes);
    frame.writeBytes(bytes);
  }

  /**
   * Encrypts the frame once.
   *
   * @return the number of bytes written, consumed by JMH
   */
  @Benchmark
  public long encrypt() {
    frame.readerIndex(0);
    var _ = encoder.writeOneOutbound(frame.retain(), encoder.voidPromise());
    encoder.flushOutbound();
    return drain(encoder.readOutbound());
  }

  /**
   * Decrypts the frame once.
   *
   * @return the number of bytes passed on, consumed by JMH
   */
  @Benchmark
  public long decrypt() {
    frame.readerIndex(0);
    decoder.pipeline().fireChannelRead(frame.retain());
    return drain(decoder.readInbound());
  }

  /** Releases the frame and closes both channels. */
  @TearDown(Level.Trial)
  public void tearDown() {
    encoder.finishAndReleaseAll();
    decoder.finishAndReleaseAll();
    frame.release();
  }

  private static long drain(ByteBuf out) {
    long readable = out.readableBytes();
    out.release();
    return readable;
  }

  private static EmbeddedChannel channel(ChannelHandler handler) {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    channel.config().setAllocator(PooledByteBufAllocator.DEFAULT);
    return channel;
  }
}
