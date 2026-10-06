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
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.TextComponent;

import io.netty.buffer.ByteBuf;

/**
 * Server disconnects the client during login ({@code S→C, ID 0x00}).
 *
 * <p>The reason is a JSON text component in a VarInt-prefixed string, in every version: 1.20.3
 * moved the configuration and play disconnect reasons to NBT, not this one.
 *
 * @param reason the reason, a JSON text component such as {@code {"text":"Invalid username"}}
 */
public record LoginDisconnect(String reason) implements LoginPacket {

  /** Longest reason a client accepts, in UTF-16 code units (vanilla's JSON text component cap). */
  private static final int MAX_REASON_LENGTH = 262_144;

  /** Codec for reading and writing login disconnect packets. */
  public static final PacketCodec<LoginDisconnect> CODEC =
      new PacketCodec<>() {
        @Override
        public LoginDisconnect decode(ByteBuf buf, ProtocolVersion version) {
          return new LoginDisconnect(McString.read(buf, MAX_REASON_LENGTH));
        }

        @Override
        public void encode(LoginDisconnect packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.reason(), MAX_REASON_LENGTH);
        }
      };

  /**
   * Creates a login disconnect whose reason is plain text.
   *
   * @param reason the text shown to the player
   * @return the packet, valid for every protocol version
   */
  public static LoginDisconnect ofPlainText(String reason) {
    return new LoginDisconnect(TextComponent.plainTextJson(reason));
  }
}
