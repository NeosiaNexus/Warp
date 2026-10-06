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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.compress.JavaCompressor;
import dev.warp.protocol.compress.PacketCompressor;
import dev.warp.protocol.compress.TrackingCompressor;

import java.util.Random;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CompressionEncoder")
class CompressionEncoderTest {

  private static final int THRESHOLD = 256;

  // ---------------------------------------------------------------------------
  // Below threshold (uncompressed)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("below threshold")
  class BelowThreshold {

    @Test
    @DisplayName("should produce uncompressed format with Data Length = 0")
    void uncompressedFormat() {
      EmbeddedChannel ch = encoderChannel();
      byte[] payload = new byte[100]; // < 256 threshold

      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      // [3-byte VarInt: Packet Length][0x00 = Data Length][payload]
      int packetLength = VarInt.read(out);
      assertEquals(1 + payload.length, packetLength); // 1 for 0x00 + payload

      int dataLength = VarInt.read(out);
      assertEquals(0, dataLength);

      byte[] actualPayload = new byte[out.readableBytes()];
      out.readBytes(actualPayload);
      assertArrayEquals(payload, actualPayload);

      out.release();
      ch.finish();
    }

    @Test
    @DisplayName("should encode small packets without compression")
    void smallPacket() {
      EmbeddedChannel ch = encoderChannel();
      byte[] payload = {0x01, 0x02, 0x03};

      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      int packetLength = VarInt.read(out);
      assertEquals(4, packetLength); // 1 + 3

      assertEquals(0, VarInt.read(out)); // Data Length = 0

      byte[] actual = new byte[out.readableBytes()];
      out.readBytes(actual);
      assertArrayEquals(payload, actual);

      out.release();
      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Above threshold (compressed)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("above threshold")
  class AboveThreshold {

    @Test
    @DisplayName("should produce compressed format with correct Data Length")
    void compressedFormat() {
      EmbeddedChannel ch = encoderChannel();
      byte[] payload = new byte[500]; // > 256 threshold, all zeros for good compression

      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      // [3-byte VarInt: Packet Length][VarInt: Data Length][compressed data]
      int packetLength = VarInt.read(out);
      assertTrue(packetLength > 0);

      int dataLength = VarInt.read(out);
      assertEquals(500, dataLength); // = original uncompressed size

      int compressedSize = out.readableBytes();
      assertTrue(compressedSize > 0);
      assertTrue(compressedSize < 500, "Zeros should compress well");

      // Verify Packet Length = VarInt.size(dataLength) + compressedSize
      assertEquals(VarInt.size(dataLength) + compressedSize, packetLength);

      out.release();
      ch.finish();
    }

    @Test
    @DisplayName("should compress random data (even if poorly)")
    void randomData() {
      EmbeddedChannel ch = encoderChannel();
      byte[] payload = new byte[500];
      new Random(42).nextBytes(payload);

      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      int packetLength = VarInt.read(out);
      assertTrue(packetLength > 0);

      int dataLength = VarInt.read(out);
      assertEquals(500, dataLength);

      out.release();
      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // End-to-end roundtrip
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("roundtrip")
  class Roundtrip {

    @Test
    @DisplayName("should survive encode → frame-decode → decompress roundtrip (below threshold)")
    void belowThresholdRoundtrip() {
      byte[] payload = {0x0A, 0x0B, 0x0C, 0x0D};

      EmbeddedChannel encoder =
          new EmbeddedChannel(
              new CompressionEncoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));
      EmbeddedChannel decoder =
          new EmbeddedChannel(
              new FrameDecoder(),
              new CompressionDecoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));

      assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf wire = encoder.readOutbound();

      assertTrue(decoder.writeInbound(wire));
      ByteBuf result = decoder.readInbound();
      assertNotNull(result);

      byte[] actual = new byte[result.readableBytes()];
      result.readBytes(actual);
      assertArrayEquals(payload, actual);

      result.release();
      encoder.finish();
      decoder.finish();
    }

    @Test
    @DisplayName("should survive encode → frame-decode → decompress roundtrip (above threshold)")
    void aboveThresholdRoundtrip() {
      byte[] payload = new byte[1000];
      new Random(42).nextBytes(payload);

      EmbeddedChannel encoder =
          new EmbeddedChannel(
              new CompressionEncoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));
      EmbeddedChannel decoder =
          new EmbeddedChannel(
              new FrameDecoder(),
              new CompressionDecoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));

      assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf wire = encoder.readOutbound();

      assertTrue(decoder.writeInbound(wire));
      ByteBuf result = decoder.readInbound();
      assertNotNull(result);

      byte[] actual = new byte[result.readableBytes()];
      result.readBytes(actual);
      assertArrayEquals(payload, actual);

      result.release();
      encoder.finish();
      decoder.finish();
    }

    @Test
    @DisplayName("should handle multiple packets in sequence")
    void multiplePackets() {
      EmbeddedChannel encoder =
          new EmbeddedChannel(
              new CompressionEncoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));
      EmbeddedChannel decoder =
          new EmbeddedChannel(
              new FrameDecoder(),
              new CompressionDecoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));

      byte[] small = {0x01, 0x02, 0x03}; // below threshold
      byte[] large = new byte[500]; // above threshold
      new Random(99).nextBytes(large);

      // Encode both
      assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(small)));
      assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(large.clone())));

      // Decode both
      ByteBuf wire1 = encoder.readOutbound();
      ByteBuf wire2 = encoder.readOutbound();
      assertTrue(decoder.writeInbound(wire1));
      assertTrue(decoder.writeInbound(wire2));

      // Verify both
      ByteBuf result1 = decoder.readInbound();
      assertNotNull(result1);
      byte[] actual1 = new byte[result1.readableBytes()];
      result1.readBytes(actual1);
      assertArrayEquals(small, actual1);
      result1.release();

      ByteBuf result2 = decoder.readInbound();
      assertNotNull(result2);
      byte[] actual2 = new byte[result2.readableBytes()];
      result2.readBytes(actual2);
      assertArrayEquals(large, actual2);
      result2.release();

      encoder.finish();
      decoder.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Threshold update
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("threshold update")
  class ThresholdUpdate {

    @Test
    @DisplayName("should respect updated threshold")
    void dynamicThreshold() {
      JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
      CompressionEncoder encoder = new CompressionEncoder(THRESHOLD, compressor);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      // 300 bytes > threshold 256 → compressed
      byte[] payload = new byte[300];
      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf out1 = ch.readOutbound();
      VarInt.read(out1); // packet length
      int dataLength1 = VarInt.read(out1);
      assertEquals(300, dataLength1); // compressed → Data Length = original size
      out1.release();

      // Raise threshold to 500 → 300 bytes is now below threshold → uncompressed
      encoder.setThreshold(500);
      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(new byte[300])));
      ByteBuf out2 = ch.readOutbound();
      VarInt.read(out2); // packet length
      int dataLength2 = VarInt.read(out2);
      assertEquals(0, dataLength2); // uncompressed → Data Length = 0
      out2.release();

      ch.finish();
    }

    @Test
    @DisplayName("should send uncompressed when threshold is negative (compression disabled)")
    void negativeThresholdDisablesCompression() {
      JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
      CompressionEncoder encoder = new CompressionEncoder(-1, compressor);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      // 500 bytes with threshold -1 → always uncompressed
      byte[] payload = new byte[500];
      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf out = ch.readOutbound();
      VarInt.read(out); // packet length
      int dataLength = VarInt.read(out);
      assertEquals(0, dataLength); // must be uncompressed
      out.release();

      ch.finish();
    }

    @Test
    @DisplayName("should compress everything when threshold is zero")
    void zeroThresholdCompressesAll() {
      JavaCompressor compressor = new JavaCompressor(Deflater.DEFAULT_COMPRESSION);
      CompressionEncoder encoder = new CompressionEncoder(0, compressor);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      // Even a tiny 3-byte payload should be compressed with threshold=0
      byte[] payload = {0x01, 0x02, 0x03};
      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf out = ch.readOutbound();
      VarInt.read(out); // packet length
      int dataLength = VarInt.read(out);
      assertEquals(3, dataLength); // compressed → Data Length = original size
      out.release();

      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Limits and lifecycle
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("limits and lifecycle")
  class LimitsAndLifecycle {

    @Test
    @DisplayName("should send an empty packet uncompressed even when everything is compressed")
    void emptyPacketUncompressed() {
      EmbeddedChannel ch =
          new EmbeddedChannel(
              new CompressionEncoder(0, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));

      assertTrue(ch.writeOutbound(Unpooled.EMPTY_BUFFER));

      // Packet Length 1 as a padded 3-byte VarInt, then Data Length 0 and no packet bytes.
      ByteBuf out = ch.readOutbound();
      assertArrayEquals(
          new byte[] {(byte) 0x81, (byte) 0x80, 0x00, 0x00}, ByteBufUtil.getBytes(out));
      out.release();
      ch.finish();
    }

    @Test
    @DisplayName("should accept a compressed frame of exactly MAX_21_BIT bytes")
    void maximumCompressedFrame() {
      // Data Length (one byte for a one-byte packet) plus the compressed bytes fill the frame.
      EmbeddedChannel ch =
          new EmbeddedChannel(
              new CompressionEncoder(0, new FixedOutputCompressor(VarInt.MAX_21_BIT - 1)));

      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(new byte[] {0x2A})));

      ByteBuf out = ch.readOutbound();
      assertEquals(VarInt.MAX_21_BIT, VarInt.read(out));
      assertEquals(VarInt.MAX_21_BIT, out.readableBytes());
      out.release();
      ch.finish();
    }

    @Test
    @DisplayName("should reject a compressed frame longer than MAX_21_BIT bytes")
    void oversizedCompressedFrame() {
      EmbeddedChannel ch =
          new EmbeddedChannel(
              new CompressionEncoder(0, new FixedOutputCompressor(VarInt.MAX_21_BIT)));

      EncoderException e =
          assertThrows(
              EncoderException.class,
              () -> ch.writeOutbound(Unpooled.wrappedBuffer(new byte[] {0x2A})));
      assertEquals(
          "Compressed frame exceeds maximum length of " + VarInt.MAX_21_BIT + " bytes",
          e.getMessage());
      ch.finish();
    }

    @Test
    @DisplayName("should close its compressor when removed from the pipeline")
    void closesCompressorOnRemoval() {
      TrackingCompressor compressor = new TrackingCompressor();
      CompressionEncoder encoder = new CompressionEncoder(THRESHOLD, compressor);
      EmbeddedChannel ch = new EmbeddedChannel(encoder);

      ch.pipeline().remove(encoder);

      assertEquals(1, compressor.closes());
      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static EmbeddedChannel encoderChannel() {
    return new EmbeddedChannel(
        new CompressionEncoder(THRESHOLD, new JavaCompressor(Deflater.DEFAULT_COMPRESSION)));
  }

  /** "Compresses" any packet into the given number of zero bytes. */
  private static final class FixedOutputCompressor implements PacketCompressor {

    private final int outputLength;

    FixedOutputCompressor(int outputLength) {
      this.outputLength = outputLength;
    }

    @Override
    public void inflate(ByteBuf source, ByteBuf destination, int uncompressedSize) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deflate(ByteBuf source, ByteBuf destination) {
      source.skipBytes(source.readableBytes());
      destination.writeZero(outputLength);
    }

    @Override
    public void close() {}
  }
}
