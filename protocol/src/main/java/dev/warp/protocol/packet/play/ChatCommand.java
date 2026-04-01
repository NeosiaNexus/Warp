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
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Client sends a chat command ({@code C→S}).
 *
 * <p>Split from the chat message packet in 1.19.3. The proxy intercepts this for command routing
 * between backend servers.
 *
 * <p>The command string does not include the leading {@code /}. Signing-related fields (timestamp,
 * salt, signatures) vary across versions and are captured as raw bytes since the proxy does not
 * validate signatures.
 *
 * @param command the command string (without leading slash)
 * @param rawSignatureData the remaining bytes (timestamp, salt, argument signatures — version
 *     dependent)
 */
public record ChatCommand(String command, byte[] rawSignatureData) implements PlayPacket {

  /** Maximum command length per protocol spec. */
  private static final int MAX_COMMAND_LENGTH = 256;

  /** Codec for reading and writing chat command packets. */
  public static final PacketCodec<ChatCommand> CODEC =
      new PacketCodec<>() {
        @Override
        public ChatCommand decode(ByteBuf buf, ProtocolVersion version) {
          String command = McString.read(buf, MAX_COMMAND_LENGTH);
          byte[] rawSignatureData = new byte[buf.readableBytes()];
          buf.readBytes(rawSignatureData);
          return new ChatCommand(command, rawSignatureData);
        }

        @Override
        public void encode(ChatCommand packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.command(), MAX_COMMAND_LENGTH);
          buf.writeBytes(packet.rawSignatureData());
        }
      };
}
