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
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketWatch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;

/**
 * Plugin message in the configuration state ({@code bidirectional}).
 *
 * <p>Used for channels like {@code minecraft:brand}. The same record type is registered in both
 * serverbound and clientbound directions since the wire format is identical.
 *
 * @param channel the plugin channel identifier (e.g., {@code minecraft:brand})
 * @param data the channel-specific payload (remaining bytes after channel)
 */
public record ConfigPluginMessage(String channel, byte[] data) implements ConfigPacket {

  /** The channel a client declares its brand on, as it enters the configuration state. */
  public static final String BRAND_CHANNEL = "minecraft:brand";

  /**
   * The most bytes a brand may take (rule 8 of the anti-bot research's false-positive rules: no
   * allow-list, printable text of at most 128 bytes).
   */
  public static final int MAX_BRAND_BYTES = 128;

  /** Codec for reading and writing configuration plugin messages. */
  public static final PacketCodec<ConfigPluginMessage> CODEC =
      new PacketCodec<>() {
        @Override
        public ConfigPluginMessage decode(ByteBuf buf, ProtocolVersion version) {
          String channel = McString.read(buf);
          byte[] data = new byte[buf.readableBytes()];
          buf.readBytes(data);
          return new ConfigPluginMessage(channel, data);
        }

        @Override
        public void encode(ConfigPluginMessage packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.channel());
          buf.writeBytes(packet.data());
        }
      };

  /**
   * Watch of the client's configuration plugin messages: reports the brand message only, with at
   * most {@value #MAX_BRAND_BYTES} + 2 bytes of its data (a longer one is not a brand, see {@link
   * #brand()}); every message, the brand's included, is forwarded untouched. A message no client
   * sends (an unreadable channel) reports nothing: the anti-bot judges the client on what it does
   * not send, never on a frame it cannot read.
   */
  public static final PacketWatch<ConfigPluginMessage> BRAND_WATCH =
      (buf, version) -> {
        try {
          String channel = McString.read(buf);
          if (!BRAND_CHANNEL.equals(channel)) {
            return null;
          }
          byte[] data = new byte[Math.min(buf.readableBytes(), MAX_BRAND_BYTES + 3)];
          buf.readBytes(data);
          return new ConfigPluginMessage(channel, data);
        } catch (RuntimeException malformed) {
          return null;
        }
      };

  /**
   * Returns the brand this message declares, if it is a well-formed brand message: on {@link
   * #BRAND_CHANNEL}, one string of 1 to {@value #MAX_BRAND_BYTES} bytes without control characters
   * and nothing after it. Every known client's brand ({@code vanilla}, {@code fabric}, {@code
   * forge}, {@code Geyser}, launchers' names) reads this way.
   *
   * @return the brand, or {@code null} if the message is not a valid brand message
   */
  public @Nullable String brand() {
    if (!BRAND_CHANNEL.equals(channel) || data.length > MAX_BRAND_BYTES + 2) {
      return null;
    }
    ByteBuf buf = Unpooled.wrappedBuffer(data);
    try {
      String brand = McString.read(buf, MAX_BRAND_BYTES);
      if (buf.isReadable() || brand.isEmpty()) {
        return null;
      }
      for (int i = 0; i < brand.length(); i++) {
        if (Character.isISOControl(brand.charAt(i))) {
          return null;
        }
      }
      return brand;
    } catch (RuntimeException malformed) {
      return null;
    }
  }
}
