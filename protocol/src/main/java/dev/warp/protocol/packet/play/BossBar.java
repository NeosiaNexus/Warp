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
import dev.warp.protocol.packet.PacketWatch;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Server adds, updates or removes a boss bar ({@code S→C}, from 1.9).
 *
 * <p>Before 1.20.2 boss bars survive the Join Game of a server switch, so the proxy follows the
 * bars the current server shows in order to remove them when the player leaves. It watches the
 * packet ({@link #WATCH}) rather than decoding it: the bar's UUID and the action are read in place,
 * and the frame is forwarded as received. The proxy itself only writes removals ({@link #remove}).
 *
 * <p>Layout: UUID, VarInt action, then the action's fields: title, health, color, division and
 * flags to add; nothing to remove; one or two of them to update (Velocity's {@code BossBarPacket},
 * minecraft-data's {@code packet_boss_bar}).
 *
 * @param uuid the boss bar's UUID
 * @param action {@link #ADD}, {@link #REMOVE}, or an update (2 to 5)
 */
public record BossBar(UUID uuid, int action) implements PlayPacket {

  /** Action that shows a new boss bar. */
  public static final int ADD = 0;

  /** Action that removes a boss bar. */
  public static final int REMOVE = 1;

  /**
   * Creates the packet that removes a boss bar.
   *
   * @param uuid the boss bar's UUID
   * @return the packet
   */
  public static BossBar remove(UUID uuid) {
    return new BossBar(uuid, REMOVE);
  }

  /**
   * Reports the bars a server shows and removes: the UUID and the action, read in place. Updates,
   * which every animated bar sends many times a second, report nothing and allocate nothing.
   */
  public static final PacketWatch<BossBar> WATCH =
      (buf, version) -> {
        long mostSignificant = buf.readLong();
        long leastSignificant = buf.readLong();
        int action = VarInt.read(buf);
        return action == ADD || action == REMOVE
            ? new BossBar(new UUID(mostSignificant, leastSignificant), action)
            : null;
      };

  /**
   * Codec of the boss bar removals the proxy writes: the UUID and the action, which is all a
   * removal holds. Other actions carry fields the proxy never reads, and are refused.
   */
  public static final PacketCodec<BossBar> CODEC =
      new PacketCodec<>() {
        @Override
        public BossBar decode(ByteBuf buf, ProtocolVersion version) {
          UUID uuid = McUuid.read(buf);
          int action = VarInt.read(buf);
          if (action != REMOVE) {
            throw new DecoderException("Only boss bar removals are decoded, not action " + action);
          }
          return new BossBar(uuid, action);
        }

        @Override
        public void encode(BossBar packet, ByteBuf buf, ProtocolVersion version) {
          if (packet.action() != REMOVE) {
            throw new IllegalArgumentException(
                "Only boss bar removals are written, not action " + packet.action());
          }
          McUuid.write(buf, packet.uuid());
          VarInt.write(buf, REMOVE);
        }
      };
}
