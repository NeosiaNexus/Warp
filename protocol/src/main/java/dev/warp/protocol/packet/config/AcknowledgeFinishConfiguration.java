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
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Client acknowledges the end of configuration ({@code C→S, ID 0x03}).
 *
 * <p>Empty payload. This triggers the state transition from CONFIGURATION to PLAY.
 */
public record AcknowledgeFinishConfiguration() implements ConfigPacket {

  /** Codec for reading and writing acknowledge finish configuration packets. */
  public static final PacketCodec<AcknowledgeFinishConfiguration> CODEC =
      new PacketCodec<>() {
        @Override
        public AcknowledgeFinishConfiguration decode(ByteBuf buf, ProtocolVersion version) {
          return new AcknowledgeFinishConfiguration();
        }

        @Override
        public void encode(
            AcknowledgeFinishConfiguration packet, ByteBuf buf, ProtocolVersion version) {
          // no fields
        }
      };
}
