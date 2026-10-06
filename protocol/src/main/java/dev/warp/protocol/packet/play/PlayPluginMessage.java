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
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

/**
 * Plugin message in the play state ({@code bidirectional}).
 *
 * <p>Used for proxy channels ({@code bungeecord:main}, {@code warp:main}), brand exchange ({@code
 * minecraft:brand}), and plugin-defined channels. The same record type is registered in both
 * directions.
 *
 * <p>Layout: the channel (string), then the payload. From 1.8 the payload runs to the end of the
 * packet. In 1.7 it is behind its length: a short in vanilla (minecraft-data's {@code buffer} with
 * an {@code i16} count), which Forge extends to three bytes for payloads of 32 KiB and more: when
 * the short's top bit is set, its 15 low bits are the length's low bits and the next byte holds the
 * bits above, as Velocity's {@code ProtocolUtils#readExtendedForgeShort} reads it. Below 32 KiB
 * both forms are the same two bytes. Channel names changed in 1.13 from plain strings ({@code
 * "MC|Brand"}) to namespaced identifiers ({@code "minecraft:brand"}); the record carries them as
 * they are.
 *
 * @param channel the plugin channel identifier
 * @param data the channel-specific payload
 */
public record PlayPluginMessage(String channel, byte[] data) implements PlayPacket {

  /** Largest 1.7 payload: Forge's limit, Velocity's {@code FORGE_MAX_ARRAY_LENGTH}. */
  static final int MAX_LEGACY_DATA_LENGTH = 0x1FFF9A;

  /** Top bit of the first two bytes of a 1.7 length: a third byte follows (Forge). */
  private static final int EXTENDED = 0x8000;

  /** Codec for reading and writing play plugin message packets. */
  public static final PacketCodec<PlayPluginMessage> CODEC =
      new PacketCodec<>() {
        @Override
        public PlayPluginMessage decode(ByteBuf buf, ProtocolVersion version) {
          String channel = McString.read(buf);
          int length =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)
                  ? buf.readableBytes()
                  : readLegacyLength(buf);
          byte[] data = new byte[length];
          buf.readBytes(data);
          return new PlayPluginMessage(channel, data);
        }

        @Override
        public void encode(PlayPluginMessage packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.channel());
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
            writeLegacyLength(buf, packet.data().length);
          }
          buf.writeBytes(packet.data());
        }
      };

  /** Reads the length of a 1.7 payload, two bytes or Forge's three, and checks the bytes exist. */
  private static int readLegacyLength(ByteBuf buf) {
    int low = buf.readUnsignedShort();
    int length = (low & EXTENDED) == 0 ? low : (low & ~EXTENDED) | (buf.readUnsignedByte() << 15);
    if (length > MAX_LEGACY_DATA_LENGTH || !buf.isReadable(length)) {
      throw new DecoderException(
          "Invalid 1.7 plugin message length " + length + " (" + buf.readableBytes() + " left)");
    }
    return length;
  }

  /** Writes the length of a 1.7 payload: a short, or Forge's three bytes from 32 KiB. */
  private static void writeLegacyLength(ByteBuf buf, int length) {
    if (length > MAX_LEGACY_DATA_LENGTH) {
      throw new EncoderException(
          "1.7 plugin message payload too large: " + length + " > " + MAX_LEGACY_DATA_LENGTH);
    }
    if (length < EXTENDED) {
      buf.writeShort(length);
    } else {
      buf.writeShort(EXTENDED | (length & 0x7FFF));
      buf.writeByte(length >>> 15);
    }
  }
}
