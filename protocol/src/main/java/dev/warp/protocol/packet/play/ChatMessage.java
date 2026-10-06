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
 * Player sends a chat line ({@code C→S}, up to 1.18.2).
 *
 * <p>Until 1.19 gave commands their own packet ({@link ChatCommand}), commands arrive here too, as
 * chat starting with a slash: the proxy reads this packet to intercept its own commands ({@code
 * /server}) and forwards the others. Layout: one string, at most 100 characters up to 1.10 and 256
 * from 1.11 (Velocity's {@code LegacyChatPacket}, minecraft-data's {@code packet_chat}).
 *
 * @param message the chat line, a command when it starts with {@code /}
 */
public record ChatMessage(String message) implements PlayPacket {

  /** Longest chat line any version sends (1.11 and newer). */
  private static final int MAX_LENGTH = 256;

  /** Codec for reading and writing chat message packets. */
  public static final PacketCodec<ChatMessage> CODEC =
      new PacketCodec<>() {
        @Override
        public ChatMessage decode(ByteBuf buf, ProtocolVersion version) {
          return new ChatMessage(McString.read(buf, MAX_LENGTH));
        }

        @Override
        public void encode(ChatMessage packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.message(), MAX_LENGTH);
        }
      };
}
