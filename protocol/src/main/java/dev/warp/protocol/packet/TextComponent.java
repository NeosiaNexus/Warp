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
import dev.warp.protocol.codec.McString;

import java.util.HexFormat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

/**
 * Builds the wire form of a plain text component: the reason of a disconnect, the content of a
 * system message.
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

  /** NBT {@code TAG_String} type id. */
  private static final int TAG_STRING = 0x08;

  /** Longest {@code TAG_String} payload in bytes: its length prefix is an unsigned short. */
  private static final int MAX_NBT_STRING_BYTES = 0xFFFF;

  private static final HexFormat HEX = HexFormat.of();

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
   *     JSON, or 65 535 bytes of modified UTF-8
   */
  public static byte[] plainText(String text, ProtocolVersion version) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
      return nbtString(text);
    }
    return jsonString(plainTextJson(text));
  }

  /**
   * Returns the JSON text component holding plain text, {@code {"text":"..."}}, the text escaped as
   * a JSON string requires (RFC 8259, section 7).
   *
   * <p>This is the form of the login disconnect reason in every version.
   *
   * @param text the plain text
   * @return the JSON text component
   */
  public static String plainTextJson(String text) {
    return "{\"text\":\"" + escapeJson(text) + "\"}";
  }

  /**
   * Returns the JSON text component the client translates into its own language, {@code
   * {"translate":"..."}}, the key escaped as a JSON string requires (RFC 8259, section 7).
   *
   * <p>This is how vanilla servers and Velocity word the reasons the game defines, such as {@code
   * multiplayer.disconnect.invalid_public_key}.
   *
   * @param key the translation key
   * @return the JSON text component
   */
  public static String translatableJson(String key) {
    return "{\"translate\":\"" + escapeJson(key) + "\"}";
  }

  // ---------------------------------------------------------------------------
  // Wire formats
  // ---------------------------------------------------------------------------

  /** Encodes {@code json} as a protocol string: VarInt byte length, then UTF-8. */
  private static byte[] jsonString(String json) {
    ByteBuf buf = Unpooled.buffer(McString.encodedSize(json));
    try {
      McString.write(buf, json);
      return ByteBufUtil.getBytes(buf);
    } finally {
      buf.release();
    }
  }

  /**
   * Encodes {@code text} as a network NBT string tag (no root name, as since 1.20.2).
   *
   * <p>NBT strings are modified UTF-8, which the client decodes with {@link
   * java.io.DataInput#readUTF}: U+0000 takes two bytes, and a supplementary character is a
   * surrogate pair of three bytes each. Standard UTF-8 differs for those characters only, and makes
   * the client fail to decode the packet.
   */
  private static byte[] nbtString(String text) {
    int length = modifiedUtf8Length(text);
    if (length > MAX_NBT_STRING_BYTES) {
      throw new IllegalArgumentException(
          "Text too long for an NBT string: "
              + length
              + " bytes of modified UTF-8 (max "
              + MAX_NBT_STRING_BYTES
              + ")");
    }
    byte[] encoded = new byte[3 + length];
    encoded[0] = TAG_STRING;
    encoded[1] = (byte) (length >>> 8);
    encoded[2] = (byte) length;
    int index = 3;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c != 0 && c < 0x80) {
        encoded[index++] = (byte) c;
      } else if (c < 0x800) {
        encoded[index++] = (byte) (0xC0 | (c >> 6));
        encoded[index++] = (byte) (0x80 | (c & 0x3F));
      } else {
        encoded[index++] = (byte) (0xE0 | (c >> 12));
        encoded[index++] = (byte) (0x80 | ((c >> 6) & 0x3F));
        encoded[index++] = (byte) (0x80 | (c & 0x3F));
      }
    }
    return encoded;
  }

  /** Returns the modified UTF-8 byte length of {@code text}. */
  private static int modifiedUtf8Length(String text) {
    int length = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c != 0 && c < 0x80) {
        length += 1;
      } else if (c < 0x800) {
        length += 2;
      } else {
        length += 3;
      }
    }
    return length;
  }

  /** Escapes {@code text} for a JSON string literal: quote, backslash and control characters. */
  private static String escapeJson(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append("\\u00").append(HEX.toHexDigits((byte) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }
}
