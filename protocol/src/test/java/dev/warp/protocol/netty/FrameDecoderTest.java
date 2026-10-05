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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.codec.VarInt;

import java.util.Arrays;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FrameDecoder")
class FrameDecoderTest {

  // ---------------------------------------------------------------------------
  // Frame splitting
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("frame splitting")
  class FrameSplitting {

    @Test
    @DisplayName("should decode frame with 1-byte VarInt length")
    void singleByteLength() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      byte[] payload = {0x01, 0x02, 0x03, 0x04, 0x05};
      ByteBuf in = Unpooled.buffer();
      in.writeByte(payload.length);
      in.writeBytes(payload);

      assertTrue(ch.writeInbound(in));
      assertFrame(payload, ch.readInbound());
      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode frame with 2-byte VarInt length")
    void twoByteLength() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      byte[] payload = new byte[200];
      Arrays.fill(payload, (byte) 0xAB);
      ByteBuf in = Unpooled.buffer();
      VarInt.write(in, payload.length);
      in.writeBytes(payload);

      assertTrue(ch.writeInbound(in));
      assertFrame(payload, ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode frame with 3-byte VarInt length")
    void threeByteLength() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      int size = 100_000;
      ByteBuf in = Unpooled.buffer();
      VarInt.write(in, size);
      in.writeZero(size);

      assertTrue(ch.writeInbound(in));

      assertFrameLength(size, ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode multiple frames from a single TCP segment")
    void multipleFrames() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      byte[] p1 = {0x0A, 0x0B};
      byte[] p2 = {0x0C, 0x0D, 0x0E};

      ByteBuf in = Unpooled.buffer();
      in.writeByte(p1.length);
      in.writeBytes(p1);
      in.writeByte(p2.length);
      in.writeBytes(p2);

      assertTrue(ch.writeInbound(in));

      assertFrame(p1, ch.readInbound());
      assertFrame(p2, ch.readInbound());
      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should skip empty frames (length = 0)")
    void emptyFrame() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      byte[] payload = {(byte) 0xFF};
      ByteBuf in = Unpooled.buffer();
      in.writeByte(0); // empty frame — skipped
      in.writeByte(1);
      in.writeBytes(payload);

      assertTrue(ch.writeInbound(in));
      assertFrame(payload, ch.readInbound());
      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should decode maximum 3-byte VarInt length (2,097,151)")
    void maxThreeByteVarInt() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      int size = VarInt.MAX_21_BIT;
      ByteBuf in = Unpooled.buffer();
      VarInt.write(in, size);
      in.writeZero(size);

      assertTrue(ch.writeInbound(in));

      assertFrameLength(size, ch.readInbound());
      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Wire frame output
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("wire frame output")
  class WireFrameOutput {

    @Test
    @DisplayName("should emit complete frames positioned at their length prefix")
    void emitsCompleteFrames() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      ByteBuf in = Unpooled.buffer();
      in.writeByte(3).writeBytes(new byte[] {7, 8, 9});
      in.writeByte(1).writeByte(42);

      assertTrue(ch.writeInbound(in));

      ByteBuf first = ch.readInbound();
      assertEquals(0, first.readerIndex());
      assertArrayEquals(new byte[] {3, 7, 8, 9}, ByteBufUtil.getBytes(first));
      first.release();
      ByteBuf second = ch.readInbound();
      assertArrayEquals(new byte[] {1, 42}, ByteBufUtil.getBytes(second));
      second.release();
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should keep padded length prefixes byte for byte (Minestom, Velocity)")
    void keepsPaddedPrefix() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      // Length 5 written as a padded 3-byte VarInt, as Minestom and Velocity do.
      byte[] wire = {(byte) 0x85, (byte) 0x80, 0x00, 1, 2, 3, 4, 5};

      assertTrue(ch.writeInbound(Unpooled.wrappedBuffer(wire)));

      ByteBuf frame = ch.readInbound();
      assertArrayEquals(wire, ByteBufUtil.getBytes(frame));
      frame.release();
      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Branchless / safe path boundary
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("branchless path boundary")
  class BranchlessPathBoundary {

    @Test
    @DisplayName(
        "should use branchless path when exactly 4 bytes readable (1-byte VarInt + 3 payload)")
    void exactlyFourBytes() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      byte[] payload = {0x0A, 0x0B, 0x0C};
      ByteBuf in = Unpooled.buffer();
      in.writeByte(payload.length); // 1-byte VarInt = 3
      in.writeBytes(payload);
      // Total: 4 bytes → branchless path

      assertTrue(ch.writeInbound(in));
      assertFrame(payload, ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should use safe path when exactly 3 bytes readable (3-byte VarInt, 0 payload)")
    void exactlyThreeBytes() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      // 3-byte VarInt encoding of 0 (overlong): 0x80, 0x80, 0x00
      // This encodes value 0, and exactly 3 readable bytes → safe path
      ByteBuf in = Unpooled.buffer();
      in.writeByte(0x80);
      in.writeByte(0x80);
      in.writeByte(0x00);

      // VarInt decodes to 0 → empty frame → skipped
      assertFalse(ch.writeInbound(in));
      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should use safe path when exactly 3 bytes readable (1-byte VarInt + 2 payload)")
    void exactlyThreeBytesWithPayload() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      ByteBuf in = Unpooled.buffer();
      in.writeByte(2); // 1-byte VarInt = 2
      in.writeBytes(new byte[] {(byte) 0xDE, (byte) 0xAD});
      // Total 3 bytes → safe path (branchless requires ≥ 4)

      assertTrue(ch.writeInbound(in));
      assertFrameLength(2, ch.readInbound());
      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Partial reads (TCP segmentation)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("partial reads")
  class PartialReads {

    @Test
    @DisplayName("should handle VarInt split across two TCP segments")
    void splitVarInt() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());

      // First byte of 2-byte VarInt encoding of 128: 0x80
      ch.writeInbound(Unpooled.wrappedBuffer(new byte[] {(byte) 0x80}));
      assertNull(ch.readInbound());

      // Second byte + full payload
      ByteBuf part2 = Unpooled.buffer();
      part2.writeByte(0x01); // completes VarInt = 128
      part2.writeZero(128);
      assertTrue(ch.writeInbound(part2));

      assertFrameLength(128, ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should handle payload split across TCP segments")
    void splitPayload() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());

      // Length = 10 but only 5 payload bytes
      ByteBuf part1 = Unpooled.buffer();
      part1.writeByte(10);
      part1.writeZero(5);
      ch.writeInbound(part1);
      assertNull(ch.readInbound());

      // Remaining 5 bytes
      assertTrue(ch.writeInbound(Unpooled.wrappedBuffer(new byte[5])));

      assertFrameLength(10, ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should handle 3-byte VarInt split one byte at a time")
    void threeWaySplitVarInt() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      int length = 20_000; // requires 3-byte VarInt
      ByteBuf varint = Unpooled.buffer(3);
      VarInt.write(varint, length);

      // Send byte by byte
      ch.writeInbound(Unpooled.wrappedBuffer(new byte[] {varint.readByte()}));
      assertNull(ch.readInbound());

      ch.writeInbound(Unpooled.wrappedBuffer(new byte[] {varint.readByte()}));
      assertNull(ch.readInbound());

      // Third VarInt byte + full payload
      ByteBuf part3 = Unpooled.buffer();
      part3.writeByte(varint.readByte());
      part3.writeZero(length);
      assertTrue(ch.writeInbound(part3));

      varint.release();

      assertFrameLength(length, ch.readInbound());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should produce no output on empty input")
    void emptyInput() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      assertFalse(ch.writeInbound(Unpooled.EMPTY_BUFFER));
      assertNull(ch.readInbound());
      assertFalse(ch.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Error handling
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("error handling")
  class ErrorHandling {

    @Test
    @DisplayName("should reject VarInt exceeding 3 bytes")
    void varIntTooWide() {
      EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
      ByteBuf in = Unpooled.buffer();
      // Three bytes with continuation bits set → byte 4 needed → too wide
      in.writeByte(0x80);
      in.writeByte(0x80);
      in.writeByte(0x80);
      in.writeByte(0x01);

      assertThrows(DecoderException.class, () -> ch.writeInbound(in));
      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Roundtrip
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("roundtrip")
  class Roundtrip {

    @Test
    @DisplayName("should survive encode → decode roundtrip with FrameEncoder")
    void encodeDecodeRoundtrip() {
      byte[] payload = {0x01, 0x02, 0x03, 0x04, 0x05};
      EmbeddedChannel encoder = new EmbeddedChannel(FrameEncoder.INSTANCE);
      EmbeddedChannel decoder = new EmbeddedChannel(new FrameDecoder());

      assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf wire = encoder.readOutbound();

      assertTrue(decoder.writeInbound(wire));
      assertFrame(payload, decoder.readInbound());

      assertFalse(encoder.finish());
      assertFalse(decoder.finish());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Asserts a complete frame: its length prefix, then {@code expected} as the payload. */
  private static void assertFrame(byte[] expected, ByteBuf frame) {
    assertNotNull(frame);
    assertEquals(expected.length, VarInt.read(frame));
    byte[] bytes = new byte[frame.readableBytes()];
    frame.readBytes(bytes);
    assertArrayEquals(expected, bytes);
    frame.release();
  }

  /** Asserts a complete frame whose length prefix and payload are both {@code payloadLength}. */
  private static void assertFrameLength(int payloadLength, ByteBuf frame) {
    assertNotNull(frame);
    assertEquals(payloadLength, VarInt.read(frame));
    assertEquals(payloadLength, frame.readableBytes());
    frame.release();
  }
}
