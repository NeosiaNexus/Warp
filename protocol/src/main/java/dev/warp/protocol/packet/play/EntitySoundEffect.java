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
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server plays a sound that follows an entity ({@code S→C}), from 1.19.3, with the sound named by
 * its key.
 *
 * <p>The Entity Sound Effect packet exists from 1.14, but only took a registry id until 1.19.3,
 * which inlined the sound as a holder: {@code 0}, the name and an absent fixed range. The proxy
 * never learns the sound registry of a client (it is forwarded untouched), so it only writes the
 * named form, and a sound that follows an entity is not played before 1.19.3. Layout: the sound,
 * the source (a VarInt), the entity id (a VarInt), float volume, float pitch, long seed
 * (minecraft-data's {@code packet_entity_sound_effect}).
 *
 * @param sound the sound's key
 * @param source the sound source's wire id (see {@link SoundEffect#source})
 * @param entityId the id of the entity the sound follows, as the client's server numbers it
 * @param volume the volume, 1 for normal
 * @param pitch the pitch, 1 for normal
 * @param seed the seed of the sound's variations
 */
public record EntitySoundEffect(
    String sound, int source, int entityId, float volume, float pitch, long seed)
    implements PlayPacket {

  /** Codec for reading and writing named sounds that follow an entity. */
  public static final PacketCodec<EntitySoundEffect> CODEC =
      new PacketCodec<>() {
        @Override
        public EntitySoundEffect decode(ByteBuf buf, ProtocolVersion version) {
          String sound = SoundEffect.readSound(buf, version);
          int source = VarInt.read(buf);
          int entityId = VarInt.read(buf);
          return new EntitySoundEffect(
              sound, source, entityId, buf.readFloat(), buf.readFloat(), buf.readLong());
        }

        @Override
        public void encode(EntitySoundEffect packet, ByteBuf buf, ProtocolVersion version) {
          SoundEffect.writeSound(buf, packet.sound(), version);
          VarInt.write(buf, packet.source());
          VarInt.write(buf, packet.entityId());
          buf.writeFloat(packet.volume());
          buf.writeFloat(packet.pitch());
          buf.writeLong(packet.seed());
        }
      };
}
