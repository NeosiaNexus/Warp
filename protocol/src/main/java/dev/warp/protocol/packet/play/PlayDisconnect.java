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

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.text.ComponentCodec;

import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;

/**
 * Server disconnects the player during gameplay ({@code S→C}).
 *
 * <p>The reason is a text component whose wire format changed in 1.20.3, from a VarInt-prefixed
 * JSON string to NBT. The proxy keeps the encoded bytes and never parses them.
 *
 * @param rawReason the encoded reason, exactly as on the wire: a VarInt-prefixed JSON string before
 *     1.20.3, an NBT tag from 1.20.3 (see {@link ComponentCodec})
 */
public record PlayDisconnect(byte[] rawReason) implements PlayPacket {

  /** Codec for reading and writing play disconnect packets. */
  public static final PacketCodec<PlayDisconnect> CODEC =
      new PacketCodec<>() {
        @Override
        public PlayDisconnect decode(ByteBuf buf, ProtocolVersion version) {
          byte[] rawReason = new byte[buf.readableBytes()];
          buf.readBytes(rawReason);
          return new PlayDisconnect(rawReason);
        }

        @Override
        public void encode(PlayDisconnect packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeBytes(packet.rawReason());
        }
      };

  /**
   * Returns the reason as a component: what a backend that kicks a player wrote, read without
   * failing (see {@link ComponentCodec#decode(byte[], ProtocolVersion, ProtocolState)}).
   *
   * @param version the protocol version of the connection the packet came from
   * @return the reason, {@link Component#empty()} when the backend gave none
   */
  public Component decodedReason(ProtocolVersion version) {
    return ComponentCodec.decode(rawReason, version, ProtocolState.PLAY);
  }

  /**
   * Creates a play disconnect whose reason is {@code reason}, encoded for {@code version}.
   *
   * @param reason the reason shown to the player
   * @param version the client's protocol version, which selects JSON or NBT
   * @return the packet
   * @throws IllegalArgumentException if the reason is too large for the client
   */
  public static PlayDisconnect of(Component reason, ProtocolVersion version) {
    return new PlayDisconnect(ComponentCodec.encode(reason, version));
  }

  /**
   * Creates a play disconnect whose reason is plain text, encoded for {@code version}.
   *
   * @param reason the text shown to the player
   * @param version the client's protocol version, which selects JSON or NBT
   * @return the packet
   * @throws IllegalArgumentException if the reason is too large for the client
   */
  public static PlayDisconnect ofPlainText(String reason, ProtocolVersion version) {
    return of(Component.text(reason), version);
  }
}
