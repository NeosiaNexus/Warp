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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_17;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server sets how long the next titles fade in, stay and fade out ({@code S→C}, from 1.8), in
 * ticks. The times apply to the titles shown after them, so they are sent first.
 *
 * <p>From 1.17 this is the Set Titles Animation packet: three ints. Up to 1.16.5 it is the Title
 * packet's set times action, 2 before 1.11 and 3 from 1.11, followed by the three ints ({@link
 * LegacyTitle}).
 *
 * @param fadeIn the fade-in time, in ticks
 * @param stay the time the title stays, in ticks
 * @param fadeOut the fade-out time, in ticks
 */
public record SetTitleTimes(int fadeIn, int stay, int fadeOut) implements PlayPacket {

  /** Codec for reading and writing set title times packets. */
  public static final PacketCodec<SetTitleTimes> CODEC =
      new PacketCodec<>() {
        @Override
        public SetTitleTimes decode(ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(MINECRAFT_1_17)) {
            LegacyTitle.expect(buf, LegacyTitle.setTimes(version));
          }
          return new SetTitleTimes(buf.readInt(), buf.readInt(), buf.readInt());
        }

        @Override
        public void encode(SetTitleTimes packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(MINECRAFT_1_17)) {
            VarInt.write(buf, LegacyTitle.setTimes(version));
          }
          buf.writeInt(packet.fadeIn());
          buf.writeInt(packet.stay());
          buf.writeInt(packet.fadeOut());
        }
      };
}
