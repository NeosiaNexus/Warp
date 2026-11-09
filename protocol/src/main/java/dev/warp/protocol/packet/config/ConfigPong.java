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
import dev.warp.protocol.packet.PacketWatch;

import io.netty.buffer.ByteBuf;

/**
 * The client's answer to a {@link ConfigPing} ({@code C→S}, 1.20.2+), carrying the ping's id.
 *
 * @param id the id of the ping answered
 */
public record ConfigPong(int id) implements ConfigPacket {

  /** Codec for reading and writing configuration pongs. */
  public static final PacketCodec<ConfigPong> CODEC =
      new PacketCodec<>() {
        @Override
        public ConfigPong decode(ByteBuf buf, ProtocolVersion version) {
          return new ConfigPong(buf.readInt());
        }

        @Override
        public void encode(ConfigPong packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeInt(packet.id());
        }
      };

  /** Watch of the client's pongs: read whole, and the frame forwarded untouched. */
  public static final PacketWatch<ConfigPong> WATCH =
      (buf, version) -> {
        try {
          return CODEC.decode(buf, version);
        } catch (RuntimeException malformed) {
          // Not one the proxy can read: forwarded as it is, like every other frame of the client,
          // and judged by whoever waited for it as never having come.
          return null;
        }
      };
}
