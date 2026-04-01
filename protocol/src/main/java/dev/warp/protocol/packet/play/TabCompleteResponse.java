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
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server responds with tab completion suggestions ({@code S→C}).
 *
 * <p>The proxy may merge suggestions from multiple backend servers or add its own. The raw matches
 * data is preserved for faithful forwarding.
 *
 * @param transactionId the matching request's transaction ID
 * @param rawMatches the raw bytes of (start, length, matches array)
 */
public record TabCompleteResponse(int transactionId, byte[] rawMatches) implements PlayPacket {

  /** Codec for reading and writing tab complete response packets. */
  public static final PacketCodec<TabCompleteResponse> CODEC =
      new PacketCodec<>() {
        @Override
        public TabCompleteResponse decode(ByteBuf buf, ProtocolVersion version) {
          int transactionId = VarInt.read(buf);
          byte[] rawMatches = new byte[buf.readableBytes()];
          buf.readBytes(rawMatches);
          return new TabCompleteResponse(transactionId, rawMatches);
        }

        @Override
        public void encode(TabCompleteResponse packet, ByteBuf buf, ProtocolVersion version) {
          VarInt.write(buf, packet.transactionId());
          buf.writeBytes(packet.rawMatches());
        }
      };
}
