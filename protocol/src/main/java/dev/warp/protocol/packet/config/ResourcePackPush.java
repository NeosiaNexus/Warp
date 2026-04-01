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
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.packet.PacketCodec;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

/**
 * Server pushes a resource pack to the client ({@code S→C, ID 0x08}).
 *
 * @param uuid the unique identifier for this resource pack
 * @param url the download URL
 * @param hash the SHA-1 hash of the pack (hex, max 40 chars)
 * @param forced whether the client must accept the pack
 * @param promptMessage the optional prompt message shown to the client (raw text component bytes)
 */
public record ResourcePackPush(
    UUID uuid, String url, String hash, boolean forced, byte @Nullable [] promptMessage)
    implements ConfigPacket {

  /** Codec for reading and writing resource pack push packets. */
  public static final PacketCodec<ResourcePackPush> CODEC =
      new PacketCodec<>() {
        @Override
        public ResourcePackPush decode(ByteBuf buf, ProtocolVersion version) {
          UUID uuid = McUuid.read(buf);
          String url = McString.read(buf);
          String hash = McString.read(buf, 40);
          boolean forced = buf.readBoolean();
          byte @Nullable [] promptMessage = null;
          if (buf.readBoolean()) {
            promptMessage = new byte[buf.readableBytes()];
            buf.readBytes(promptMessage);
          }
          return new ResourcePackPush(uuid, url, hash, forced, promptMessage);
        }

        @Override
        public void encode(ResourcePackPush packet, ByteBuf buf, ProtocolVersion version) {
          McUuid.write(buf, packet.uuid());
          McString.write(buf, packet.url());
          McString.write(buf, packet.hash(), 40);
          buf.writeBoolean(packet.forced());
          buf.writeBoolean(packet.promptMessage() != null);
          if (packet.promptMessage() != null) {
            buf.writeBytes(packet.promptMessage());
          }
        }
      };
}
