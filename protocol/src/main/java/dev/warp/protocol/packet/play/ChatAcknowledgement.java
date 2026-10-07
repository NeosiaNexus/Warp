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
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Client acknowledges the signed chat messages it has seen ({@code C→S}, from 1.19.3): "Acknowledge
 * Message" in the protocol documentation.
 *
 * <p>From 1.19.3 a server keeps, for each player, a window over the signed chat messages it sent
 * them. Every chat message and every command the client sends carries a last-seen update: an
 * offset, how many messages the client has seen since its previous update, then which of the last
 * 20 it acknowledges. The server moves its window by the offset, then checks the acknowledgements
 * against it, and kicks the player when they do not match. A client that has seen more than 64
 * messages without saying anything sends this packet, the offset alone.
 *
 * <p>The proxy sends it in the client's place when it keeps a command from the backend: the backend
 * moves its window as the command would have, and stays in step with the client.
 *
 * <p>Layout: VarInt offset (vanilla {@code ServerboundChatAckPacket}, Velocity's {@code
 * ChatAcknowledgementPacket}, minecraft-data's {@code message_acknowledgement}).
 *
 * @param offset how many messages the client saw since its previous last-seen update
 */
public record ChatAcknowledgement(int offset) implements PlayPacket {

  /** Rejects a negative offset, which a server refuses (and kicks the player for). */
  public ChatAcknowledgement {
    if (offset < 0) {
      throw new IllegalArgumentException("Negative last-seen offset: " + offset);
    }
  }

  /** Codec for reading and writing chat acknowledgement packets. */
  public static final PacketCodec<ChatAcknowledgement> CODEC =
      new PacketCodec<>() {
        @Override
        public ChatAcknowledgement decode(ByteBuf buf, ProtocolVersion version) {
          return new ChatAcknowledgement(VarInt.read(buf));
        }

        @Override
        public void encode(ChatAcknowledgement packet, ByteBuf buf, ProtocolVersion version) {
          VarInt.write(buf, packet.offset());
        }
      };
}
