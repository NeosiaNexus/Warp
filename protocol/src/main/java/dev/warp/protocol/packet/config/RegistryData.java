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
 * Server sends registry data for synchronisation ({@code S→C, ID 0x07}).
 *
 * <p>Contains entries for a specific registry (e.g., {@code minecraft:dimension_type}). The proxy
 * stores the registry ID and raw entry bytes for faithful replay during server switches — it does
 * not need to parse the NBT entry structures.
 *
 * @param registryId the registry identifier (e.g., {@code minecraft:dimension_type})
 * @param rawEntries the raw bytes of the entries array (VarInt count + entry data)
 */
public record RegistryData(String registryId, byte[] rawEntries) implements ConfigPacket {

  /** Codec for reading and writing registry data packets. */
  public static final PacketCodec<RegistryData> CODEC =
      new PacketCodec<>() {
        @Override
        public RegistryData decode(ByteBuf buf, ProtocolVersion version) {
          String registryId = McString.read(buf);
          byte[] rawEntries = new byte[buf.readableBytes()];
          buf.readBytes(rawEntries);
          return new RegistryData(registryId, rawEntries);
        }

        @Override
        public void encode(RegistryData packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.registryId());
          buf.writeBytes(packet.rawEntries());
        }
      };
}
