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
import dev.warp.protocol.codec.VarInt;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

/**
 * Utility for constructing text component wire representations.
 *
 * <p>Disconnect packets and chat messages require a text component payload whose wire format
 * depends on the protocol version:
 *
 * <ul>
 *   <li><b>Pre-1.20.3</b>: VarInt-prefixed JSON string ({@code {"text":"..."}})
 *   <li><b>1.20.3+</b>: NBT string tag (type byte {@code 0x08} + unsigned-short length + UTF-8)
 * </ul>
 */
public final class TextComponent {

  /** NBT TAG_String type ID. */
  private static final int TAG_STRING = 0x08;

  private TextComponent() {}

  /**
   * Encodes a plain text message as a text component in the version-appropriate wire format.
   *
   * @param text the plain text message
   * @param version the protocol version
   * @return the raw bytes ready to be used in disconnect/chat packets
   */
  public static byte[] plainText(String text, ProtocolVersion version) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
      return nbtString(text);
    }
    return jsonText(text);
  }

  /** Encodes as a VarInt-prefixed JSON string (pre-1.20.3). */
  private static byte[] jsonText(String text) {
    String json = "{\"text\":\"" + escapeJson(text) + "\"}";
    int byteCount = ByteBufUtil.utf8Bytes(json);
    ByteBuf buf = Unpooled.buffer(VarInt.size(byteCount) + byteCount);
    try {
      McString.write(buf, json);
      byte[] result = new byte[buf.readableBytes()];
      buf.readBytes(result);
      return result;
    } finally {
      buf.release();
    }
  }

  /**
   * Encodes as an NBT string tag (1.20.3+).
   *
   * <p>Format: {@code 0x08 (TAG_String) + unsigned-short length + UTF-8 text}. The text is the raw
   * message content — Minecraft accepts plain strings as text components in NBT.
   */
  private static byte[] nbtString(String text) {
    byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
    ByteBuf buf = Unpooled.buffer(1 + 2 + utf8.length);
    try {
      buf.writeByte(TAG_STRING);
      buf.writeShort(utf8.length);
      buf.writeBytes(utf8);
      byte[] result = new byte[buf.readableBytes()];
      buf.readBytes(result);
      return result;
    } finally {
      buf.release();
    }
  }

  /** Escapes a string for safe inclusion in a JSON string literal. */
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
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }
}
