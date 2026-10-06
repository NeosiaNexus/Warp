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
import dev.warp.protocol.packet.PacketWatch;

import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;

/**
 * Server removes tab list entries ({@code S→C}, "Player Info Remove", from 1.19.3).
 *
 * <p>The proxy watches it before 1.20.2 only, to forget the entries a server removes: the UUIDs are
 * read in place ({@link #WATCH}) and the frame is forwarded as received. It also sends it, to
 * remove the entries the previous server left when a player switches servers. Layout: VarInt count,
 * then the UUIDs (Velocity's {@code RemovePlayerInfoPacket}, minecraft-data's {@code
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
          return new PlayerInfoRemove(TabListEntries.readUuids(buf));
        }

        @Override
        public void encode(PlayerInfoRemove packet, ByteBuf buf, ProtocolVersion version) {
          TabListEntries.writeUuids(buf, packet.profileIds());
        }
      };

  /**
   * Reports the players a server removes from the tab list. The packet holds nothing but their
   * UUIDs, so it is read whole; the frame is still forwarded as received, never re-encoded.
   */
  public static final PacketWatch<PlayerInfoRemove> WATCH = CODEC::decode;
}
