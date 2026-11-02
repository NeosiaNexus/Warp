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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_3;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

/**
 * Server plays a sound that follows an entity ({@code S→C}, from 1.14): the Entity Sound Effect
 * packet.
 *
 * <p>Up to 1.19.2 the sound is its id in the client's sound event registry, a VarInt, which the
 * proxy takes from {@link SoundEventIds}. From 1.19.3 the sound is a holder, which the proxy always
 * writes inlined: {@code 0}, the name and an absent fixed range, so that any sound plays, a
 * resource pack's too. Then the source (a VarInt), the entity id (a VarInt), float volume, float
 * pitch, and from 1.19 a long seed (minecraft-data's {@code packet_entity_sound_effect}).
 *
 * @param sound the sound's key, which names it from 1.19.3; {@code null} for a packet to an older
 *     client
 * @param soundId the sound's id in the client's registry, which names it before 1.19.3; {@code -1}
 *     for a packet to a newer client
 * @param source the sound source's wire id (see {@link SoundEffect#source})
 * @param entityId the id of the entity the sound follows, as the client's server numbers it
 * @param volume the volume, 1 for normal
 * @param pitch the pitch, 1 for normal
 * @param seed the seed of the sound's variations, from 1.19 (read as 0 before)
 */
public record EntitySoundEffect(
    @Nullable String sound,
    int soundId,
    int source,
    int entityId,
    float volume,
    float pitch,
    long seed)
    implements PlayPacket {

  /**
   * Creates the packet of a client from 1.19.3, which names the sound by its key.
   *
   * @param sound the sound's key
   * @param source the source's wire id
   * @param entityId the entity the sound follows
   * @param volume the volume
   * @param pitch the pitch
   * @param seed the seed
   * @return the packet
   */
  public static EntitySoundEffect named(
      String sound, int source, int entityId, float volume, float pitch, long seed) {
    return new EntitySoundEffect(sound, -1, source, entityId, volume, pitch, seed);
  }

  /**
   * Creates the packet of a client from 1.14 to 1.19.2, which names the sound by its id.
   *
   * @param soundId the sound's id in the client's registry ({@link SoundEventIds#id})
   * @param source the source's wire id
   * @param entityId the entity the sound follows
   * @param volume the volume
   * @param pitch the pitch
   * @param seed the seed, written from 1.19
   * @return the packet
   */
  public static EntitySoundEffect registered(
      int soundId, int source, int entityId, float volume, float pitch, long seed) {
    return new EntitySoundEffect(null, soundId, source, entityId, volume, pitch, seed);
  }

  /** Codec for reading and writing sounds that follow an entity. */
  public static final PacketCodec<EntitySoundEffect> CODEC =
      new PacketCodec<>() {
        @Override
        public EntitySoundEffect decode(ByteBuf buf, ProtocolVersion version) {
          String sound = null;
          int soundId = -1;
          if (version.isOlderThan(MINECRAFT_1_19_3)) {
            soundId = VarInt.read(buf);
          } else {
            sound = SoundEffect.readSound(buf, version);
          }
          int source = VarInt.read(buf);
          int entityId = VarInt.read(buf);
          float volume = buf.readFloat();
          float pitch = buf.readFloat();
          long seed = version.isAtLeast(MINECRAFT_1_19) ? buf.readLong() : 0;
          return new EntitySoundEffect(sound, soundId, source, entityId, volume, pitch, seed);
        }

        @Override
        public void encode(EntitySoundEffect packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(MINECRAFT_1_19_3)) {
            if (packet.soundId() < 0) {
              throw new IllegalArgumentException(
                  "A sound following an entity is a registry id before 1.19.3: " + packet);
            }
            VarInt.write(buf, packet.soundId());
          } else {
            String sound = packet.sound();
            if (sound == null) {
              throw new IllegalArgumentException(
                  "A sound following an entity is named from 1.19.3: " + packet);
            }
            SoundEffect.writeSound(buf, sound, version);
          }
          VarInt.write(buf, packet.source());
          VarInt.write(buf, packet.entityId());
          buf.writeFloat(packet.volume());
          buf.writeFloat(packet.pitch());
          if (version.isAtLeast(MINECRAFT_1_19)) {
            buf.writeLong(packet.seed());
          }
        }
      };
}
