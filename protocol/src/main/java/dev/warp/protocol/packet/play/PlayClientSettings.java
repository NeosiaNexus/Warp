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
 * <p>Sent as soon as the client enters PLAY, then whenever the player changes a setting (language,
 * render distance, etc.). The proxy forwards it to the backend and caches it for replay on server
 * switches. From 1.20.2 the wire format matches {@link
 * dev.warp.protocol.packet.config.ClientInformation} in the configuration state.
 *
 * <p>Version history (fields in wire order):
 *
 * <ul>
 *   <li><b>1.7.2–1.7.10</b>: locale, view distance, chat mode (byte), chat colors, difficulty
 *       (byte), show cape (boolean, read as the skin parts byte)
 *   <li><b>1.8</b>: difficulty removed, show cape widened to the skin parts bitmask
 *   <li><b>1.9</b>: chat mode becomes a VarInt, main hand (VarInt) appended
 *   <li><b>1.17</b>: text filtering (boolean) appended
 *   <li><b>1.18</b>: allow server listings (boolean) appended
 *   <li><b>1.21.2</b>: particle status (VarInt) appended
 * </ul>
 *
 * <p>A field the version does not send decodes to a neutral default, documented on each component,
 * and is not written back when encoding for that version.
 *
 * @param locale the client locale (e.g. {@code en_GB})
 * @param viewDistance the render distance in chunks
 * @param chatMode chat visibility (0 = enabled, 1 = commands only, 2 = hidden)
 * @param chatColors whether the client supports chat colors
 * @param difficulty the client-side difficulty from {@code options.txt}; 1.7.x only, 0 otherwise
 * @param displayedSkinParts bitmask of visible skin parts (1.7.x: 1 if the cape is shown, else 0)
 * @param mainHand dominant hand (0 = left, 1 = right); right before 1.9
 * @param enableTextFiltering whether to filter chat text; off before 1.17
 * @param allowServerListings whether the player appears in server listings; allowed before 1.18
 * @param particleStatus particle level (0 = all, 1 = decreased, 2 = minimal); all before 1.21.2
 */
public record PlayClientSettings(
    String locale,
    byte viewDistance,
    int chatMode,
    boolean chatColors,
    byte difficulty,
    byte displayedSkinParts,
    int mainHand,
    boolean enableTextFiltering,
    boolean allowServerListings,
    int particleStatus)
    implements PlayPacket {

  /** Longest locale accepted, in characters. */
  private static final int MAX_LOCALE_LENGTH = 16;

  /** Difficulty of a client that does not send one (1.8+). */
  private static final byte NO_DIFFICULTY = 0;

  /** Main hand of a client that cannot choose one (before 1.9): the right hand. */
  private static final int RIGHT_HAND = 1;

  /** Text filtering of a client that does not send it (before 1.17): off. */
  private static final boolean TEXT_FILTERING_OFF = false;

  /** Server listing of a client that does not send it (before 1.18): allowed, as in vanilla. */
  private static final boolean SERVER_LISTING_ALLOWED = true;

  /** Particle status of a client that does not send it (before 1.21.2): all particles. */
  private static final int ALL_PARTICLES = 0;

  /** Codec for reading and writing play client settings packets. */
  public static final PacketCodec<PlayClientSettings> CODEC =
      new PacketCodec<>() {
        @Override
        public PlayClientSettings decode(ByteBuf buf, ProtocolVersion version) {
          String locale = McString.read(buf, MAX_LOCALE_LENGTH);
          byte viewDistance = buf.readByte();
          int chatMode =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)
                  ? VarInt.read(buf)
                  : buf.readUnsignedByte();
          boolean chatColors = buf.readBoolean();
          byte difficulty =
              version.isOlderThan(ProtocolVersion.MINECRAFT_1_8) ? buf.readByte() : NO_DIFFICULTY;
          byte displayedSkinParts = buf.readByte();
          int mainHand =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_9) ? VarInt.read(buf) : RIGHT_HAND;
          boolean enableTextFiltering =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_17)
                  ? buf.readBoolean()
                  : TEXT_FILTERING_OFF;
          boolean allowServerListings =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_18)
                  ? buf.readBoolean()
                  : SERVER_LISTING_ALLOWED;
          int particleStatus =
              version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2)
                  ? VarInt.read(buf)
                  : ALL_PARTICLES;
          return new PlayClientSettings(
              locale,
              viewDistance,
              chatMode,
              chatColors,
              difficulty,
              displayedSkinParts,
              mainHand,
              enableTextFiltering,
              allowServerListings,
              particleStatus);
        }

        @Override
        public void encode(PlayClientSettings packet, ByteBuf buf, ProtocolVersion version) {
          McString.write(buf, packet.locale(), MAX_LOCALE_LENGTH);
          buf.writeByte(packet.viewDistance());
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)) {
            VarInt.write(buf, packet.chatMode());
          } else {
            buf.writeByte(packet.chatMode());
          }
          buf.writeBoolean(packet.chatColors());
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
            buf.writeByte(packet.difficulty());
          }
          buf.writeByte(packet.displayedSkinParts());
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)) {
            VarInt.write(buf, packet.mainHand());
          }
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_17)) {
            buf.writeBoolean(packet.enableTextFiltering());
          }
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_18)) {
            buf.writeBoolean(packet.allowServerListings());
          }
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2)) {
            VarInt.write(buf, packet.particleStatus());
          }
        }
      };
}
