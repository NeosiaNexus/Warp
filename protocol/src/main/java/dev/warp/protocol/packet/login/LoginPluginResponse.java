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
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

/**
 * Client responds to a login plugin request ({@code C→S, ID 0x02}).
 *
 * @param messageId the message ID from the matching {@link LoginPluginRequest}
 * @param successful whether the client understood and handled the request
 * @param data the response payload, or {@code null} if {@code successful} is {@code false}
 */
public record LoginPluginResponse(int messageId, boolean successful, byte @Nullable [] data)
    implements LoginPacket {

  /** Codec for reading and writing login plugin response packets. */
  public static final PacketCodec<LoginPluginResponse> CODEC =
      new PacketCodec<>() {
        @Override
        public LoginPluginResponse decode(ByteBuf buf, ProtocolVersion version) {
          int messageId = VarInt.read(buf);
          boolean successful = buf.readBoolean();
          byte @Nullable [] data = null;
          if (buf.isReadable()) {
            data = new byte[buf.readableBytes()];
            buf.readBytes(data);
          }
          return new LoginPluginResponse(messageId, successful, data);
        }

        @Override
        public void encode(LoginPluginResponse packet, ByteBuf buf, ProtocolVersion version) {
          VarInt.write(buf, packet.messageId());
          buf.writeBoolean(packet.successful());
          if (packet.data() != null) {
            buf.writeBytes(packet.data());
          }
        }
      };
}
