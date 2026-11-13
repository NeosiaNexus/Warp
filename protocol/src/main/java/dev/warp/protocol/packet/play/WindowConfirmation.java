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
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * A window transaction (Confirm Transaction, 1.7 to 1.16.5), the same layout in both directions: a
 * server that sends one with {@code accepted = false} gets the same window and action back with
 * {@code accepted = true}, from the client's main thread. The anti-bot's virtual world uses it as
 * the ping of versions that have no {@link PlayPing}.
 *
 * @param windowId the window, 0 for the player's inventory
 * @param action the action number, echoed back
 * @param accepted whether the action was accepted
 */
public record WindowConfirmation(int windowId, short action, boolean accepted)
    implements PlayPacket {

  /** Codec for the window confirmation, both directions. */
  public static final PacketCodec<WindowConfirmation> CODEC =
      new PacketCodec<>() {
        @Override
        public WindowConfirmation decode(ByteBuf buf, ProtocolVersion version) {
          int windowId = buf.readByte();
          short action = buf.readShort();
          boolean accepted = buf.readBoolean();
          return new WindowConfirmation(windowId, action, accepted);
        }

        @Override
        public void encode(WindowConfirmation packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeByte(packet.windowId());
          buf.writeShort(packet.action());
          buf.writeBoolean(packet.accepted());
        }
      };
}
