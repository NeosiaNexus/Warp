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
package dev.warp.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("McUuid codec")
class McUuidTest {

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("read")
  class Read {

    @Test
    @DisplayName("should decode UUID from two big-endian longs")
    void standardUuid() {
      UUID expected = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
      ByteBuf buf = Unpooled.buffer(McUuid.ENCODED_SIZE);
      try {
        buf.writeLong(expected.getMostSignificantBits());
        buf.writeLong(expected.getLeastSignificantBits());
        assertEquals(expected, McUuid.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should decode all-zeros UUID (nil UUID)")
    void allZeros() {
      ByteBuf buf = Unpooled.buffer(McUuid.ENCODED_SIZE);
      try {
        buf.writeLong(0L);
        buf.writeLong(0L);
        assertEquals(new UUID(0, 0), McUuid.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should decode all-ones UUID")
    void allOnes() {
      ByteBuf buf = Unpooled.buffer(McUuid.ENCODED_SIZE);
      try {
        buf.writeLong(-1L);
        buf.writeLong(-1L);
        assertEquals(new UUID(-1, -1), McUuid.read(buf));
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should advance reader index by exactly 16 bytes")
    void advancesReaderIndex() {
      ByteBuf buf = Unpooled.buffer(McUuid.ENCODED_SIZE + 1);
      try {
        buf.writeLong(0L);
        buf.writeLong(0L);
        buf.writeByte(0xAA); // sentinel
        McUuid.read(buf);
        assertEquals(McUuid.ENCODED_SIZE, buf.readerIndex());
        assertEquals((byte) 0xAA, buf.readByte());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should throw on insufficient readable bytes")
    void insufficientData() {
      ByteBuf buf = Unpooled.buffer(8);
      try {
        buf.writeLong(0L); // only 8 bytes, need 16
        assertThrows(IndexOutOfBoundsException.class, () -> McUuid.read(buf));
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("write")
  class Write {

    @Test
    @DisplayName("should encode UUID as two big-endian longs")
    void standardUuid() {
      UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
      ByteBuf buf = Unpooled.buffer(McUuid.ENCODED_SIZE);
      try {
        McUuid.write(buf, uuid);
        assertEquals(McUuid.ENCODED_SIZE, buf.readableBytes());
        assertEquals(uuid.getMostSignificantBits(), buf.readLong());
        assertEquals(uuid.getLeastSignificantBits(), buf.readLong());
      } finally {
        buf.release();
      }
    }

    @Test
    @DisplayName("should produce exactly 16 bytes")
    void fixedSize() {
      ByteBuf buf = Unpooled.buffer();
      try {
        McUuid.write(buf, new UUID(0, 0));
        assertEquals(McUuid.ENCODED_SIZE, buf.readableBytes());
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Roundtrip
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("roundtrip")
  class Roundtrip {

    @Test
    @DisplayName("should preserve UUID through write-then-read cycle")
    void writeRead() {
      UUID[] testValues = {
        UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
        new UUID(0, 0),
        new UUID(-1, -1),
        new UUID(Long.MAX_VALUE, Long.MIN_VALUE),
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
      };

      for (UUID uuid : testValues) {
        ByteBuf buf = Unpooled.buffer(McUuid.ENCODED_SIZE);
        try {
          McUuid.write(buf, uuid);
          assertEquals(uuid, McUuid.read(buf));
          assertEquals(0, buf.readableBytes());
        } finally {
          buf.release();
        }
      }
    }

    @Test
    @DisplayName("should handle consecutive UUIDs in the same buffer")
    void consecutive() {
      UUID first = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
      UUID second = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

      ByteBuf buf = Unpooled.buffer(McUuid.ENCODED_SIZE * 2);
      try {
        McUuid.write(buf, first);
        McUuid.write(buf, second);

        assertEquals(first, McUuid.read(buf));
        assertEquals(second, McUuid.read(buf));
        assertEquals(0, buf.readableBytes());
      } finally {
        buf.release();
      }
    }
  }
}
