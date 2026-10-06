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
package dev.warp.protocol.packet;

import dev.warp.protocol.ProtocolVersion;

import io.netty.buffer.ByteBuf;

/**
 * Reads and writes a specific packet type from a Netty {@link ByteBuf}.
 *
 * <p>Implementations are colocated with their packet definition as a {@code public static final}
 * field named {@code CODEC}. The {@link ProtocolVersion} parameter enables version-dependent
 * encoding within a single codec instance. As a {@link PacketReader}, a codec decodes the packets
 * registered as decoded; for the others it only writes.
 *
 * @param <T> the packet type this codec handles
 */
public non-sealed interface PacketCodec<T extends Packet> extends PacketReader {

  /**
   * Decodes a packet from the buffer, advancing the reader index past all consumed bytes.
   *
   * <p>The buffer is positioned immediately after the packet ID VarInt — the codec reads only the
   * packet's payload fields.
   *
   * @param buf the buffer to read from
   * @param version the protocol version of the connection
   * @return the decoded packet
   */
  T decode(ByteBuf buf, ProtocolVersion version);

  /**
   * Encodes a packet into the buffer, advancing the writer index.
   *
   * <p>The packet ID has already been written — the codec writes only the payload fields.
   *
   * @param packet the packet to encode
   * @param buf the buffer to write to
   * @param version the protocol version of the connection
   */
  void encode(T packet, ByteBuf buf, ProtocolVersion version);
}
