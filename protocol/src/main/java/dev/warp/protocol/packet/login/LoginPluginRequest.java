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
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server sends a login plugin request ({@code S→C, ID 0x04}).
 *
 * <p>Used for proxy-specific channels like {@code velocity:player_info} (Velocity modern
 * forwarding). The client responds with {@link LoginPluginResponse}.
 *
 * @param messageId unique identifier for this request–response pair
 * @param channel the plugin channel name (e.g., {@code velocity:player_info})
 * @param data the channel-specific payload (remaining bytes after channel)
 */
public record LoginPluginRequest(int messageId, String channel, byte[] data)
    implements LoginPacket {

  /** Codec for reading and writing login plugin request packets. */
  public static final PacketCodec<LoginPluginRequest> CODEC =
      new PacketCodec<>() {
        @Override
        public LoginPluginRequest decode(ByteBuf buf, ProtocolVersion version) {
          int messageId = VarInt.read(buf);
          String channel = McString.read(buf);
          byte[] data = new byte[buf.readableBytes()];
          buf.readBytes(data);
          return new LoginPluginRequest(messageId, channel, data);
        }

        @Override
        public void encode(LoginPluginRequest packet, ByteBuf buf, ProtocolVersion version) {
          VarInt.write(buf, packet.messageId());
          McString.write(buf, packet.channel());
          buf.writeBytes(packet.data());
        }
      };
}
