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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_11;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_17;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Server clears the title on screen ({@code S→C}, from 1.8).
 *
 * <p>Before 1.20.2 a title survives the Join Game of a server switch; the proxy clears it when a
 * player switches, as Velocity does. From 1.17 this is the Clear Titles packet and a boolean: the
 * client hides the title and subtitle, and also resets the fade times when it is set. Up to 1.16.5
 * it is the Title packet with its hide or reset action, a VarInt whose value moved in 1.11 when the
 * action bar took action 2 (hide 3 then 4, reset 4 then 5). The reset action alone does not hide
 * the subtitle there: the 1.8.9 client blanks the title and shows the last subtitle under it for a
 * whole title duration, so a clear takes the hide action, then the reset (Velocity's {@code
 * LegacyTitlePacket} and {@code TitleClearPacket}, minecraft-data's {@code packet_title} and {@code
 * packet_clear_titles}).
 *
 * @param reset {@code true} to also reset the fade times to their defaults (the reset action up to
 *     1.16.5), {@code false} to only hide the text (the hide action)
 */
public record ClearTitles(boolean reset) implements PlayPacket {

  /** Codec for reading and writing clear titles packets. */
  public static final PacketCodec<ClearTitles> CODEC =
      new PacketCodec<>() {
        @Override
        public ClearTitles decode(ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(MINECRAFT_1_17)) {
            return new ClearTitles(buf.readBoolean());
          }
          int action = VarInt.read(buf);
          int hide = hideAction(version);
          if (action != hide && action != hide + 1) {
            throw new DecoderException("Title action " + action + " does not clear the title");
          }
          return new ClearTitles(action != hide);
        }

        @Override
        public void encode(ClearTitles packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(MINECRAFT_1_17)) {
            buf.writeBoolean(packet.reset());
          } else {
            VarInt.write(buf, packet.reset() ? hideAction(version) + 1 : hideAction(version));
          }
        }
      };

  /** The Title packet's hide action, up to 1.16.5; reset is the next one. */
  private static int hideAction(ProtocolVersion version) {
    return version.isAtLeast(MINECRAFT_1_11) ? 4 : 3;
  }
}
