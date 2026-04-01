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

import java.util.UUID;

import io.netty.buffer.ByteBuf;

/**
 * UUID codec for the Minecraft protocol.
 *
 * <p>UUIDs are encoded as two 64-bit big-endian integers: the most significant bits followed by the
 * least significant bits, totaling {@value #ENCODED_SIZE} bytes on the wire.
 *
 * <p>No validation is performed — all 128-bit patterns are valid UUIDs, and the fixed size
 * eliminates length-prefix concerns.
 */
public final class McUuid {

  /** Wire size of an encoded UUID in bytes (two 64-bit longs). */
  public static final int ENCODED_SIZE = Long.BYTES * 2;

  private McUuid() {}

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  /**
   * Reads a UUID from the buffer, advancing the reader index by {@value #ENCODED_SIZE} bytes.
   *
   * @param buf the buffer to read from
   * @return the decoded UUID
   * @throws IndexOutOfBoundsException if the buffer has fewer than {@value #ENCODED_SIZE} readable
   *     bytes
   */
  public static UUID read(ByteBuf buf) {
    long msb = buf.readLong();
    long lsb = buf.readLong();
    return new UUID(msb, lsb);
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  /**
   * Writes a UUID to the buffer, advancing the writer index by {@value #ENCODED_SIZE} bytes.
   *
   * @param buf the buffer to write to
   * @param uuid the UUID to encode
   */
  public static void write(ByteBuf buf, UUID uuid) {
    buf.writeLong(uuid.getMostSignificantBits());
    buf.writeLong(uuid.getLeastSignificantBits());
  }
}
