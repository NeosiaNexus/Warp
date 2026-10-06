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

import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;

/**
 * Server adds or updates tab list entries ({@code S→C}, "Player Info Update", from 1.19.3).
 *
 * <p>The proxy decodes it before 1.20.2 only, where the tab list survives the Join Game of a server
 * switch: it keeps the UUIDs the current server adds, to remove the leftovers when the player
 * leaves. It reads the actions and each entry's UUID, and carries the entries verbatim.
 *
 * <p>Layout (1.19.3 to 1.20.1): a byte of action flags, VarInt count, then per entry a UUID and the
 * fields of each flagged action, in flag order: name and properties ({@link #ADD_PLAYER}), optional
 * chat session ({@link #INITIALIZE_CHAT}), game mode, listed flag, latency, optional display name
 * (Velocity's {@code UpsertPlayerInfoPacket}, minecraft-data's {@code packet_player_info}).
 *
 * @param actions the action flags ({@link #ADD_PLAYER} to {@link #UPDATE_DISPLAY_NAME})
 * @param profileIds the UUID of every entry, in order
 * @param entries the entry count and the entries, verbatim
 */
@SuppressWarnings("ArrayRecordComponent") // entries are never mutated
public record PlayerInfoUpdate(int actions, List<UUID> profileIds, byte[] entries)
    implements PlayPacket {

  /** Adds the entries' players to the tab list. */
  public static final int ADD_PLAYER = 0x01;

  /** Sets the entries' chat sessions. */
  public static final int INITIALIZE_CHAT = 0x02;

  /** Changes the entries' game mode. */
  public static final int UPDATE_GAME_MODE = 0x04;

  /** Shows or hides the entries in the tab list. */
  public static final int UPDATE_LISTED = 0x08;

  /** Changes the entries' latency. */
  public static final int UPDATE_LATENCY = 0x10;

  /** Changes the entries' display name. */
  public static final int UPDATE_DISPLAY_NAME = 0x20;

  /** Copies the UUIDs into an unmodifiable list. */
  public PlayerInfoUpdate {
    profileIds = List.copyOf(profileIds);
  }

  /** Codec for reading and writing player info update packets (1.19.3 to 1.20.1). */
  public static final PacketCodec<PlayerInfoUpdate> CODEC =
      new PacketCodec<>() {
        @Override
        public PlayerInfoUpdate decode(ByteBuf buf, ProtocolVersion version) {
          int actions = buf.readUnsignedByte();
          int start = buf.readerIndex();
          int count = TabListEntries.readCount(buf, McUuid.ENCODED_SIZE);
          UUID[] profileIds = new UUID[count];
          for (int i = 0; i < count; i++) {
            profileIds[i] = McUuid.read(buf);
            skipEntry(buf, actions);
          }
          return new PlayerInfoUpdate(
              actions, List.of(profileIds), TabListEntries.copyFrom(buf, start));
        }

        @Override
        public void encode(PlayerInfoUpdate packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeByte(packet.actions());
          buf.writeBytes(packet.entries());
        }
      };

  private static void skipEntry(ByteBuf buf, int actions) {
    if ((actions & ADD_PLAYER) != 0) {
      McString.skip(buf); // name
      TabListEntries.skipProperties(buf);
    }
    if ((actions & INITIALIZE_CHAT) != 0 && buf.readBoolean()) {
      buf.skipBytes(McUuid.ENCODED_SIZE); // session ID
      TabListEntries.skipPublicKey(buf);
    }
    if ((actions & UPDATE_GAME_MODE) != 0) {
      VarInt.skip(buf);
    }
    if ((actions & UPDATE_LISTED) != 0) {
      buf.skipBytes(1);
    }
    if ((actions & UPDATE_LATENCY) != 0) {
      VarInt.skip(buf);
    }
    if ((actions & UPDATE_DISPLAY_NAME) != 0) {
      TabListEntries.skipOptionalString(buf);
    }
  }
}
