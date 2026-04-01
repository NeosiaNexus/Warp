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

/**
 * Client sends its settings during gameplay ({@code C->S}).
 *
 * <p>Sent when the player changes settings (language, render distance, etc.) during gameplay. The
 * proxy caches this for replay to backend servers on server switch. Wire format matches {@link
 * dev.warp.protocol.packet.config.ClientInformation} in the configuration state.
 *
 * @param locale the client locale
 * @param viewDistance the render distance in chunks
 * @param chatMode chat visibility (0=enabled, 1=commands only, 2=hidden)
 * @param chatColors whether the client supports chat colors
 * @param displayedSkinParts bitmask of visible skin parts
 * @param mainHand dominant hand (0=left, 1=right)
 * @param enableTextFiltering whether to filter chat text
 * @param allowServerListings whether the player appears in server listings
 * @param particleStatus particle level (0=all, 1=decreased, 2=minimal; 1.21.2+)
 */
public record PlayClientSettings(
    String locale,
    byte viewDistance,
    int chatMode,
    boolean chatColors,
    byte displayedSkinParts,
    int mainHand,
    boolean enableTextFiltering,
    boolean allowServerListings,
    int particleStatus)
    implements PlayPacket {

  /** Codec for reading and writing play client settings packets. */
  public static final PacketCodec<PlayClientSettings> CODEC =
      new PacketCodec<>() {
        @Override
        public PlayClientSettings decode(ByteBuf buf, ProtocolVersion version) {
          String locale = McString.read(buf, 16);
          byte viewDistance = buf.readByte();
          int chatMode = VarInt.read(buf);
          boolean chatColors = buf.readBoolean();
          byte displayedSkinParts = buf.readByte();
          int mainHand = VarInt.read(buf);
          boolean enableTextFiltering = buf.readBoolean();
          boolean allowServerListings = buf.readBoolean();
          int particleStatus =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2) ? VarInt.read(buf) : 0;
          return new PlayClientSettings(
              locale,
              viewDistance,
              chatMode,
              chatColors,
              displayedSkinParts,
              mainHand,
              enableTextFiltering,
              allowServerListings,
              particleStatus);
        }

        @Override
        public void encode(PlayClientSettings packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.locale(), 16);
          buf.writeByte(packet.viewDistance());
          VarInt.write(buf, packet.chatMode());
          buf.writeBoolean(packet.chatColors());
          buf.writeByte(packet.displayedSkinParts());
          VarInt.write(buf, packet.mainHand());
          buf.writeBoolean(packet.enableTextFiltering());
          buf.writeBoolean(packet.allowServerListings());
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2)) {
            VarInt.write(buf, packet.particleStatus());
          }
        }
      };
}
