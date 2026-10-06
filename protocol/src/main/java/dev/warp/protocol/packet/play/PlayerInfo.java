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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Server changes the tab list ({@code S→C}, "Player Info", from 1.8 to 1.19.2; split into {@link
 * PlayerInfoUpdate} and {@link PlayerInfoRemove} in 1.19.3).
 *
 * <p>The tab list survives the Join Game of a server switch, so the proxy keeps the UUIDs the
 * current server adds and removes, to remove the leftovers when the player leaves. It reads the
 * action and each entry's UUID, and carries the entries verbatim.
 *
 * <p>Layout: VarInt action, VarInt count, then per entry a UUID and the action's fields. Adding a
 * player sends its name, properties, game mode, latency and optional display name, plus from 1.19
 * an optional public key (Velocity's {@code LegacyPlayerListItemPacket}, minecraft-data's {@code
 * packet_player_info}). 1.7 keys the list by name instead of UUID, with its own packet: {@link
 * LegacyPlayerInfo}.
 *
 * @param action {@link #ADD_PLAYER}, {@link #UPDATE_GAME_MODE}, {@link #UPDATE_LATENCY}, {@link
 *     #UPDATE_DISPLAY_NAME} or {@link #REMOVE_PLAYER}
 * @param profileIds the UUID of every entry, in order
 * @param entries the entry count and the entries, verbatim
 */
@SuppressWarnings("ArrayRecordComponent") // entries are never mutated
public record PlayerInfo(int action, List<UUID> profileIds, byte[] entries) implements PlayPacket {

  /** Adds players to the tab list. */
  public static final int ADD_PLAYER = 0;

  /** Changes the game mode of listed players. */
  public static final int UPDATE_GAME_MODE = 1;

  /** Changes the latency of listed players. */
  public static final int UPDATE_LATENCY = 2;

  /** Changes the display name of listed players. */
  public static final int UPDATE_DISPLAY_NAME = 3;

  /** Removes players from the tab list. */
  public static final int REMOVE_PLAYER = 4;

  /** Copies the UUIDs into an unmodifiable list. */
  public PlayerInfo {
    profileIds = List.copyOf(profileIds);
  }

  /**
   * Creates the packet that removes players from the tab list.
   *
   * @param profileIds the players' UUIDs
   * @return the packet
   */
  public static PlayerInfo remove(Collection<UUID> profileIds) {
    return new PlayerInfo(
        REMOVE_PLAYER, List.copyOf(profileIds), TabListEntries.encodeUuids(profileIds));
  }

  /** Codec for reading and writing player info packets. */
  public static final PacketCodec<PlayerInfo> CODEC =
      new PacketCodec<>() {
        @Override
        public PlayerInfo decode(ByteBuf buf, ProtocolVersion version) {
          int action = VarInt.read(buf);
          int start = buf.readerIndex();
          int count = TabListEntries.readCount(buf, McUuid.ENCODED_SIZE);
          UUID[] profileIds = new UUID[count];
          for (int i = 0; i < count; i++) {
            profileIds[i] = McUuid.read(buf);
            skipEntry(buf, action, version);
          }
          return new PlayerInfo(action, List.of(profileIds), TabListEntries.copyFrom(buf, start));
        }

        @Override
        public void encode(PlayerInfo packet, ByteBuf buf, ProtocolVersion version) {
          VarInt.write(buf, packet.action());
          buf.writeBytes(packet.entries());
        }
      };

  private static void skipEntry(ByteBuf buf, int action, ProtocolVersion version) {
    switch (action) {
      case ADD_PLAYER -> {
        McString.skip(buf); // name
        TabListEntries.skipProperties(buf);
        VarInt.skip(buf); // game mode
        VarInt.skip(buf); // latency
        TabListEntries.skipOptionalString(buf); // display name
        if (version.isAtLeast(MINECRAFT_1_19) && buf.readBoolean()) {
          TabListEntries.skipPublicKey(buf);
        }
      }
      case UPDATE_GAME_MODE, UPDATE_LATENCY -> VarInt.skip(buf);
      case UPDATE_DISPLAY_NAME -> TabListEntries.skipOptionalString(buf);
      case REMOVE_PLAYER -> {
        // The UUID is the whole entry.
      }
      default -> throw new DecoderException("Unknown player info action " + action);
    }
  }
}
