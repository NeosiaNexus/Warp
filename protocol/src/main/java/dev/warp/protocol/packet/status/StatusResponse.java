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
package dev.warp.protocol.packet.status;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server replies with its status as a JSON string ({@code S→C, ID 0x00}).
 *
 * <p>The JSON contains version information, player count, MOTD, and optionally a favicon. The wire
 * format (VarInt-prefixed string) has been unchanged since 1.7.2; only the JSON content evolves.
 *
 * @param json the raw JSON status response
 */
public record StatusResponse(String json) implements StatusPacket {

  /** Codec for reading and writing status response packets. */
  public static final PacketCodec<StatusResponse> CODEC =
      new PacketCodec<>() {
        @Override
        public StatusResponse decode(ByteBuf buf, ProtocolVersion version) {
          return new StatusResponse(McString.read(buf));
        }

        @Override
        public void encode(StatusResponse packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.json());
        }
      };
}
