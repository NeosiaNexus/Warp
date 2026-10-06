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
import dev.warp.protocol.codec.McString;
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
 *   <li><b>1.8-1.9.4</b>: String hash (of the pack it answers) + VarInt result
 *   <li><b>1.10-1.20.2</b>: VarInt result only
 *   <li><b>1.20.3+</b>: UUID + VarInt result
 * </ul>
 *
 * @param uuid the resource pack UUID (zero UUID before 1.20.3)
 * @param hash the resource pack hash (empty from 1.10)
 * @param result the result code (0=accepted, 1=declined, 2=failed, 3=downloaded, etc.)
 */
public record ResourcePackResponse(UUID uuid, String hash, int result) implements PlayPacket {

  /** Codec for reading and writing resource pack response packets. */
  public static final PacketCodec<ResourcePackResponse> CODEC =
      new PacketCodec<>() {
        @Override
        public ResourcePackResponse decode(ByteBuf buf, ProtocolVersion version) {
          UUID uuid =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)
                  ? McUuid.read(buf)
                  : new UUID(0, 0);
          String hash = version.isAtMost(ProtocolVersion.MINECRAFT_1_9_4) ? McString.read(buf) : "";
          int result = VarInt.read(buf);
          return new ResourcePackResponse(uuid, hash, result);
        }

        @Override
        public void encode(ResourcePackResponse packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
            McUuid.write(buf, packet.uuid());
          }
          if (version.isAtMost(ProtocolVersion.MINECRAFT_1_9_4)) {
            McString.write(buf, packet.hash());
          }
          VarInt.write(buf, packet.result());
        }
      };
}
