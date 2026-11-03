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
package dev.warp.api.network;

/**
 * Where in a connection's pipeline a plugin handler sits, and what it sees there. See the {@link
 * dev.warp.api.network package documentation} for the whole pipeline.
 *
 * <p>Both positions are duplex: one handler there sees what the connection receives (inbound, as
 * {@code channelRead}) and what it sends (outbound, as {@code write}). Messages are {@link
 * io.netty.buffer.ByteBuf}s, and Netty's reference counting applies: a handler releases what it
 * consumes, and passes on buffers it owns.
 */
public enum PipelinePosition {

  /**
   * Complete frames, decrypted, as framed on the wire: {@code [VarInt length][body]}, where the
   * body is {@code [VarInt packet id][payload]}, or, once the connection is compressed, {@code
   * [VarInt data length][payload, zlib-compressed when data length is not 0]}.
   *
   * <p>Handlers here see every frame, including the ones forwarded from the player's other
   * connection, which may arrive in their original compressed form. They cost the proxy nothing
   * beyond their own work: forwarding stays as it is. A handler that changes a frame keeps it a
   * valid frame.
   */
  FRAME,

  /**
   * One packet per message, decrypted and decompressed: {@code [VarInt packet id][payload]},
   * without length prefix, in the protocol version and state the connection is in.
   *
   * <p>Inbound, handlers see each packet the peer sent before the proxy decodes it; outbound, each
   * packet the proxy sends, including the ones forwarded from the player's other connection, before
   * it is compressed and framed. A handler may rewrite a packet, drop it, or turn it into several,
   * each in this same format.
   *
   * <p>Adding a handler here disables compression passthrough for this connection (and only this
   * one): it then inflates every frame it receives, and compresses again every packet it sends.
   *
   * <p>On a backend connection, the proxy answers the backend's player info forwarding query
   * (Velocity modern forwarding) itself, ahead of these handlers, in the backend's own protocol: a
   * translator never carries it, so forwarding works for players of any version, 1.12 and older
   * included, whose protocol has no login query.
   */
  PACKET
}
