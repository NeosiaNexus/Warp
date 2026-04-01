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
 * Server requests the connection to return to configuration state ({@code S→C}).
 *
 * <p>Empty payload. The client must respond with {@link AcknowledgeConfiguration}. Present since
 * 1.20.2 (protocol 764). Used during server switches to re-sync registries and resource packs.
 */
public record StartConfiguration() implements PlayPacket {

  /** Codec for reading and writing start configuration packets. */
  public static final PacketCodec<StartConfiguration> CODEC =
      new PacketCodec<>() {
        @Override
        public StartConfiguration decode(ByteBuf buf, ProtocolVersion version) {
          return new StartConfiguration();
        }

        @Override
        public void encode(StartConfiguration packet, ByteBuf buf, ProtocolVersion version) {
          // no fields
        }
      };
}
