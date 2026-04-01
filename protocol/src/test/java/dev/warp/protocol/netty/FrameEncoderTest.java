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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.codec.VarInt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FrameEncoder")
class FrameEncoderTest {

  // ---------------------------------------------------------------------------
  // Wire format
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("wire format")
  class WireFormat {

    @Test
    @DisplayName("should prepend 1-byte VarInt for small payloads (0–127 bytes)")
    void oneBytePrefix() {
      EmbeddedChannel ch = new EmbeddedChannel(FrameEncoder.INSTANCE);
      byte[] payload = {0x01, 0x02, 0x03};

      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      int length = VarInt.read(out);
      assertEquals(3, length);
      assertRemainingEquals(payload, out);

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should prepend 2-byte VarInt for medium payloads (128–16383 bytes)")
    void twoBytePrefix() {
      EmbeddedChannel ch = new EmbeddedChannel(FrameEncoder.INSTANCE);
      byte[] payload = new byte[200];

      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(payload)));
      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      int length = VarInt.read(out);
      assertEquals(200, length);
      assertEquals(200, out.readableBytes());
      out.release();

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should prepend 3-byte VarInt for large payloads (16384+ bytes)")
    void threeBytePrefix() {
      EmbeddedChannel ch = new EmbeddedChannel(FrameEncoder.INSTANCE);
      int size = 100_000;

      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(new byte[size])));
      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      int length = VarInt.read(out);
      assertEquals(size, length);
      assertEquals(size, out.readableBytes());
      out.release();

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should encode empty payload as VarInt(0)")
    void emptyPayload() {
      EmbeddedChannel ch = new EmbeddedChannel(FrameEncoder.INSTANCE);

      assertTrue(ch.writeOutbound(Unpooled.EMPTY_BUFFER));
      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      int length = VarInt.read(out);
      assertEquals(0, length);
      assertEquals(0, out.readableBytes());
      out.release();

      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should produce correct total size (VarInt prefix + payload)")
    void totalSize() {
      EmbeddedChannel ch = new EmbeddedChannel(FrameEncoder.INSTANCE);
      int payloadSize = 300; // 2-byte VarInt

      assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(new byte[payloadSize])));
      ByteBuf out = ch.readOutbound();
      assertNotNull(out);

      assertEquals(VarInt.size(payloadSize) + payloadSize, out.readableBytes());
      out.release();

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
    @DisplayName("should reject payload exceeding MAX_21_BIT")
    void oversizedPayload() {
      EmbeddedChannel ch = new EmbeddedChannel(FrameEncoder.INSTANCE);
      ByteBuf oversized = Unpooled.wrappedBuffer(new byte[VarInt.MAX_21_BIT + 1]);

      assertThrows(EncoderException.class, () -> ch.writeOutbound(oversized));
      ch.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Singleton
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("singleton")
  class Singleton {

    @Test
    @DisplayName("should be usable across multiple channels via INSTANCE")
    void sharedInstance() {
      EmbeddedChannel ch1 = new EmbeddedChannel(FrameEncoder.INSTANCE);
      EmbeddedChannel ch2 = new EmbeddedChannel(FrameEncoder.INSTANCE);

      byte[] payload = {0x42};

      assertTrue(ch1.writeOutbound(Unpooled.wrappedBuffer(payload)));
      assertTrue(ch2.writeOutbound(Unpooled.wrappedBuffer(payload)));

      ByteBuf out1 = ch1.readOutbound();
      ByteBuf out2 = ch2.readOutbound();
      assertNotNull(out1);
      assertNotNull(out2);
      assertEquals(out1.readableBytes(), out2.readableBytes());

      out1.release();
      out2.release();
      ch1.finish();
      ch2.finish();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void assertRemainingEquals(byte[] expected, ByteBuf buf) {
    byte[] actual = new byte[buf.readableBytes()];
    buf.readBytes(actual);
    assertArrayEquals(expected, actual);
    buf.release();
  }
}
