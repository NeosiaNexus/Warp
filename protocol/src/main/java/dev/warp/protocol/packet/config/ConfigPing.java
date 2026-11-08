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
package dev.warp.protocol.packet.config;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * A server's ping in the configuration state ({@code S→C}, 1.20.2+): the client answers at once
 * with a {@link ConfigPong} carrying the same id, whatever else it is doing. The proxy sends one at
 * the start of its probe of an unknown player, to measure the round trip and to check that the
 * client runs a configuration state machine at all.
 *
 * @param id the id the client must echo, a plain {@code int}
 */
public record ConfigPing(int id) implements ConfigPacket {

  /** Codec for reading and writing configuration pings. */
  public static final PacketCodec<ConfigPing> CODEC =
      new PacketCodec<>() {
        @Override
        public ConfigPing decode(ByteBuf buf, ProtocolVersion version) {
          return new ConfigPing(buf.readInt());
        }

        @Override
        public void encode(ConfigPing packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeInt(packet.id());
        }
      };
}
