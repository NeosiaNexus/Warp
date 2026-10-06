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
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.TextComponent;

import io.netty.buffer.ByteBuf;

/**
 * Server disconnects the player during gameplay ({@code S→C}).
 *
 * <p>The reason is a text component whose wire format changed in 1.20.3, from a VarInt-prefixed
 * JSON string to NBT. The proxy keeps the encoded bytes and never parses them.
 *
 * @param rawReason the encoded reason, exactly as on the wire: a VarInt-prefixed JSON string before
 *     1.20.3, an NBT tag from 1.20.3 (see {@link TextComponent})
 */
public record PlayDisconnect(byte[] rawReason) implements PlayPacket {

  /** Codec for reading and writing play disconnect packets. */
  public static final PacketCodec<PlayDisconnect> CODEC =
      new PacketCodec<>() {
        @Override
        public PlayDisconnect decode(ByteBuf buf, ProtocolVersion version) {
          byte[] rawReason = new byte[buf.readableBytes()];
          buf.readBytes(rawReason);
          return new PlayDisconnect(rawReason);
        }

        @Override
        public void encode(PlayDisconnect packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeBytes(packet.rawReason());
        }
      };

  /**
   * Creates a play disconnect whose reason is plain text, encoded for {@code version}.
   *
   * @param reason the text shown to the player
   * @param version the client's protocol version, which selects JSON or NBT
   * @return the packet
   */
  public static PlayDisconnect ofPlainText(String reason, ProtocolVersion version) {
    return new PlayDisconnect(TextComponent.plainText(reason, version));
  }
}
