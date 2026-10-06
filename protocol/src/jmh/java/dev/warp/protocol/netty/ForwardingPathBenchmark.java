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

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.bench.AbstractMicrobenchmark;
import dev.warp.protocol.bench.BenchmarkConfig;
import dev.warp.protocol.bench.EventLoopLikeExecutor;
import dev.warp.protocol.bench.PacketCorpus;
import dev.warp.protocol.bench.PacketCorpus.Workload;
import dev.warp.protocol.bench.WireStreams;
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.packet.PacketDirection;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.zip.Deflater;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * CPU cost of relaying clientbound PLAY traffic from a backend to a player, per packet.
 *
 * <p>Wires the same handlers, in the same order, as the proxy does once both legs are in PLAY
 * state: the backend leg's inbound pipeline ({@link FrameDecoder} → {@link MinecraftDecoder} → a
 * tail that hands each frame to a {@link FrameForwarder}) feeds the client leg's outbound pipeline
 * ({@link MinecraftEncoder} → {@link CompressionEncoder} → optional {@link CipherEncoder}). {@link
 * CompressionMode#TRANSCODE} switches compression passthrough off, reproducing what proxies without
 * it pay. The backend stream is pre-encoded with vanilla settings (threshold 256, zlib level 6) and
 * delivered in 16 KiB reads, so frame reassembly across read boundaries is part of the measurement.
 * Socket I/O and cross-thread hand-off are not: this isolates the per-packet codec work that
 * dominates proxy CPU. Both legs run on a Netty {@code FastThreadLocalThread} ({@link
 * EventLoopLikeExecutor}), as they do on an event loop in the proxy.
 *
 * <p>Every trial first checks that the client-side bytes decode back to the original packets, so a
 * broken pipeline can never produce a flattering number.
 */
@State(Scope.Thread)
@SuppressWarnings("checkstyle:VisibilityModifier") // JMH injects @Param fields directly
@OperationsPerInvocation(BenchmarkConfig.PACKETS)
public class ForwardingPathBenchmark extends AbstractMicrobenchmark {

  private static final int READ_SIZE = 16 * 1024;

  @Param({"CHUNK", "ENTITY_MOVE", "MIXED"})
  Workload workload;

  @Param({"false", "true"})
  boolean encrypted;

  /** How uninspected compressed frames are relayed. */
  public enum CompressionMode {
    /** Forward the backend's compressed bytes as they are (Warp's default). */
    PASSTHROUGH,
    /** Inflate every compressed frame and compress it again, as other proxies do. */
    TRANSCODE
  }

  @Param({"PASSTHROUGH", "TRANSCODE"})
  CompressionMode mode;

  private List<ByteBuf> corpus;
  private ByteBuf backendStream;
  private List<ByteBuf> reads;
  private EmbeddedChannel backendLeg;
  private EmbeddedChannel clientLeg;

  /**
   * Builds the corpus, the backend wire stream and both pipeline legs, then validates one pass.
   *
   * @throws GeneralSecurityException if the AES cipher is unavailable
   */
  @Setup(Level.Trial)
  public void setUp() throws GeneralSecurityException {
    corpus =
        PacketCorpus.generate(
            workload, BenchmarkConfig.PACKETS, BenchmarkConfig.VERSION, BenchmarkConfig.SEED);
    backendStream =
        WireStreams.encode(corpus, BenchmarkConfig.THRESHOLD, Deflater.DEFAULT_COMPRESSION);
    reads = WireStreams.splitIntoReads(backendStream, READ_SIZE);
    long rawBytes = corpus.stream().mapToLong(ByteBuf::readableBytes).sum();
    System.out.printf(
        "Corpus %s: %d packets, %d raw bytes, %d wire bytes (%.1f B/packet on the wire)%n",
        workload,
        BenchmarkConfig.PACKETS,
        rawBytes,
        backendStream.readableBytes(),
        (double) backendStream.readableBytes() / BenchmarkConfig.PACKETS);

    boolean passthrough = mode == CompressionMode.PASSTHROUGH;
    SecretKey key = new SecretKeySpec(new byte[16], "AES");
    clientLeg = channel();
    if (encrypted) {
      clientLeg.pipeline().addLast(new CipherEncoder(key));
    }
    clientLeg
        .pipeline()
        .addLast(
            new CompressionEncoder(
                BenchmarkConfig.THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)),
            new MinecraftEncoder(
                PacketDirection.CLIENTBOUND, BenchmarkConfig.VERSION, ProtocolState.PLAY));
    FrameForwarder toClient = new FrameForwarder(clientLeg);
    toClient.compressionEnabled(BenchmarkConfig.THRESHOLD);
    toClient.setVerbatimEnabled(passthrough);

    MinecraftDecoder backendDecoder =
        new MinecraftDecoder(
            PacketDirection.CLIENTBOUND, BenchmarkConfig.VERSION, ProtocolState.PLAY);
    backendDecoder.enableCompression(
        new FrameDecompressor(
            BenchmarkConfig.THRESHOLD,
            false,
            FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE,
            new JavaCompressor(Deflater.DEFAULT_COMPRESSION)),
        passthrough);
    // Frames are forwarded from the pipeline tail, synchronously, as MinecraftConnection does.
    backendLeg =
        channel(
            new FrameDecoder(),
            backendDecoder,
            new ChannelInboundHandlerAdapter() {
              @Override
              public void channelRead(ChannelHandlerContext ctx, Object msg) {
                toClient.forward((ByteBuf) msg, backendDecoder);
              }
            });

    validate(encrypted ? key : null);
  }

  /**
   * Relays the whole corpus once: backend reads in, client-ready bytes out.
   *
   * @return the number of bytes written towards the client, consumed by JMH
   */
  @Benchmark
  public long relayClientbound() {
    long written = 0;
    for (ByteBuf read : reads) {
      written += relayRead(read, null);
    }
    return written;
  }

  /** Releases every buffer and closes both legs. */
  @TearDown(Level.Trial)
  public void tearDown() {
    backendLeg.finishAndReleaseAll();
    clientLeg.finishAndReleaseAll();
    reads.forEach(ByteBuf::release);
    backendStream.release();
    corpus.forEach(ByteBuf::release);
  }

  // ---------------------------------------------------------------------------
  // Relay — mirrors the proxy's per-read flow: decode, write per packet, flush on read complete
  // ---------------------------------------------------------------------------

  private long relayRead(ByteBuf read, @Nullable ByteBuf capture) {
    backendLeg.writeInbound(read.retainedDuplicate()); // decodes and forwards every frame
    clientLeg.flush();
    long written = 0;
    for (ByteBuf out = clientLeg.readOutbound(); out != null; out = clientLeg.readOutbound()) {
      written += out.readableBytes();
      if (capture != null) {
        capture.writeBytes(out, out.readerIndex(), out.readableBytes());
      }
      out.release();
    }
    return written;
  }

  private void validate(@Nullable SecretKey key) throws GeneralSecurityException {
    ByteBuf captured = Unpooled.directBuffer();
    for (ByteBuf read : reads) {
      relayRead(read, captured);
    }
    List<ByteBuf> decoded = WireStreams.decode(captured, BenchmarkConfig.THRESHOLD, key);
    try {
      if (decoded.size() != corpus.size()) {
        throw new IllegalStateException(
            "Relayed " + decoded.size() + " packets, expected " + corpus.size());
      }
      for (int i = 0; i < corpus.size(); i++) {
        if (!ByteBufUtil.equals(decoded.get(i), corpus.get(i))) {
          throw new IllegalStateException("Packet " + i + " corrupted by the relay");
        }
      }
    } finally {
      decoded.forEach(ByteBuf::release);
    }
  }

  private static EmbeddedChannel channel(ChannelHandler... handlers) {
    EmbeddedChannel channel = new EmbeddedChannel(handlers);
    channel.config().setAllocator(PooledByteBufAllocator.DEFAULT);
    return channel;
  }
}
