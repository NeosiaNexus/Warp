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
package dev.warp.protocol.packet;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.text.ComponentCodec;

import net.kyori.adventure.text.Component;

/**
 * Builds the wire form of a plain text component: the reason of a disconnect, the content of a
 * system message. A shorthand for {@link ComponentCodec}, which encodes any Adventure component.
 *
 * <p>The format depends on the protocol version and, for disconnects, on the state:
 *
 * <ul>
 *   <li><b>Before 1.20.3</b>: a VarInt-prefixed JSON string, {@code {"text":"..."}}
 *   <li><b>1.20.3+</b>: a network NBT {@code TAG_String}: type byte {@code 0x08}, unsigned-short
 *       byte length, modified UTF-8. A string tag is the NBT form of a component holding only text.
 *   <li><b>Login state, every version</b>: the JSON string, built from {@link #plainTextJson}.
 *       1.20.3 moved the configuration and play states to NBT, not the login state.
 * </ul>
 */
public final class TextComponent {

  /**
   * The JSON dialect of plain text and translation keys sent to any version: that of 1.9 to 1.20.2
   * servers, a text object without HTML escaping, which every version reads.
   */
  private static final ProtocolVersion EVERY_VERSION = ProtocolVersion.MINECRAFT_1_20_2;

  private TextComponent() {}

  /**
   * Encodes plain text as a text component in the format of {@code version}'s configuration and
   * play states.
   *
   * @param text the plain text
   * @param version the protocol version of the receiving client
   * @return the complete field as written on the wire: a VarInt-prefixed JSON string before 1.20.3,
   *     an NBT string tag from 1.20.3
   * @throws IllegalArgumentException if the text is too long for the format: 32 767 characters of
   *     JSON (262 144 from 1.14), or 65 535 bytes of modified UTF-8
   */
  public static byte[] plainText(String text, ProtocolVersion version) {
    return ComponentCodec.encode(Component.text(text), version);
  }

  /**
   * Returns the JSON text component holding plain text, {@code {"text":"..."}}, the text escaped as
   * a JSON string requires (RFC 8259, section 7), in a form every version reads.
   *
   * <p>This is the form of the login disconnect reason in every version.
   *
   * @param text the plain text
   * @return the JSON text component
   */
  public static String plainTextJson(String text) {
    return ComponentCodec.json(Component.text(text), EVERY_VERSION);
  }

  /**
   * Returns the JSON text component the client translates into its own language, {@code
   * {"translate":"..."}}, the key escaped as a JSON string requires (RFC 8259, section 7).
   *
   * <p>This is how vanilla servers and Velocity word the reasons the game defines, such as {@code
   * multiplayer.disconnect.invalid_public_key_signature}.
   *
   * @param key the translation key
   * @return the JSON text component
   */
  public static String translatableJson(String key) {
    return ComponentCodec.json(Component.translatable(key), EVERY_VERSION);
  }
}
