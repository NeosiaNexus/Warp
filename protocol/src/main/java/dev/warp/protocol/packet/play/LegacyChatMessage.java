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
 * Client sends a line typed in the chat box before 1.19 ({@code C→S}): a chat message, or a command
 * when it starts with {@code /}.
 *
 * <p>The packet is a single string from 1.7.2 to 1.18.2. In 1.19 commands moved to their own packet
 * ({@link ChatCommand}) and chat messages became signed, so it is registered up to 1.18.2 only. The
 * proxy decodes it to catch its own commands and forwards every other line to the backend.
 *
 * @param message the typed line, including the leading {@code /} of a command
 */
public record LegacyChatMessage(String message) implements PlayPacket {

  /**
   * Longest line accepted, the limit since 1.11. Older clients send at most 100 characters; the
   * proxy leaves that check to the backend, which applies its own limit anyway.
   */
  private static final int MAX_MESSAGE_LENGTH = 256;

  /** Codec for reading and writing legacy chat message packets. */
  public static final PacketCodec<LegacyChatMessage> CODEC =
      new PacketCodec<>() {
        @Override
        public LegacyChatMessage decode(ByteBuf buf, ProtocolVersion version) {
          return new LegacyChatMessage(McString.read(buf, MAX_MESSAGE_LENGTH));
        }

        @Override
        public void encode(LegacyChatMessage packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.message(), MAX_MESSAGE_LENGTH);
        }
      };
}
