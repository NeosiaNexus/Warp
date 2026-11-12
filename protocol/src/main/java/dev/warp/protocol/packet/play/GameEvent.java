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
 * A game event (Game Event, "Change Game State" before 1.20.2): one byte and a float, the same
 * layout in every version. Encode-only: the anti-bot's virtual world sends {@link
 * #START_WAITING_FOR_CHUNKS} to a 1.20.3+ client, which otherwise never leaves its loading screen.
 *
 * @param event the event id
 * @param value the event's value
 */
public record GameEvent(int event, float value) implements PlayPacket {

  /** Event 13 (1.20.3+): the client waits for the chunk under it, then starts playing. */
  public static final int START_WAITING_FOR_CHUNKS = 13;

  /** Codec for the clientbound game event. */
  public static final PacketCodec<GameEvent> CODEC =
      new PacketCodec<>() {
        @Override
        public GameEvent decode(ByteBuf buf, ProtocolVersion version) {
          throw new UnsupportedOperationException("encode-only");
        }

        @Override
        public void encode(GameEvent packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeByte(packet.event());
          buf.writeFloat(packet.value());
        }
      };
}
