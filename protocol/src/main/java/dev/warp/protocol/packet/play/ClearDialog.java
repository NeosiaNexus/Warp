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
 * Server closes the dialog on screen, if any ({@code S→C}, from 1.21.6, in the play and
 * configuration states): the Clear Dialog packet, with no fields.
 */
public record ClearDialog() implements PlayPacket {

  /** Codec for reading and writing dialog clears. */
  public static final PacketCodec<ClearDialog> CODEC =
      new PacketCodec<>() {
        @Override
        public ClearDialog decode(ByteBuf buf, ProtocolVersion version) {
          return new ClearDialog();
        }

        @Override
        public void encode(ClearDialog packet, ByteBuf buf, ProtocolVersion version) {
          // no fields
        }
      };
}
