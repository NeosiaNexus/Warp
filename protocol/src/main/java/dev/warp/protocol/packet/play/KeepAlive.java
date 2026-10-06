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
 * <p>The server sends an ID; the client must echo it unchanged. The same record type is registered
 * in both directions.
 *
 * <p>Version history:
 *
 * <ul>
 *   <li><b>1.7.2 to 1.7.6</b>: ID as 32-bit int
 *   <li><b>1.8 to 1.12.1</b>: ID as VarInt (still 32-bit)
 *   <li><b>1.12.2+</b>: ID as 64-bit long
 * </ul>
 *
 * <p>The record holds a {@code long} for every version. Before 1.12.2, {@link #CODEC} refuses to
 * encode an ID outside the {@code int} range instead of truncating it: the client would echo the
 * truncated value, which never matches the ID the sender is waiting for.
 *
 * @param id the keep-alive identifier; it must fit in an {@code int} unless {@link
 *     #hasLongId(ProtocolVersion)} holds for the connection's version
 */
public record KeepAlive(long id) implements PlayPacket {

  /** Codec for reading and writing keep-alive packets. */
  public static final PacketCodec<KeepAlive> CODEC =
      new PacketCodec<>() {
        @Override
        public KeepAlive decode(ByteBuf buf, ProtocolVersion version) {
          long id;
          if (hasLongId(version)) {
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
          if (hasLongId(version)) {
            buf.writeLong(packet.id());
          } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
            VarInt.write(buf, intId(packet, version));
          } else {
            buf.writeInt(intId(packet, version));
          }
        }
      };

  /**
   * Returns whether {@code version} carries the keep-alive ID as a 64-bit {@code long}, which is
   * the case from 1.12.2 on. Older versions carry a 32-bit ID, so an ID generated for them must lie
   * in the {@code int} range.
   *
   * @param version the protocol version of the connection
   * @return {@code true} if every {@code long} ID survives the wire format of {@code version}
   */
  public static boolean hasLongId(ProtocolVersion version) {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_12_2);
  }

  /** Narrows the ID to the 32 bits of a pre-1.12.2 wire format, refusing to drop any bit. */
  private static int intId(KeepAlive packet, ProtocolVersion version) {
    int id = (int) packet.id();
    if (id != packet.id()) {
      throw new IllegalArgumentException(
          "KeepAlive id " + packet.id() + " does not fit the 32-bit id of " + version);
    }
    return id;
  }
}
