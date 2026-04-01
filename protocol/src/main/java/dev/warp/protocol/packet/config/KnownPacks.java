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
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Known packs negotiation ({@code bidirectional}).
 *
 * <p>The server sends its known data packs; the client responds with the subset it already has
 * cached. Present since 1.20.5 (protocol 766). The same record type is used for both directions.
 *
 * @param packs the list of known data packs
 */
public record KnownPacks(List<Pack> packs) implements ConfigPacket {

  /**
   * A known data pack entry.
   *
   * @param namespace the pack namespace
   * @param id the pack identifier
   * @param version the pack version string
   */
  public record Pack(String namespace, String id, String version) {}

  /** Codec for reading and writing known packs packets. */
  public static final PacketCodec<KnownPacks> CODEC =
      new PacketCodec<>() {
        @Override
        public KnownPacks decode(ByteBuf buf, ProtocolVersion version) {
          int count = VarInt.read(buf);
          if (count > 128) {
            throw new DecoderException("Too many packs: " + count + " (max 128)");
          }
          List<Pack> packs = new ArrayList<>(count);
          for (int i = 0; i < count; i++) {
            String namespace = McString.read(buf);
            String id = McString.read(buf);
            String packVersion = McString.read(buf);
            packs.add(new Pack(namespace, id, packVersion));
          }
          return new KnownPacks(List.copyOf(packs));
        }

        @Override
        public void encode(KnownPacks packet, ByteBuf buf, ProtocolVersion version) {
          VarInt.write(buf, packet.packs().size());
          for (Pack pack : packet.packs()) {
            McString.write(buf, pack.namespace());
            McString.write(buf, pack.id());
            McString.write(buf, pack.version());
          }
        }
      };
}
