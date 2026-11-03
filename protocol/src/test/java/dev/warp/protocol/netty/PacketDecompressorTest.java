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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.warp.protocol.compress.FrameDecompressor;
import dev.warp.protocol.compress.TrackingCompressor;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PacketDecompressor")
class PacketDecompressorTest {

  private static final int THRESHOLD = 256;

  @Nested
  @DisplayName("ownership of the decompressor")
  class Ownership {

    @Test
    @DisplayName("should start without a decompressor while the connection is uncompressed")
    void startsUncompressed() {
      PacketDecompressor handler = new PacketDecompressor(null);
      EmbeddedChannel ch = new EmbeddedChannel(handler);

      ch.pipeline().remove(handler);

      assertNull(handler.decompressor());
      assertFalse(ch.finish());
    }

    @Test
    @DisplayName("should use the new decompressor and close the previous one when re-enabled")
    void replacesAndClosesThePrevious() {
      TrackingCompressor first = new TrackingCompressor();
      TrackingCompressor second = new TrackingCompressor();
      PacketDecompressor handler = new PacketDecompressor(decompressor(first));
      FrameDecompressor next = decompressor(second);

      handler.enableCompression(next);

      assertSame(next, handler.decompressor());
      assertEquals(1, first.closes());
      assertEquals(0, second.closes());
    }

    @Test
    @DisplayName("should close its decompressor once when removed from the pipeline, and drop it")
    void closesOnRemoval() {
      TrackingCompressor compressor = new TrackingCompressor();
      PacketDecompressor handler = new PacketDecompressor(null);
      EmbeddedChannel ch = new EmbeddedChannel(handler);
      handler.enableCompression(decompressor(compressor));

      ch.pipeline().remove(handler);

      assertEquals(1, compressor.closes());
      assertNull(handler.decompressor());
      assertFalse(ch.finish());
    }
  }

  private static FrameDecompressor decompressor(TrackingCompressor compressor) {
    return new FrameDecompressor(
        THRESHOLD, true, FrameDecompressor.DEFAULT_MAX_UNCOMPRESSED_SIZE, compressor);
  }
}
