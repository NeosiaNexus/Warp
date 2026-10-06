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

import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;

/**
 * Server removes tab list entries ({@code S→C}, "Player Info Remove", from 1.19.3).
 *
 * <p>The proxy decodes it before 1.20.2 only, to forget the entries a server removes, and sends it
 * to remove the entries the previous server left when a player switches servers. Layout: VarInt
 * count, then the UUIDs (Velocity's {@code RemovePlayerInfoPacket}, minecraft-data's {@code
 * packet_player_remove}).
 *
 * @param profileIds the UUIDs of the removed entries
 */
public record PlayerInfoRemove(List<UUID> profileIds) implements PlayPacket {

  /** Copies the UUIDs into an unmodifiable list. */
  public PlayerInfoRemove {
    profileIds = List.copyOf(profileIds);
  }

  /** Codec for reading and writing player info remove packets. */
  public static final PacketCodec<PlayerInfoRemove> CODEC =
      new PacketCodec<>() {
        @Override
        public PlayerInfoRemove decode(ByteBuf buf, ProtocolVersion version) {
          int count = TabListEntries.readCount(buf, McUuid.ENCODED_SIZE);
          UUID[] profileIds = new UUID[count];
          for (int i = 0; i < count; i++) {
            profileIds[i] = McUuid.read(buf);
          }
          return new PlayerInfoRemove(List.of(profileIds));
        }

        @Override
        public void encode(PlayerInfoRemove packet, ByteBuf buf, ProtocolVersion version) {
          TabListEntries.writeUuids(buf, packet.profileIds());
        }
      };
}
