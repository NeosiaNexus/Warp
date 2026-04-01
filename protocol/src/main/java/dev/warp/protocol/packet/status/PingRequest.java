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
 * Client sends a ping with an arbitrary payload ({@code C→S, ID 0x01}).
 *
 * <p>The server must echo the same payload in a {@link PongResponse}. Used to measure round-trip
 * latency in the server list.
 *
 * @param payload an arbitrary value chosen by the client
 */
public record PingRequest(long payload) implements StatusPacket {

  /** Codec for reading and writing ping request packets. */
  public static final PacketCodec<PingRequest> CODEC =
      new PacketCodec<>() {
        @Override
        public PingRequest decode(ByteBuf buf, ProtocolVersion version) {
          return new PingRequest(buf.readLong());
        }

        @Override
        public void encode(PingRequest packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeLong(packet.payload());
        }
      };
}
