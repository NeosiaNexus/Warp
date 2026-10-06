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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.netty.handler.codec.DecoderException;
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
    @DisplayName("should re-frame frames between uncompressed connections when verbatim is off")
    void reFramesWhenVerbatimDisabled() {
      Sink sink = Sink.uncompressed();
      sink.forwarder.setVerbatimEnabled(false);
      // Length 3 as a padded 3-byte VarInt: the encoder writes it in one byte.
      ByteBuf frame =
          Unpooled.wrappedBuffer(new byte[] {(byte) 0x83, (byte) 0x80, 0x00, 0x55, 1, 2});

      sink.forwarder.forward(frame, null, null);

      assertArrayEquals(new byte[] {3, 0x55, 1, 2}, sink.output());
    }

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

  @Nested
  @DisplayName("frames the source already inflated")
  class InflatedBySource {

    @Test
    @DisplayName("should forward the frame verbatim when it is canonical here, dropping the packet")
    void verbatimWhenCanonical() {
      Sink sink = Sink.compressed(256, new RefusingCompressor());
      // Compressible, so the frame's length (a few bytes of zlib) is far below the threshold.
      byte[] packet = Frames.packet(0x55, new byte[999]);
      ByteBuf frame = Frames.compressed(packet, 6);
      byte[] wire = ByteBufUtil.getBytes(frame);
      ByteBuf inflated = Unpooled.wrappedBuffer(packet.clone());

      sink.forwarder.forward(frame, source(256), inflated);

      assertArrayEquals(wire, sink.output());
      assertEquals(0, inflated.refCnt());
    }

    @Test
    @DisplayName("should re-encode the packet when the frame is below this connection's threshold")
    void reEncodedBelowThreshold() {
      Sink sink = Sink.compressed(2048, new RefusingCompressor());
      byte[] packet = Frames.packet(0x55, new byte[999]);
      ByteBuf frame = Frames.compressed(packet, 6);

      sink.forwarder.forward(frame, source(256), Unpooled.wrappedBuffer(packet.clone()));

      byte[] out = sink.output();
      assertArrayEquals(packet, decodeCompressed(out, 2048));
      assertEquals(0, dataLength(out), "sent uncompressed below the threshold");
      assertEquals(0, frame.refCnt());
    }

    @Test
    @DisplayName("should re-encode the packet when verbatim forwarding is disabled")
    void reEncodedWhenVerbatimDisabled() {
      CountingCompressor deflater = new CountingCompressor();
      Sink sink = Sink.compressed(256, deflater);
      sink.forwarder.setVerbatimEnabled(false);
      byte[] packet = Frames.packet(0x55, new byte[999]);

      sink.forwarder.forward(
          Frames.compressed(packet, 6), source(256), Unpooled.wrappedBuffer(packet.clone()));

      assertArrayEquals(packet, decodeCompressed(sink.output(), 256));
      assertEquals(1, deflater.deflations);
    }

    @Test
    @DisplayName("should re-encode the packet for an uncompressed connection")
    void reEncodedForUncompressed() {
      Sink sink = Sink.uncompressed();
      byte[] packet = Frames.packet(0x55, new byte[999]);

      sink.forwarder.forward(
          Frames.compressed(packet, 6), source(256), Unpooled.wrappedBuffer(packet.clone()));

      ByteBuf out = Unpooled.wrappedBuffer(sink.output());
      assertEquals(packet.length, VarInt.read(out));
      assertArrayEquals(packet, ByteBufUtil.getBytes(out));
    }

    @Test
    @DisplayName("should never write a frame of an uncompressed source to a compressed connection")
    void reEncodedFromUncompressedSource() {
      Sink sink = Sink.compressed(256, new RefusingCompressor());
      // Packet id 0 reads as "Data Length 0" in the compressed format: written as it is, the
      // frame would be valid there, and the peer would lose the id.
      byte[] packet = Frames.packet(0x00, new byte[99]);

      sink.forwarder.forward(Frames.plain(packet), null, Unpooled.wrappedBuffer(packet.clone()));

      byte[] out = sink.output();
      assertArrayEquals(packet, decodeCompressed(out, 256));
      assertEquals(0, dataLength(out));
    }

    @Test
    @DisplayName("should release both the frame and the packet when the connection is closed")
    void releasesBothWhenClosed() {
      Sink sink = Sink.compressed(256, new RefusingCompressor());
      sink.channel.close().syncUninterruptibly();
      byte[] packet = packet(300);
      ByteBuf frame = Frames.compressed(packet, 6);
      ByteBuf inflated = Unpooled.wrappedBuffer(packet.clone());

      sink.forwarder.forward(frame, source(256), inflated);

      assertEquals(0, frame.refCnt());
      assertEquals(0, inflated.refCnt());
    }
  }

  @Nested
  @DisplayName("threshold 0: every packet compressed")
  class ThresholdZero {

    @Test
    @DisplayName("should forward compressed frames verbatim")
    void compressedVerbatim() {
      Sink sink = Sink.compressed(0, new RefusingCompressor());
      ByteBuf frame = Frames.compressed(packet(100), 6);
      byte[] wire = ByteBufUtil.getBytes(frame);

      sink.forwarder.forward(frame, source(0), null);

      assertArrayEquals(wire, sink.output());
    }

    @Test
    @DisplayName("should forward frames its source inflated verbatim")
    void inflatedVerbatim() {
      Sink sink = Sink.compressed(0, new RefusingCompressor());
      byte[] packet = packet(100);
      ByteBuf frame = Frames.compressed(packet, 6);
      byte[] wire = ByteBufUtil.getBytes(frame);

      sink.forwarder.forward(frame, source(0), Unpooled.wrappedBuffer(packet.clone()));

      assertArrayEquals(wire, sink.output());
    }

    @Test
    @DisplayName("should compress the frames of an uncompressed source")
    void compressesUncompressedSource() {
      CountingCompressor deflater = new CountingCompressor();
      Sink sink = Sink.compressed(0, deflater);
      byte[] packet = packet(100);

      sink.forwarder.forward(Frames.plain(packet), null, null);

      byte[] out = sink.output();
      assertArrayEquals(packet, decodeCompressed(out, 0));
      assertEquals(packet.length, dataLength(out));
      assertEquals(1, deflater.deflations);
    }
  }

  @Nested
  @DisplayName("canonical frames")
  class CanonicalFrames {

    @Test
    @DisplayName("should take uncompressed packets only below the threshold")
    void uncompressedBelowThreshold() {
      FrameForwarder forwarder = new FrameForwarder(new EmbeddedChannel());
      forwarder.compressionEnabled(256);

      assertTrue(forwarder.accepts(0, 255));
      assertFalse(forwarder.accepts(0, 256));
    }

    @Test
    @DisplayName("should take compressed packets only from the threshold up")
    void compressedFromThreshold() {
      FrameForwarder forwarder = new FrameForwarder(new EmbeddedChannel());
      forwarder.compressionEnabled(256);

      assertTrue(forwarder.accepts(256, 20));
      assertFalse(forwarder.accepts(255, 20));
    }
  }

  @Test
  @DisplayName("should validate a frame it re-encodes against its source's threshold")
  void validatesReEncodedFrames() {
    Sink sink = Sink.compressed(1024, new RefusingCompressor());
    FrameDecompressor validating =
        new FrameDecompressor(
            256, true, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, new JavaCompressor(6));
    ByteBuf frame = Frames.compressed(packet(100), 6);

    assertThrows(DecoderException.class, () -> sink.forwarder.forward(frame, validating, null));
    assertEquals(0, frame.refCnt());
    sink.channel.finishAndReleaseAll();
  }

  @Test
  @DisplayName("should refuse to forward verbatim to a connection without framing encoder")
  void requiresFramingEncoder() {
    EmbeddedChannel channel = new EmbeddedChannel();
    FrameForwarder forwarder = new FrameForwarder(channel);
    ByteBuf frame = Frames.plain(packet(10));

    assertThrows(IllegalStateException.class, () -> forwarder.forward(frame, null, null));
    frame.release();
    channel.finishAndReleaseAll();
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
