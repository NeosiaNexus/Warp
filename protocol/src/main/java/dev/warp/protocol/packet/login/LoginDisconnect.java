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

  /** Longest reason a 1.14+ client reads, in UTF-16 code units. */
  private static final int MAX_REASON_LENGTH = 262_144;

  /** Longest reason a client before 1.14 reads, in UTF-16 code units. */
  private static final int LEGACY_MAX_REASON_LENGTH = 32_767;

  /** Codec for reading and writing login disconnect packets. */
  public static final PacketCodec<LoginDisconnect> CODEC =
      new PacketCodec<>() {
        @Override
        public LoginDisconnect decode(ByteBuf buf, ProtocolVersion version) {
          return new LoginDisconnect(McString.read(buf, maxReasonLength(version)));
        }

        @Override
        public void encode(LoginDisconnect packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.reason(), maxReasonLength(version));
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

  /**
   * Creates a login disconnect whose reason the client translates, as vanilla servers word theirs.
   *
   * @param key the translation key, such as {@code multiplayer.disconnect.invalid_public_key}
   * @return the packet, valid for every protocol version
   */
  public static LoginDisconnect ofTranslation(String key) {
    return new LoginDisconnect(TextComponent.translatableJson(key));
  }

  /**
   * Returns the longest reason a vanilla client of {@code version} reads: 32 767 UTF-16 code units
   * until 1.13.2, 262 144 from 1.14. The login packet kept the old cap through 1.13 while the other
   * text components moved to the new one.
   */
  private static int maxReasonLength(ProtocolVersion version) {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_14)
        ? MAX_REASON_LENGTH
        : LEGACY_MAX_REASON_LENGTH;
  }
}
