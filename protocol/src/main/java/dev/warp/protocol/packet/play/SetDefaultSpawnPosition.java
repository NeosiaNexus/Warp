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
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * The world's spawn point (Set Default Spawn Position), which a 1.19.3+ client waits for before it
 * leaves its loading screen. Encode-only: the anti-bot's virtual world sends it; a server's stay
 * opaque. Written from 1.8 (packed position, an angle from 1.17); 1.7 wrote three ints.
 *
 * @param x the block x
 * @param y the block y
 * @param z the block z
 */
public record SetDefaultSpawnPosition(int x, int y, int z) implements PlayPacket {

  /** Codec for the clientbound spawn position. */
  public static final PacketCodec<SetDefaultSpawnPosition> CODEC =
      new PacketCodec<>() {
        @Override
        public SetDefaultSpawnPosition decode(ByteBuf buf, ProtocolVersion version) {
          throw new UnsupportedOperationException("encode-only");
        }

        @Override
        public void encode(SetDefaultSpawnPosition packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
            buf.writeInt(packet.x());
            buf.writeInt(packet.y());
            buf.writeInt(packet.z());
            return;
          }
          buf.writeLong(BlockUpdate.packPosition(packet.x(), packet.y(), packet.z(), version));
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_17)) {
            buf.writeFloat(0); // angle
          }
        }
      };
}
