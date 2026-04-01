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
 * Server echoes the client's ping payload ({@code S→C, ID 0x01}).
 *
 * @param payload the same value sent by the client in {@link PingRequest}
 */
public record PongResponse(long payload) implements StatusPacket {

  /** Codec for reading and writing pong response packets. */
  public static final PacketCodec<PongResponse> CODEC =
      new PacketCodec<>() {
        @Override
        public PongResponse decode(ByteBuf buf, ProtocolVersion version) {
          return new PongResponse(buf.readLong());
        }

        @Override
        public void encode(PongResponse packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeLong(packet.payload());
        }
      };
}
