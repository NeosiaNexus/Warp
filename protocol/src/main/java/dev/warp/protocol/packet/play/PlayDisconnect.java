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

import io.netty.buffer.ByteBuf;

/**
 * Server disconnects the player during gameplay ({@code S→C}).
 *
 * <p>The reason is a text component whose wire format changed in 1.20.3 (JSON → NBT). Raw bytes are
 * preserved for format-agnostic forwarding.
 *
 * @param rawReason the raw text component bytes (JSON or NBT depending on version)
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
}
