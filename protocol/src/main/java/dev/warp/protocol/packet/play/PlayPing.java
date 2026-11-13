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
 * A ping of the play state (1.17+), which the client echoes as a {@link PlayPong} with the same id,
 * from its main thread. The anti-bot's virtual world sends one; a server's stay opaque.
 *
 * @param id the id, echoed back
 */
public record PlayPing(int id) implements PlayPacket {

  /** Codec for the clientbound play ping. */
  public static final PacketCodec<PlayPing> CODEC =
      new PacketCodec<>() {
        @Override
        public PlayPing decode(ByteBuf buf, ProtocolVersion version) {
          return new PlayPing(buf.readInt());
        }

        @Override
        public void encode(PlayPing packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeInt(packet.id());
        }
      };
}
