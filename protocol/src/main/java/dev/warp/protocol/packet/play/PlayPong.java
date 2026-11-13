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

import io.netty.buffer.ByteBuf;

/**
 * The client's answer to a {@link PlayPing} (1.17+). Decoded by the anti-bot's virtual world only.
 *
 * @param id the id of the ping answered
 */
public record PlayPong(int id) implements PlayPacket {

  /** Codec for the serverbound play pong. */
  public static final PacketCodec<PlayPong> CODEC =
      new PacketCodec<>() {
        @Override
        public PlayPong decode(ByteBuf buf, ProtocolVersion version) {
          return new PlayPong(buf.readInt());
        }

        @Override
        public void encode(PlayPong packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeInt(packet.id());
        }
      };
}
