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
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;

/**
 * Server stops sounds ({@code S→C}, from 1.13): every sound, those of a source, those of a key, or
 * those of a key in a source.
 *
 * <p>Layout: a byte of flags, {@code 1} when a source follows (a VarInt), {@code 2} when a sound
 * key follows (minecraft-data's {@code packet_stop_sound}). From 1.9.4 to 1.12.2 the same request
 * travels in the {@code MC|StopSound} plugin message (see {@link #legacyPluginMessage}); before
 * 1.9.4 a client cannot stop a sound the server started.
 *
 * @param source the sound source's wire id (see {@link SoundEffect#source}), or {@code -1} for
 *     every source
 * @param sound the sound's key, or {@code null} for every sound
 */
public record StopSound(int source, @Nullable String sound) implements PlayPacket {

  /** The plugin message channel that stops sounds from 1.9.4 to 1.12.2. */
  public static final String LEGACY_CHANNEL = "MC|StopSound";

  /** Flag set when the packet names a source. */
  private static final int HAS_SOURCE = 1;

  /** Flag set when the packet names a sound. */
  private static final int HAS_SOUND = 2;

  /** The names of the sources, by wire id, as the {@code MC|StopSound} message carries them. */
  private static final String[] LEGACY_SOURCE_NAMES = {
    "master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient",
    "voice"
  };

  /** Codec for reading and writing stop sound packets. */
  public static final PacketCodec<StopSound> CODEC =
      new PacketCodec<>() {
        @Override
        public StopSound decode(ByteBuf buf, ProtocolVersion version) {
          byte flags = buf.readByte();
          int source = (flags & HAS_SOURCE) != 0 ? VarInt.read(buf) : -1;
          String sound = (flags & HAS_SOUND) != 0 ? McString.read(buf) : null;
          return new StopSound(source, sound);
        }

        @Override
        public void encode(StopSound packet, ByteBuf buf, ProtocolVersion version) {
          String sound = packet.sound();
          int flags = (packet.source() >= 0 ? HAS_SOURCE : 0) | (sound != null ? HAS_SOUND : 0);
          buf.writeByte(flags);
          if (packet.source() >= 0) {
            VarInt.write(buf, packet.source());
          }
          if (sound != null) {
            McString.write(buf, sound);
          }
        }
      };

  /**
   * Returns the {@code MC|StopSound} plugin message a 1.9.4 to 1.12.2 client stops sounds with: the
   * source's name then the sound's key, each empty for all of them (1.12.2 {@code
   * NetHandlerPlayClient#handleCustomPayload}).
   *
   * @return the plugin message
   */
  public PlayPluginMessage legacyPluginMessage() {
    ByteBuf buf = Unpooled.buffer();
    try {
      // A source those clients lack (ui, from 1.21.6) is mapped beforehand; master otherwise.
      String sourceName =
          source < 0 ? "" : LEGACY_SOURCE_NAMES[source < LEGACY_SOURCE_NAMES.length ? source : 0];
      McString.write(buf, sourceName);
      McString.write(buf, sound != null ? sound : "", SoundEffect.MAX_SOUND_LENGTH);
      return new PlayPluginMessage(LEGACY_CHANNEL, ByteBufUtil.getBytes(buf));
    } finally {
      buf.release();
    }
  }
}
