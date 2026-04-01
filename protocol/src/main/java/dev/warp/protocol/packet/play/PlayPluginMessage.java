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
 * Plugin message in the play state ({@code bidirectional}).
 *
 * <p>Used for proxy channels ({@code bungeecord:main}, {@code warp:main}), brand exchange ({@code
 * minecraft:brand}), and plugin-defined channels. The same record type is registered in both
 * directions.
 *
 * <p>Channel name format changed in 1.13 from plain strings ({@code "MC|Brand"}) to namespaced
 * identifiers ({@code "minecraft:brand"}), but the wire format (VarInt-prefixed string + remaining
 * bytes) is identical.
 *
 * @param channel the plugin channel identifier
 * @param data the channel-specific payload
 */
public record PlayPluginMessage(String channel, byte[] data) implements PlayPacket {

  /** Codec for reading and writing play plugin message packets. */
  public static final PacketCodec<PlayPluginMessage> CODEC =
      new PacketCodec<>() {
        @Override
        public PlayPluginMessage decode(ByteBuf buf, ProtocolVersion version) {
          String channel = McString.read(buf);
          byte[] data = new byte[buf.readableBytes()];
          buf.readBytes(data);
          return new PlayPluginMessage(channel, data);
        }

        @Override
        public void encode(PlayPluginMessage packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.channel());
          buf.writeBytes(packet.data());
        }
      };
}
