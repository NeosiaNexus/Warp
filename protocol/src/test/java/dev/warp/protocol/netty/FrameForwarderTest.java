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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.compress.PacketCompressor;
import dev.warp.protocol.packet.PacketDirection;

import java.util.Random;
import java.util.zip.DataFormatException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FrameForwarder")
class FrameForwarderTest {

  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;

  @Nested
  @DisplayName("verbatim forwarding")
  class Verbatim {

    @Test
    @DisplayName("should forward compressed frames byte for byte between equal thresholds")
    void equalThresholds() {
      Sink sink = Sink.compressed(256, new RefusingCompressor());
      ByteBuf frame = Frames.compressed(packet(1000), 6);
      byte[] wire = ByteBufUtil.getBytes(frame);

      sink.forwarder.forward(frame, source(256), null);

      assertArrayEquals(wire, sink.output());
    }

    @Test
    @DisplayName("should forward uncompressed frames below the threshold verbatim")
    void smallUncompressed() {
      Sink sink = Sink.compressed(256, new RefusingCompressor());
      ByteBuf frame = Frames.uncompressed(packet(200));
      byte[] wire = ByteBufUtil.getBytes(frame);

      sink.forwarder.forward(frame, source(1024), null);

      assertArrayEquals(wire, sink.output());
    }

    @Test
    @DisplayName("should forward frames between two uncompressed connections verbatim")
    void bothUncompressed() {
      Sink sink = Sink.uncompressed();
      ByteBuf frame = Frames.plain(packet(700));
      byte[] wire = ByteBufUtil.getBytes(frame);

      sink.forwarder.forward(frame, null, null);

      assertArrayEquals(wire, sink.output());
    }
  }

  @Nested
  @DisplayName("re-encoding")
  class ReEncoding {

    @Test
    @DisplayName("should send below-threshold compressed frames uncompressed, never re-deflated")
    void belowThreshold() {
      Sink sink = Sink.compressed(256, new RefusingCompressor());
      byte[] packet = packet(100);

      sink.forwarder.forward(Frames.compressed(packet, 6), source(64), null);

      byte[] out = sink.output();
      assertArrayEquals(packet, decodeCompressed(out, 256));
      assertEquals(0, dataLength(out), "re-encoded uncompressed, never re-deflated");
    }

    @Test
    @DisplayName("should compress uncompressed frames at the threshold, which Krypton rejects")
    void compressesLargeUncompressed() {
      Sink sink = Sink.compressed(256, new JavaCompressor(6));
      byte[] packet = packet(500);

      sink.forwarder.forward(Frames.uncompressed(packet), source(1024), null);

      byte[] out = sink.output();
      assertArrayEquals(packet, decodeCompressed(out, 256));
      assertEquals(packet.length, dataLength(out));
    }

    @Test
    @DisplayName("should strip compression for an uncompressed connection")
    void toUncompressed() {
      Sink sink = Sink.uncompressed();
      byte[] packet = packet(2000);

      sink.forwarder.forward(Frames.compressed(packet, 6), source(256), null);

      ByteBuf out = Unpooled.wrappedBuffer(sink.output());
      assertEquals(packet.length, VarInt.read(out));
      assertArrayEquals(packet, ByteBufUtil.getBytes(out));
    }

    @Test
    @DisplayName("should compress for a compressed connection when the source is uncompressed")
    void fromUncompressed() {
      Sink sink = Sink.compressed(256, new JavaCompressor(6));
      byte[] packet = packet(2000);

      sink.forwarder.forward(Frames.plain(packet), null, null);

      byte[] out = sink.output();
      assertArrayEquals(packet, decodeCompressed(out, 256));
      assertEquals(packet.length, dataLength(out));
    }

    @Test
    @DisplayName("should reuse bytes the source already inflated instead of inflating again")
    void reusesInflatedBytes() {
      Sink sink = Sink.compressed(256, new RefusingCompressor());
      byte[] packet = packet(100);
      FrameDecompressor refusing =
          new FrameDecompressor(
              64, false, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, new RefusingCompressor());

      sink.forwarder.forward(
          Frames.compressed(packet, 6), refusing, Unpooled.wrappedBuffer(packet.clone()));

      assertArrayEquals(packet, decodeCompressed(sink.output(), 256));
    }

    @Test
    @DisplayName("should re-encode every frame when verbatim forwarding is disabled")
    void verbatimDisabled() {
      CountingCompressor deflater = new CountingCompressor();
      Sink sink = Sink.compressed(256, deflater);
      sink.forwarder.setVerbatimEnabled(false);
      byte[] packet = packet(1000);

      sink.forwarder.forward(Frames.compressed(packet, 6), source(256), null);

      assertArrayEquals(packet, decodeCompressed(sink.output(), 256));
      assertEquals(1, deflater.deflations);
    }
  }

  @Test
  @DisplayName("should release the frame when the connection is closed")
  void releasesWhenClosed() {
    Sink sink = Sink.uncompressed();
    sink.channel.close().syncUninterruptibly();
    ByteBuf frame = Frames.plain(packet(10));

    sink.forwarder.forward(frame, null, null);

    assertEquals(0, frame.refCnt());
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  /** A connection receiving forwarded frames, with the encoders the proxy installs. */
  private static final class Sink {
    final EmbeddedChannel channel;
    final FrameForwarder forwarder;

    private Sink(EmbeddedChannel channel) {
      this.channel = channel;
      this.forwarder = new FrameForwarder(channel);
    }

    static Sink compressed(int threshold, PacketCompressor deflater) {
      Sink sink =
          new Sink(
              new EmbeddedChannel(
                  new CompressionEncoder(threshold, deflater),
                  new MinecraftEncoder(PacketDirection.CLIENTBOUND, MODERN, ProtocolState.PLAY)));
      sink.forwarder.compressionEnabled(threshold);
      return sink;
    }

    static Sink uncompressed() {
      return new Sink(
          new EmbeddedChannel(
              FrameEncoder.INSTANCE,
              new MinecraftEncoder(PacketDirection.CLIENTBOUND, MODERN, ProtocolState.PLAY)));
    }

    /** Flushes and returns everything written to the socket, as one byte array. */
    byte[] output() {
      channel.flushOutbound();
      ByteBuf all = Unpooled.buffer();
      for (ByteBuf out = channel.readOutbound(); out != null; out = channel.readOutbound()) {
        all.writeBytes(out);
        out.release();
      }
      assertFalse(channel.finish());
      return Frames.drain(all);
    }
  }

  private static FrameDecompressor source(int threshold) {
    return new FrameDecompressor(
        threshold, false, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, new JavaCompressor(6));
  }

  private static byte[] packet(int size) {
    byte[] payload = new byte[size - 1];
    new Random(size).nextBytes(payload);
    return Frames.packet(0x55, payload);
  }

  /** Decodes one frame of a compressed connection as a validating (server) peer would. */
  private static byte[] decodeCompressed(byte[] wire, int threshold) {
    EmbeddedChannel peer =
        new EmbeddedChannel(new CompressionDecoder(threshold, new JavaCompressor(6)));
    peer.writeInbound(Unpooled.wrappedBuffer(wire));
    byte[] packet = Frames.drain(peer.readInbound());
    assertFalse(peer.finish());
    return packet;
  }

  private static int dataLength(byte[] wire) {
    ByteBuf buf = Unpooled.wrappedBuffer(wire);
    VarInt.read(buf);
    return VarInt.read(buf);
  }

  /** Fails the test if the encoder compresses anything. */
  private static final class RefusingCompressor implements PacketCompressor {
    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize) {
      throw new AssertionError("encoder must not inflate");
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) {
      throw new AssertionError("frame must not be re-compressed");
    }

    @Override
    public void close() {}
  }

  /** Counts deflations, delegating to the JDK's zlib. */
  private static final class CountingCompressor implements PacketCompressor {
    private final JavaCompressor delegate = new JavaCompressor(6);
    private int deflations;

    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize)
        throws DataFormatException {
      delegate.inflate(source, destination, uncompressedSize);
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
      deflations++;
      delegate.deflate(source, destination);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
