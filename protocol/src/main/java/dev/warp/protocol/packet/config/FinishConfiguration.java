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
 * Server signals the end of the configuration phase ({@code S→C, ID 0x03}).
 *
 * <p>Empty payload. The client must respond with {@link AcknowledgeFinishConfiguration} before the
 * connection transitions to PLAY.
 */
public record FinishConfiguration() implements ConfigPacket {

  /** Codec for reading and writing finish configuration packets. */
  public static final PacketCodec<FinishConfiguration> CODEC =
      new PacketCodec<>() {
        @Override
        public FinishConfiguration decode(ByteBuf buf, ProtocolVersion version) {
          return new FinishConfiguration();
        }

        @Override
        public void encode(FinishConfiguration packet, ByteBuf buf, ProtocolVersion version) {
          // no fields
        }
      };
}
