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
import org.jspecify.annotations.Nullable;

/**
 * Reads, in place, the few fields the proxy follows in a packet it otherwise forwards untouched.
 *
 * <p>A watched packet is never decoded: the decoder runs the watch over the packet's bytes, then
 * forwards the original frame exactly as it arrived, in its compressed form whenever the other
 * connection accepts it (blind forwarding). Nothing is copied and, unless the packet changes what
 * the proxy follows, nothing is allocated. When it does, the watch returns a packet holding only
 * the fields read, which the decoder emits just before the frame.
 *
 * <p>Implementations are colocated with their packet definition as a {@code public static final}
 * field named {@code WATCH}.
 *
 * @param <T> the packet type reported
 */
@FunctionalInterface
public non-sealed interface PacketWatch<T extends Packet> extends PacketReader {

  /**
   * Reads what the proxy follows from a received packet.
   *
   * <p>The buffer is positioned immediately after the packet ID. The watch may read as far as it
   * needs and must not write: the decoder forwards the frame from its own indices. Reading less
   * than the whole packet is expected, so trailing bytes are not checked.
   *
   * @param buf the packet's bytes after its ID
   * @param version the protocol version of the connection
   * @return the fields read, or {@code null} if the packet changes nothing the proxy follows
   */
  @Nullable T watch(ByteBuf buf, ProtocolVersion version);
}
