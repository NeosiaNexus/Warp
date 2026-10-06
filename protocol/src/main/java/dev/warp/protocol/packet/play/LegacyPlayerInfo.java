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
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server changes the tab list of a 1.7 client ({@code S→C}, "Player List Item", 1.7.2 to 1.7.10;
 * keyed by UUID from 1.8, see {@link PlayerInfo}).
 *
 * <p>The 1.7 client keys its tab list by the listed name: a packet with {@code online} set adds the
 * name, or updates its latency when it is already listed, and one without removes it. The tab list
 * survives the Join Game of a server switch, so the proxy keeps the names the current server lists,
 * to remove the leftovers when the player leaves.
 *
 * <p>Layout: the name (string; vanilla lists at most 16 characters, which may include {@code §}
 * formatting codes, and the client enforces that limit, not the proxy), whether it is listed
 * (boolean) and its latency in milliseconds (short). One entry per packet. Sources: Velocity's
 * {@code LegacyPlayerListItemPacket} (its pre-1.8 branch), minecraft-data's {@code
 * packet_player_info} for 1.7.
 *
 * @param name the listed name: the entry's key on the client
 * @param online {@code true} to list the name or update its latency, {@code false} to remove it
 * @param latency the latency in milliseconds, which the client ignores on a removal
 */
public record LegacyPlayerInfo(String name, boolean online, short latency) implements PlayPacket {

  /**
   * Creates the packet that removes a name from the tab list.
   *
   * @param name the listed name
   * @return the packet
   */
  public static LegacyPlayerInfo remove(String name) {
    return new LegacyPlayerInfo(name, false, (short) 0);
  }

  /** Codec for reading and writing 1.7 player list item packets. */
  public static final PacketCodec<LegacyPlayerInfo> CODEC =
      new PacketCodec<>() {
        @Override
        public LegacyPlayerInfo decode(ByteBuf buf, ProtocolVersion version) {
          return new LegacyPlayerInfo(McString.read(buf), buf.readBoolean(), buf.readShort());
        }

        @Override
        public void encode(LegacyPlayerInfo packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.name());
          buf.writeBoolean(packet.online());
          buf.writeShort(packet.latency());
        }
      };
}
