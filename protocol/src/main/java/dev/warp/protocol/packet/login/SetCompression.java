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

/**
 * Server sets the compression threshold ({@code S→C, ID 0x03}).
 *
 * <p>Packets equal to or larger than the threshold are compressed. A threshold of {@code -1}
 * disables compression. Present since 1.8.
 *
 * @param threshold the minimum packet size for compression, or {@code -1} to disable
 */
public record SetCompression(int threshold) implements LoginPacket {

  /** Codec for reading and writing set compression packets. */
  public static final PacketCodec<SetCompression> CODEC =
      new PacketCodec<>() {
        @Override
        public SetCompression decode(ByteBuf buf, ProtocolVersion version) {
          return new SetCompression(VarInt.read(buf));
        }

        @Override
        public void encode(SetCompression packet, ByteBuf buf, ProtocolVersion version) {
          VarInt.write(buf, packet.threshold());
        }
      };
}
