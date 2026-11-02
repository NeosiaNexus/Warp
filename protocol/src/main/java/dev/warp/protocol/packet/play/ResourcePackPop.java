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
import dev.warp.protocol.packet.PacketCodec;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

/**
 * Server unloads one resource pack, or all of them ({@code S→C}, from 1.20.3, in the play and
 * configuration states): the Remove Resource Pack packet, an optional UUID (minecraft-data's {@code
 * packet_remove_resource_pack}). Before 1.20.3 no packet unloads a server resource pack.
 *
 * <p>The proxy decodes the removals a server sends: one that removes every pack must not take the
 * proxy's packs with it, and is replaced by the removal of the server's own packs.
 *
 * @param uuid the pack to unload, or {@code null} for every pack
 */
public record ResourcePackPop(@Nullable UUID uuid) implements PlayPacket {

  /** Codec for reading and writing resource pack removals. */
  public static final PacketCodec<ResourcePackPop> CODEC =
      new PacketCodec<>() {
        @Override
        public ResourcePackPop decode(ByteBuf buf, ProtocolVersion version) {
          return new ResourcePackPop(buf.readBoolean() ? McUuid.read(buf) : null);
        }

        @Override
        public void encode(ResourcePackPop packet, ByteBuf buf, ProtocolVersion version) {
          UUID uuid = packet.uuid();
          buf.writeBoolean(uuid != null);
          if (uuid != null) {
            McUuid.write(buf, uuid);
          }
        }
      };
}
