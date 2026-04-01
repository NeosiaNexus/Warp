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
package dev.warp.protocol.packet.status;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Client requests the server status ({@code C→S, ID 0x00}).
 *
 * <p>Empty payload — the mere presence of this packet triggers the server to reply with a {@link
 * StatusResponse}.
 */
public record StatusRequest() implements StatusPacket {

  /** Codec for reading and writing status request packets. */
  public static final PacketCodec<StatusRequest> CODEC =
      new PacketCodec<>() {
        @Override
        public StatusRequest decode(ByteBuf buf, ProtocolVersion version) {
          return new StatusRequest();
        }

        @Override
        public void encode(StatusRequest packet, ByteBuf buf, ProtocolVersion version) {
          // no fields
        }
      };
}
