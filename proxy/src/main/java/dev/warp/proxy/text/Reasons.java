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
package dev.warp.proxy.text;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Converts the reasons and messages plugins give as components to what the proxy sends today, and
 * the reasons backends give back to components.
 *
 * <p>Interim: messages are sent as their plain text, and reasons in network NBT (1.20.3+) are read
 * for their text only. The full component codec (styles, every version's text format) replaces both
 * directions without changing the callers.
 */
public final class Reasons {

  private static final byte TAG_END = 0;
  private static final byte TAG_STRING = 8;
  private static final byte TAG_COMPOUND = 10;

  private Reasons() {}

  /**
   * Returns the text a component shows, without styles.
   *
   * @param component the component
   * @return its plain text
   */
  public static String plain(Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  /**
   * Reads a login disconnect reason, JSON in every version.
   *
   * @param json the reason
   * @return the component; its plain text if the JSON cannot be read
   */
  public static Component fromJson(String json) {
    try {
      return GsonComponentSerializer.gson().deserialize(json);
    } catch (RuntimeException e) {
      return Component.text(json);
    }
  }

  /**
   * Reads a configuration or play disconnect reason as a backend wrote it.
   *
   * @param raw the reason's bytes: a JSON string before 1.20.3, network NBT from 1.20.3
   * @param version the protocol version of the connection
   * @return the component; never fails on malformed input
   */
  public static Component fromWire(byte[] raw, ProtocolVersion version) {
    ByteBuf buffer = Unpooled.wrappedBuffer(raw);
    try {
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_3)) {
        return fromJson(McString.read(buffer, Integer.MAX_VALUE));
      }
      return fromNbt(buffer);
    } catch (RuntimeException e) {
      return Component.empty();
    } finally {
      buffer.release();
    }
  }

  /** Reads the text of a nameless network NBT root: a string, or a compound's "text". */
  private static Component fromNbt(ByteBuf buffer) {
    byte type = buffer.readByte();
    if (type == TAG_STRING) {
      return Component.text(readModifiedUtf8(buffer));
    }
    if (type != TAG_COMPOUND) {
      return Component.empty();
    }
    StringBuilder text = new StringBuilder();
    while (buffer.isReadable()) {
      byte entry = buffer.readByte();
      if (entry == TAG_END) {
        break;
      }
      String key = readModifiedUtf8(buffer);
      if (entry != TAG_STRING) {
        // Styles and children: the interim reader keeps the root text only.
        break;
      }
      String value = readModifiedUtf8(buffer);
      if (key.equals("text") || key.equals("translate")) {
        text.append(value);
      }
    }
    return Component.text(text.toString());
  }

  private static String readModifiedUtf8(ByteBuf buffer) {
    int length = buffer.readUnsignedShort();
    byte[] bytes = new byte[length];
    buffer.readBytes(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
