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
package dev.warp.protocol.packet.config;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server sends its branding and chat security settings ({@code S→C}).
 *
 * <p>Contains the server's MOTD, icon, and secure chat enforcement flag. The proxy may intercept
 * this during server switches to replace branding. The raw bytes are preserved for faithful
 * forwarding.
 *
 * @param rawData the entire packet payload as raw bytes
 */
public record ServerData(byte[] rawData) implements ConfigPacket {

  /** Codec for reading and writing server data packets. */
  public static final PacketCodec<ServerData> CODEC =
      new PacketCodec<>() {
        @Override
        public ServerData decode(ByteBuf buf, ProtocolVersion version) {
          byte[] rawData = new byte[buf.readableBytes()];
          buf.readBytes(rawData);
          return new ServerData(rawData);
        }

        @Override
        public void encode(ServerData packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeBytes(packet.rawData());
        }
      };
}
