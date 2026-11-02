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
 * Server instructs the client to connect to a different server ({@code S→C}), in the configuration
 * and play states.
 *
 * <p>Introduced in 1.20.5 (protocol 766). The client disconnects, then connects to {@code
 * host:port} with a handshake whose intent is transfer (3), carrying over the cookies stored on it.
 * A server only lets it in if it accepts transfers. Enables zero-downtime moves between proxies or
 * networks without client-side reconnection logic.
 *
 * <p>Registered in both states though defined with the play packets, like {@link KeepAlive}.
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
