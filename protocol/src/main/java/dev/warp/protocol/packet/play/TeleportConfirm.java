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
 * The client's confirmation of a teleport (Confirm Teleportation, 1.9+): the id of the {@link
 * SyncPlayerPosition} it applied. Decoded by the anti-bot's virtual world only.
 *
 * @param teleportId the id the server sent
 */
public record TeleportConfirm(int teleportId) implements PlayPacket {

  /** Codec for the serverbound teleport confirmation. */
  public static final PacketCodec<TeleportConfirm> CODEC =
      new PacketCodec<>() {
        @Override
        public TeleportConfirm decode(ByteBuf buf, ProtocolVersion version) {
          return new TeleportConfirm(VarInt.read(buf));
        }

        @Override
        public void encode(TeleportConfirm packet, ByteBuf buf, ProtocolVersion version) {
          VarInt.write(buf, packet.teleportId());
        }
      };
}
