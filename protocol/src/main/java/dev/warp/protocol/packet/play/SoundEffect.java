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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_10;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_9;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Server plays a sound at a position ({@code S→C}), named by its key so that it needs no registry
 * id: the Named Sound Effect packet up to 1.19.2, the Sound Effect packet with its sound inlined
 * from 1.19.3.
 *
 * <p>The client resolves the key itself, so any sound plays, a resource pack's included, in every
 * version; a key the client does not know plays nothing. The position is in eighths of a block.
 * Layout by version (minecraft-data's {@code packet_named_sound_effect} and {@code
 * packet_sound_effect}):
 *
 * <ul>
 *   <li><b>1.7, 1.8</b>: name, x, y, z as ints, float volume, pitch as an unsigned byte (63 is
 *       100%);
 *   <li><b>1.9 to 1.9.4</b>: the source (a VarInt) after the name;
 *   <li><b>1.10 to 1.18.2</b>: the pitch as a float;
 *   <li><b>1.19 to 1.19.2</b>: a long seed at the end;
 *   <li><b>1.19.3+</b>: the sound as a holder, {@code 0} then the name and an absent fixed range,
 *       in place of the name.
 * </ul>
 *
 * @param sound the sound's key, at most 256 characters
 * @param source the sound source's wire id (see {@link #source}), ignored before 1.9
 * @param x the x position in eighths of a block
 * @param y the y position in eighths of a block
 * @param z the z position in eighths of a block
 * @param volume the volume, 1 for normal
 * @param pitch the pitch, 1 for normal
 * @param seed the seed of the sound's variations, ignored before 1.19
 */
public record SoundEffect(
    String sound, int source, int x, int y, int z, float volume, float pitch, long seed)
    implements PlayPacket {

  /** The longest sound name a client reads before 1.19.3, which reads it as a key after. */
  public static final int MAX_SOUND_LENGTH = 256;

  /** The position of the ui source, added in 1.21.6. */
  private static final int UI_SOURCE = 10;

  /** Before 1.10 the pitch is a byte where 63 stands for 100%. */
  private static final int BYTE_PITCH_SCALE = 63;

  /** Codec for reading and writing positioned named sounds. */
  public static final PacketCodec<SoundEffect> CODEC =
      new PacketCodec<>() {
        @Override
        public SoundEffect decode(ByteBuf buf, ProtocolVersion version) {
          String sound = readSound(buf, version);
          int source = version.isAtLeast(MINECRAFT_1_9) ? VarInt.read(buf) : 0;
          int x = buf.readInt();
          int y = buf.readInt();
          int z = buf.readInt();
          float volume = buf.readFloat();
          float pitch = readPitch(buf, version);
          long seed = version.isAtLeast(MINECRAFT_1_19) ? buf.readLong() : 0;
          return new SoundEffect(sound, source, x, y, z, volume, pitch, seed);
        }

        @Override
        public void encode(SoundEffect packet, ByteBuf buf, ProtocolVersion version) {
          writeSound(buf, packet.sound(), version);
          if (version.isAtLeast(MINECRAFT_1_9)) {
            VarInt.write(buf, packet.source());
          }
          buf.writeInt(packet.x());
          buf.writeInt(packet.y());
          buf.writeInt(packet.z());
          buf.writeFloat(packet.volume());
          writePitch(buf, packet.pitch(), version);
          if (version.isAtLeast(MINECRAFT_1_19)) {
            buf.writeLong(packet.seed());
          }
        }
      };

  /**
   * Returns a coordinate in eighths of a block, as the packet carries it.
   *
   * @param coordinate the coordinate in blocks
   * @return the fixed-point coordinate, clamped to the int range
   */
  public static int fixed(double coordinate) {
    return Math.clamp(Math.round(coordinate * 8), Integer.MIN_VALUE, Integer.MAX_VALUE);
  }

  /**
   * Returns the wire id of a sound source, by its position in the vanilla list: master, music,
   * record, weather, block, hostile, neutral, player, ambient, voice, then ui from 1.21.6. A client
   * before 1.21.6 has no ui source, and fails the whole packet on an id it does not know: those get
   * master, the source their own interface sounds play in.
   *
   * @param ordinal the source's position in the vanilla list ({@code
   *     net.kyori.adventure.sound.Sound.Source} has the same order)
   * @param version the client's protocol version
   * @return the id to send
   */
  public static int source(int ordinal, ProtocolVersion version) {
    return ordinal == UI_SOURCE && version.isOlderThan(ProtocolVersion.MINECRAFT_1_21_6)
        ? 0
        : ordinal;
  }

  /**
   * Writes the sound as the version names it: a string up to 1.19.2, a direct holder after.
   *
   * @param buf the buffer
   * @param sound the sound's key
   * @param version the client's protocol version
   */
  static void writeSound(ByteBuf buf, String sound, ProtocolVersion version) {
    if (version.isAtLeast(MINECRAFT_1_19_3)) {
      VarInt.write(buf, 0); // not a registry id: the sound event follows
      McString.write(buf, sound, Short.MAX_VALUE);
      buf.writeBoolean(false); // no fixed range: the volume decides how far it is heard
    } else {
      McString.write(buf, sound, MAX_SOUND_LENGTH);
    }
  }

  /**
   * Reads a sound written by {@link #writeSound}.
   *
   * @param buf the buffer
   * @param version the protocol version
   * @return the sound's key
   * @throws DecoderException if the sound is a registry id, which the proxy never writes
   */
  static String readSound(ByteBuf buf, ProtocolVersion version) {
    if (version.isOlderThan(MINECRAFT_1_19_3)) {
      return McString.read(buf, MAX_SOUND_LENGTH);
    }
    int holder = VarInt.read(buf);
    if (holder != 0) {
      throw new DecoderException("Sound registry id " + (holder - 1) + " instead of a key");
    }
    String sound = McString.read(buf, Short.MAX_VALUE);
    if (buf.readBoolean()) {
      buf.readFloat(); // a fixed range, which the proxy never writes
    }
    return sound;
  }

  private static float readPitch(ByteBuf buf, ProtocolVersion version) {
    return version.isAtLeast(MINECRAFT_1_10)
        ? buf.readFloat()
        : buf.readUnsignedByte() / (float) BYTE_PITCH_SCALE;
  }

  private static void writePitch(ByteBuf buf, float pitch, ProtocolVersion version) {
    if (version.isAtLeast(MINECRAFT_1_10)) {
      buf.writeFloat(pitch);
    } else {
      buf.writeByte(Math.clamp((int) (pitch * BYTE_PITCH_SCALE), 0, 255));
    }
  }
}
