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
 * Server adds, updates or removes a boss bar ({@code S→C}, from 1.9).
 *
 * <p>Before 1.20.2 boss bars survive the Join Game of a server switch, so the proxy follows the
 * bars the current server shows in order to remove them when the player leaves. It reads the bar's
 * UUID and the action, and carries the rest of the packet verbatim. Layout: UUID, VarInt action,
 * then the action's fields (Velocity's {@code BossBarPacket}, minecraft-data's {@code
 * packet_boss_bar}).
 *
 * @param uuid the boss bar's UUID
 * @param action {@link #ADD}, {@link #REMOVE}, or an update (2 to 5)
 * @param rest the action's fields, verbatim
 */
@SuppressWarnings("ArrayRecordComponent") // rest is never mutated
public record BossBar(UUID uuid, int action, byte[] rest) implements PlayPacket {

  /** Action that shows a new boss bar. */
  public static final int ADD = 0;

  /** Action that removes a boss bar. */
  public static final int REMOVE = 1;

  private static final byte[] EMPTY = new byte[0];

  /**
   * Creates the packet that removes a boss bar.
   *
   * @param uuid the boss bar's UUID
   * @return the packet
   */
  public static BossBar remove(UUID uuid) {
    return new BossBar(uuid, REMOVE, EMPTY);
  }

  /** Codec for reading and writing boss bar packets. */
  public static final PacketCodec<BossBar> CODEC =
      new PacketCodec<>() {
        @Override
        public BossBar decode(ByteBuf buf, ProtocolVersion version) {
          UUID uuid = McUuid.read(buf);
          int action = VarInt.read(buf);
          byte[] rest = new byte[buf.readableBytes()];
          buf.readBytes(rest);
          return new BossBar(uuid, action, rest);
        }

        @Override
        public void encode(BossBar packet, ByteBuf buf, ProtocolVersion version) {
          McUuid.write(buf, packet.uuid());
          VarInt.write(buf, packet.action());
          buf.writeBytes(packet.rest());
        }
      };
}
