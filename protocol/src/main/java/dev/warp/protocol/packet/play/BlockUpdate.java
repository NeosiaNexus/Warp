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

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * One block set in the client's world (Block Update, "Block Change" before 1.20.2). Encode-only:
 * the anti-bot's virtual world builds its platform with it; a server's stay opaque.
 *
 * <p>Layouts: 1.7 (int x, byte y, int z, block id, metadata), 1.8 to 1.12.2 (packed position,
 * {@code id << 4 | metadata}), 1.13+ (packed position, block state id). The position packing moved
 * its y bits in 1.14.
 *
 * @param x the block x
 * @param y the block y
 * @param z the block z
 * @param block the block: a legacy block id before 1.13, a block state id from 1.13
 */
public record BlockUpdate(int x, int y, int z, int block) implements PlayPacket {

  /** The stone block: legacy id 1 and, since 1.13, block state 1 in every version. */
  public static final int STONE = 1;

  /** Codec for the clientbound block update. */
  public static final PacketCodec<BlockUpdate> CODEC =
      new PacketCodec<>() {
        @Override
        public BlockUpdate decode(ByteBuf buf, ProtocolVersion version) {
          throw new UnsupportedOperationException("encode-only");
        }

        @Override
        public void encode(BlockUpdate packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
            buf.writeInt(packet.x());
            buf.writeByte(packet.y());
            buf.writeInt(packet.z());
            VarInt.write(buf, packet.block());
            buf.writeByte(0); // metadata
            return;
          }
          buf.writeLong(packPosition(packet.x(), packet.y(), packet.z(), version));
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_13)) {
            VarInt.write(buf, packet.block() << 4);
          } else {
            VarInt.write(buf, packet.block());
          }
        }
      };

  /**
   * Packs a block position as the protocol does from 1.8: 26 bits of x, 12 of y and 26 of z, the y
   * bits in the middle until 1.13.2 and at the end from 1.14.
   *
   * @param x the block x
   * @param y the block y
   * @param z the block z
   * @param version the client's version
   * @return the packed position
   */
  public static long packPosition(int x, int y, int z, ProtocolVersion version) {
    long xBits = (x & 0x3FFFFFFL) << 38;
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_14)) {
      return xBits | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }
    return xBits | ((y & 0xFFFL) << 26) | (z & 0x3FFFFFFL);
  }
}
