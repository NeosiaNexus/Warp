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
 * A teleport of the client (Synchronize Player Position), absolute, without velocity: the client
 * applies it, answers a {@link TeleportConfirm} with the same id (1.9+) and a {@link
 * ClientMovement.PositionRotation} at exactly this position. Encode-only: the anti-bot's virtual
 * world sends it; a server's stay opaque.
 *
 * <p>Layouts: 1.7 (x, eye height, z, yaw, pitch, on ground), 1.8 (flags byte), 1.9 (teleport id),
 * 1.17 to 1.19.3 (dismount vehicle boolean), 1.21.2 (id first, velocity, int flags).
 *
 * @param teleportId the id the client echoes, from 1.9
 * @param x the x coordinate
 * @param y the feet height
 * @param z the z coordinate
 * @param yaw the yaw in degrees
 * @param pitch the pitch in degrees
 */
public record SyncPlayerPosition(
    int teleportId, double x, double y, double z, float yaw, float pitch) implements PlayPacket {

  /** Codec for the clientbound teleport. */
  public static final PacketCodec<SyncPlayerPosition> CODEC =
      new PacketCodec<>() {
        @Override
        public SyncPlayerPosition decode(ByteBuf buf, ProtocolVersion version) {
          throw new UnsupportedOperationException("encode-only");
        }

        @Override
        public void encode(SyncPlayerPosition packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2)) {
            VarInt.write(buf, packet.teleportId());
            buf.writeDouble(packet.x());
            buf.writeDouble(packet.y());
            buf.writeDouble(packet.z());
            buf.writeDouble(0); // velocity
            buf.writeDouble(0);
            buf.writeDouble(0);
            buf.writeFloat(packet.yaw());
            buf.writeFloat(packet.pitch());
            buf.writeInt(0); // absolute
            return;
          }
          buf.writeDouble(packet.x());
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
            buf.writeDouble(packet.y() + ClientMovement.LEGACY_STANCE); // 1.7 sends the eyes
          } else {
            buf.writeDouble(packet.y());
          }
          buf.writeDouble(packet.z());
          buf.writeFloat(packet.yaw());
          buf.writeFloat(packet.pitch());
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
            buf.writeBoolean(false); // on ground
            return;
          }
          buf.writeByte(0); // absolute
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)) {
            VarInt.write(buf, packet.teleportId());
          }
          if (version.isBetween(ProtocolVersion.MINECRAFT_1_17, ProtocolVersion.MINECRAFT_1_19_3)) {
            buf.writeBoolean(false); // dismount vehicle
          }
        }
      };
}
