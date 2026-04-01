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
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Plugin message in the configuration state ({@code bidirectional}).
 *
 * <p>Used for channels like {@code minecraft:brand}. The same record type is registered in both
 * serverbound and clientbound directions since the wire format is identical.
 *
 * @param channel the plugin channel identifier (e.g., {@code minecraft:brand})
 * @param data the channel-specific payload (remaining bytes after channel)
 */
public record ConfigPluginMessage(String channel, byte[] data) implements ConfigPacket {

  /** Codec for reading and writing configuration plugin message packets. */
  public static final PacketCodec<ConfigPluginMessage> CODEC =
      new PacketCodec<>() {
        @Override
        public ConfigPluginMessage decode(ByteBuf buf, ProtocolVersion version) {
          String channel = McString.read(buf);
          byte[] data = new byte[buf.readableBytes()];
          buf.readBytes(data);
          return new ConfigPluginMessage(channel, data);
        }

        @Override
        public void encode(ConfigPluginMessage packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.channel());
          buf.writeBytes(packet.data());
        }
      };
}
