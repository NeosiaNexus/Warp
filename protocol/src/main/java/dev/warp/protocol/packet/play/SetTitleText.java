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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_17;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.text.ComponentCodec;

import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;

/**
 * Server shows a title in the middle of the screen ({@code S→C}, from 1.8), with the subtitle and
 * fade times set before it.
 *
 * <p>From 1.17 this is the Set Title Text packet: the component alone. Up to 1.16.5 it is the Title
 * packet's set title action, 0, followed by the component ({@link LegacyTitle}). The component is a
 * VarInt-prefixed JSON string before 1.20.3, NBT from 1.20.3.
 *
 * @param text the title, encoded for the client's version (see {@link ComponentCodec})
 */
@SuppressWarnings("ArrayRecordComponent") // never mutated
public record SetTitleText(byte[] text) implements PlayPacket {

  /** Codec for reading and writing set title text packets. */
  public static final PacketCodec<SetTitleText> CODEC =
      new PacketCodec<>() {
        @Override
        public SetTitleText decode(ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(MINECRAFT_1_17)) {
            LegacyTitle.expect(buf, LegacyTitle.SET_TITLE);
          }
          return new SetTitleText(ComponentCodec.readRaw(buf, version));
        }

        @Override
        public void encode(SetTitleText packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(MINECRAFT_1_17)) {
            VarInt.write(buf, LegacyTitle.SET_TITLE);
          }
          buf.writeBytes(packet.text());
        }
      };

  /**
   * Creates the packet for a client of {@code version}.
   *
   * @param title the title
   * @param version the client's protocol version, from 1.8
   * @return the packet
   * @throws IllegalArgumentException if the title is too large for the client
   */
  public static SetTitleText of(Component title, ProtocolVersion version) {
    return new SetTitleText(ComponentCodec.encode(title, version));
  }
}
