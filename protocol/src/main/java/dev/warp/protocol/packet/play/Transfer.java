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
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server instructs the client to connect to a different server ({@code S→C}).
 *
 * <p>Introduced in 1.20.5 (protocol 766). Enables zero-downtime server transfers without
 * client-side reconnection logic.
 *
 * @param host the target server hostname
 * @param port the target server port
 */
public record Transfer(String host, int port) implements PlayPacket {

  /** Codec for reading and writing transfer packets. */
  public static final PacketCodec<Transfer> CODEC =
      new PacketCodec<>() {
        @Override
        public Transfer decode(ByteBuf buf, ProtocolVersion version) {
          String host = McString.read(buf);
          int port = VarInt.read(buf);
          return new Transfer(host, port);
        }

        @Override
        public void encode(Transfer packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.host());
          VarInt.write(buf, packet.port());
        }
      };
}
