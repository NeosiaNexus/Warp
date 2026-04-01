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
package dev.warp.protocol.packet.login;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server disconnects the client during login ({@code S→C, ID 0x00}).
 *
 * <p>The reason is a text component (JSON pre-1.20.3, NBT post-1.20.3). The proxy stores the raw
 * bytes and forwards them as-is, deferring format interpretation to the serialisation layer.
 *
 * @param rawReason the raw text component bytes (JSON or NBT depending on version)
 */
public record LoginDisconnect(byte[] rawReason) implements LoginPacket {

  /** Codec for reading and writing login disconnect packets. */
  public static final PacketCodec<LoginDisconnect> CODEC =
      new PacketCodec<>() {
        @Override
        public LoginDisconnect decode(ByteBuf buf, ProtocolVersion version) {
          byte[] rawReason = new byte[buf.readableBytes()];
          buf.readBytes(rawReason);
          return new LoginDisconnect(rawReason);
        }

        @Override
        public void encode(LoginDisconnect packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeBytes(packet.rawReason());
        }
      };
}
