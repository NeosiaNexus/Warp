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
 * The end of a client tick (Client Tick End, 1.21.2+): empty, sent once per tick. Decoded by the
 * anti-bot's virtual world only, as a signal of the client's cadence, never as a verdict.
 */
public record TickEnd() implements PlayPacket {

  /** Codec for the serverbound tick end. */
  public static final PacketCodec<TickEnd> CODEC =
      new PacketCodec<>() {
        @Override
        public TickEnd decode(ByteBuf buf, ProtocolVersion version) {
          return new TickEnd();
        }

        @Override
        public void encode(TickEnd packet, ByteBuf buf, ProtocolVersion version) {
          // no fields
        }
      };
}
