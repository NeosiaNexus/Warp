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
 * <p>Split from the chat message packet in 1.19, registered here from 1.19.3: the proxy does not
 * intercept the commands of older clients. The proxy intercepts this for command routing between
 * backend servers.
 *
 * <p>Version history:
 *
 * <ul>
 *   <li><b>1.19.3-1.20.4</b>: command (256 characters at most), then its timestamp, salt, argument
 *       signatures and acknowledged messages
 *   <li><b>1.20.5+</b>: command only (32767 characters at most). 1.20.5 moved commands with signed
 *       arguments to a separate packet, which the proxy forwards untouched
 * </ul>
 *
 * <p>The command string does not include the leading {@code /}. The signing fields are kept as raw
 * bytes since the proxy does not validate signatures.
 *
 * @param command the command string (without leading slash)
 * @param rawSignatureData the bytes after the command (signing fields before 1.20.5, else empty)
 */
public record ChatCommand(String command, byte[] rawSignatureData) implements PlayPacket {

  /** Maximum command length before 1.20.5, which raised it to the protocol's string maximum. */
  private static final int MAX_COMMAND_LENGTH_BEFORE_1_20_5 = 256;

  /** Codec for reading and writing chat command packets. */
  public static final PacketCodec<ChatCommand> CODEC =
      new PacketCodec<>() {
        @Override
        public ChatCommand decode(ByteBuf buf, ProtocolVersion version) {
          String command = McString.read(buf, maxCommandLength(version));
          byte[] rawSignatureData = new byte[buf.readableBytes()];
          buf.readBytes(rawSignatureData);
          return new ChatCommand(command, rawSignatureData);
        }

        @Override
        public void encode(ChatCommand packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.command(), maxCommandLength(version));
          buf.writeBytes(packet.rawSignatureData());
        }
      };

  private static int maxCommandLength(ProtocolVersion version) {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)
        ? McString.DEFAULT_MAX_CHARS
        : MAX_COMMAND_LENGTH_BEFORE_1_20_5;
  }
}
