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
package dev.warp.protocol.packet.play;

import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Field readers shared by the tab list packets ({@link PlayerInfo}, {@link PlayerInfoUpdate},
 * {@link PlayerInfoRemove}). They skip the fields the proxy never reads, and read and write the
 * UUID lists it keeps.
 */
final class TabListEntries {

  /** Largest player public key vanilla accepts (1.19 to 1.20.1). */
  private static final int MAX_PUBLIC_KEY_SIZE = 512;

  /** Largest player public key signature vanilla accepts. */
  private static final int MAX_KEY_SIGNATURE_SIZE = 4096;

  private TabListEntries() {}

  /** Reads an entry count, rejecting one the remaining bytes cannot hold. */
  static int readCount(ByteBuf buf, int minEntrySize) {
    return VarInt.readCount(buf, Integer.MAX_VALUE, minEntrySize, "tab list entry");
  }

  /** Skips a game profile's properties: a count, then name, value and optional signature. */
  static void skipProperties(ByteBuf buf) {
    int count = readCount(buf, 3);
    for (int i = 0; i < count; i++) {
      McString.skip(buf);
      McString.skip(buf);
      skipOptionalString(buf);
    }
  }

  /** Skips a boolean-prefixed optional string (a display name, a signature). */
  static void skipOptionalString(ByteBuf buf) {
    if (buf.readBoolean()) {
      McString.skip(buf);
    }
  }

  /** Skips a player public key: expiry, key and signature (1.19 to 1.20.1). */
  static void skipPublicKey(ByteBuf buf) {
    buf.skipBytes(Long.BYTES);
    skipByteArray(buf, MAX_PUBLIC_KEY_SIZE);
    skipByteArray(buf, MAX_KEY_SIGNATURE_SIZE);
  }

  private static void skipByteArray(ByteBuf buf, int maxSize) {
    int length = VarInt.read(buf);
    if (length < 0 || length > maxSize) {
      throw new DecoderException("Invalid byte array length: " + length);
    }
    buf.skipBytes(length);
  }

  /** Reads a UUID list: a VarInt count, then each UUID. */
  static List<UUID> readUuids(ByteBuf buf) {
    int count = readCount(buf, McUuid.ENCODED_SIZE);
    UUID[] uuids = new UUID[count];
    for (int i = 0; i < count; i++) {
      uuids[i] = McUuid.read(buf);
    }
    return List.of(uuids);
  }

  /** Writes a UUID list: a VarInt count, then each UUID. */
  static void writeUuids(ByteBuf buf, Collection<UUID> uuids) {
    VarInt.write(buf, uuids.size());
    for (UUID uuid : uuids) {
      McUuid.write(buf, uuid);
    }
  }
}
