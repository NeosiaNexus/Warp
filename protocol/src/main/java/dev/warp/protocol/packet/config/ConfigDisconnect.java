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
 * Server disconnects the client during configuration ({@code S→C, ID 0x02}).
 *
 * <p>The reason is a text component whose wire format changed in 1.20.3 (JSON → NBT). The proxy
 * stores the raw bytes and forwards them as-is, deferring format interpretation to the
 * serialisation layer.
 *
 * @param rawReason the raw text component bytes (JSON or NBT depending on version)
 */
public record ConfigDisconnect(byte[] rawReason) implements ConfigPacket {

  /** Codec for reading and writing configuration disconnect packets. */
  public static final PacketCodec<ConfigDisconnect> CODEC =
      new PacketCodec<>() {
        @Override
        public ConfigDisconnect decode(ByteBuf buf, ProtocolVersion version) {
          byte[] rawReason = new byte[buf.readableBytes()];
          buf.readBytes(rawReason);
          return new ConfigDisconnect(rawReason);
        }

        @Override
        public void encode(ConfigDisconnect packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeBytes(packet.rawReason());
        }
      };
}
