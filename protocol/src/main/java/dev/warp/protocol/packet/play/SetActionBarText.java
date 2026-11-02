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
 * Server shows a message above the hotbar, the action bar ({@code S→C}, from 1.11).
 *
 * <p>From 1.17 this is the Set Action Bar Text packet: the component alone. From 1.11 to 1.16.5 it
 * is the Title packet's set action bar action, 2, followed by the component ({@link LegacyTitle}).
 * Before 1.11 an action bar is a chat message at the game info position ({@link
 * SystemChatMessage}), whose styles the client drops. The component is a VarInt-prefixed JSON
 * string before 1.20.3, NBT from 1.20.3.
 *
 * <p>From 1.19.1 a system chat message with its overlay flag shows there as well; this packet has
 * the advantage of not depending on 1.19's chat type registry.
 *
 * @param text the action bar text, encoded for the client's version (see {@link ComponentCodec})
 */
@SuppressWarnings("ArrayRecordComponent") // never mutated
public record SetActionBarText(byte[] text) implements PlayPacket {

  /** Codec for reading and writing set action bar text packets. */
  public static final PacketCodec<SetActionBarText> CODEC =
      new PacketCodec<>() {
        @Override
        public SetActionBarText decode(ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(MINECRAFT_1_17)) {
            LegacyTitle.expect(buf, LegacyTitle.SET_ACTION_BAR);
          }
          return new SetActionBarText(ComponentCodec.readRaw(buf, version));
        }

        @Override
        public void encode(SetActionBarText packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isOlderThan(MINECRAFT_1_17)) {
            VarInt.write(buf, LegacyTitle.SET_ACTION_BAR);
          }
          buf.writeBytes(packet.text());
        }
      };

  /**
   * Creates the packet for a client of {@code version}.
   *
   * @param text the action bar text
   * @param version the client's protocol version, from 1.11
   * @return the packet
   * @throws IllegalArgumentException if the text is too large for the client
   */
  public static SetActionBarText of(Component text, ProtocolVersion version) {
    return new SetActionBarText(ComponentCodec.encode(text, version));
  }
}
