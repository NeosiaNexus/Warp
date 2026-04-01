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
 * Server sends a system chat message to the client ({@code S→C}).
 *
 * <p>The content is a text component (JSON before 1.20.3, NBT after). The proxy stores raw bytes
 * for format-agnostic handling.
 *
 * @param rawContent the raw text component bytes
 * @param overlay if {@code true}, the message is displayed as an action bar overlay
 */
public record SystemChatMessage(byte[] rawContent, boolean overlay) implements PlayPacket {

  /** Codec for reading and writing system chat message packets. */
  public static final PacketCodec<SystemChatMessage> CODEC =
      new PacketCodec<>() {
        @Override
        public SystemChatMessage decode(ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_3)) {
            // JSON: self-delimiting via VarInt-prefixed string
            int startPos = buf.readerIndex();
            McString.skip(buf);
            int contentLength = buf.readerIndex() - startPos;
            buf.readerIndex(startPos);
            byte[] rawContent = new byte[contentLength];
            buf.readBytes(rawContent);
            boolean overlay = buf.readBoolean();
            return new SystemChatMessage(rawContent, overlay);
          } else {
            // NBT: read remaining bytes minus the overlay boolean
            if (buf.readableBytes() < 1) {
              throw new io.netty.handler.codec.DecoderException(
                  "SystemChatMessage too short: need at least 1 byte for overlay");
            }
            int contentLength = buf.readableBytes() - 1;
            byte[] rawContent = new byte[contentLength];
            buf.readBytes(rawContent);
            boolean overlay = buf.readBoolean();
            return new SystemChatMessage(rawContent, overlay);
          }
        }

        @Override
        public void encode(SystemChatMessage packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeBytes(packet.rawContent());
          buf.writeBoolean(packet.overlay());
        }
      };
}
