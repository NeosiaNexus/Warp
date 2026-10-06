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
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Client requests tab completion for a command or chat input ({@code C→S}).
 *
 * <p>The proxy may intercept this for custom command routing. This is the 1.13+ layout, with a
 * transaction ID; earlier versions send no ID (and, from 1.8, more fields after the text), so the
 * packet is not registered before 1.13 and encoding it for those versions fails.
 *
 * @param transactionId a unique identifier for this completion request (1.13+)
 * @param text the partial text to complete
 */
public record TabCompleteRequest(int transactionId, String text) implements PlayPacket {

  /** Codec for reading and writing tab complete request packets. */
  public static final PacketCodec<TabCompleteRequest> CODEC =
      new PacketCodec<>() {
        @Override
        public TabCompleteRequest decode(ByteBuf buf, ProtocolVersion version) {
          int transactionId = VarInt.read(buf);
          String text = McString.read(buf);
          return new TabCompleteRequest(transactionId, text);
        }

        @Override
        public void encode(TabCompleteRequest packet, ByteBuf buf, ProtocolVersion version) {
          VarInt.write(buf, packet.transactionId());
          McString.write(buf, packet.text());
        }
      };
}
