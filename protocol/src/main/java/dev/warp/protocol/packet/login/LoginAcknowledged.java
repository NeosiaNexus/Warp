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
package dev.warp.protocol.packet.login;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Client acknowledges login success and is ready for configuration ({@code C→S, ID 0x03}).
 *
 * <p>Empty payload. Triggers the state transition from LOGIN to CONFIGURATION. Present since 1.20.2
 * (protocol 764); on older versions, the connection moves directly from LOGIN to PLAY.
 */
public record LoginAcknowledged() implements LoginPacket {

  /** Codec for reading and writing login acknowledged packets. */
  public static final PacketCodec<LoginAcknowledged> CODEC =
      new PacketCodec<>() {
        @Override
        public LoginAcknowledged decode(ByteBuf buf, ProtocolVersion version) {
          return new LoginAcknowledged();
        }

        @Override
        public void encode(LoginAcknowledged packet, ByteBuf buf, ProtocolVersion version) {
          // no fields
        }
      };
}
