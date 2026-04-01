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
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Marks the start or end of a packet bundle ({@code S→C}).
 *
 * <p>Empty payload. All packets between two delimiters are processed atomically by the client.
 * Present since 1.19.4 (protocol 762). The proxy must forward these to maintain bundle semantics.
 */
public record BundleDelimiter() implements PlayPacket {

  /** Codec for reading and writing bundle delimiter packets. */
  public static final PacketCodec<BundleDelimiter> CODEC =
      new PacketCodec<>() {
        @Override
        public BundleDelimiter decode(ByteBuf buf, ProtocolVersion version) {
          return new BundleDelimiter();
        }

        @Override
        public void encode(BundleDelimiter packet, ByteBuf buf, ProtocolVersion version) {
          // no fields
        }
      };
}
