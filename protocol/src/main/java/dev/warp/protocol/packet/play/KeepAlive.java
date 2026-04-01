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
 * Keep-alive ping/pong ({@code bidirectional}).
 *
 * <p>The server sends a random ID; the client must echo it within 15 seconds. The same record type
 * is registered in both directions.
 *
 * <p>Version history:
 *
 * <ul>
 *   <li><b>1.7.2–1.7.6</b>: ID as 32-bit int
 *   <li><b>1.8–1.12.1</b>: ID as VarInt
 *   <li><b>1.12.2+</b>: ID as 64-bit long
 * </ul>
 *
 * @param id the keep-alive identifier
 */
public record KeepAlive(long id) implements PlayPacket {

  /** Codec for reading and writing keep-alive packets. */
  public static final PacketCodec<KeepAlive> CODEC =
      new PacketCodec<>() {
        @Override
        public KeepAlive decode(ByteBuf buf, ProtocolVersion version) {
          long id;
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_12_2)) {
            id = buf.readLong();
          } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
            id = VarInt.read(buf);
          } else {
            id = buf.readInt();
          }
          return new KeepAlive(id);
        }

        @Override
        public void encode(KeepAlive packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_12_2)) {
            buf.writeLong(packet.id());
          } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
            VarInt.write(buf, (int) packet.id());
          } else {
            buf.writeInt((int) packet.id());
          }
        }
      };
}
