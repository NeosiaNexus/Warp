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
 * Client acknowledges the return to configuration state ({@code C→S}).
 *
 * <p>Empty payload. Triggers the state transition from PLAY back to CONFIGURATION. Present since
 * 1.20.2 (protocol 764).
 */
public record AcknowledgeConfiguration() implements PlayPacket {

  /** Codec for reading and writing acknowledge configuration packets. */
  public static final PacketCodec<AcknowledgeConfiguration> CODEC =
      new PacketCodec<>() {
        @Override
        public AcknowledgeConfiguration decode(ByteBuf buf, ProtocolVersion version) {
          return new AcknowledgeConfiguration();
        }

        @Override
        public void encode(AcknowledgeConfiguration packet, ByteBuf buf, ProtocolVersion version) {
          // no fields
        }
      };
}
