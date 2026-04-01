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
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import java.util.UUID;

import io.netty.buffer.ByteBuf;

/**
 * Client responds with the result of a resource pack request ({@code C->S}).
 *
 * <p>Version history:
 *
 * <ul>
 *   <li><b>1.8-1.20.2</b>: VarInt result only
 *   <li><b>1.20.3+</b>: UUID + VarInt result
 * </ul>
 *
 * @param uuid the resource pack UUID (zero UUID for pre-1.20.3)
 * @param result the result code (0=accepted, 1=declined, 2=failed, 3=downloaded, etc.)
 */
public record ResourcePackResponse(UUID uuid, int result) implements PlayPacket {

  /** Codec for reading and writing resource pack response packets. */
  public static final PacketCodec<ResourcePackResponse> CODEC =
      new PacketCodec<>() {
        @Override
        public ResourcePackResponse decode(ByteBuf buf, ProtocolVersion version) {
          UUID uuid;
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
            uuid = McUuid.read(buf);
          } else {
            uuid = new UUID(0, 0);
          }
          int result = VarInt.read(buf);
          return new ResourcePackResponse(uuid, result);
        }

        @Override
        public void encode(ResourcePackResponse packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
            McUuid.write(buf, packet.uuid());
          }
          VarInt.write(buf, packet.result());
        }
      };
}
