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
 * Server sends respawn data when the player changes dimension or respawns ({@code S→C}).
 *
 * <p>The proxy uses this during server switching (dimension trick: switch to a different dimension
 * then back, forcing the client to respawn without a full rejoin). Like {@link JoinGame}, full
 * decode is deferred — the proxy captures raw bytes for faithful replay.
 *
 * @param rawData the entire packet payload as raw bytes
 */
public record Respawn(byte[] rawData) implements PlayPacket {

  /** Codec for reading and writing respawn packets. */
  public static final PacketCodec<Respawn> CODEC =
      new PacketCodec<>() {
        @Override
        public Respawn decode(ByteBuf buf, ProtocolVersion version) {
          byte[] rawData = new byte[buf.readableBytes()];
          buf.readBytes(rawData);
          return new Respawn(rawData);
        }

        @Override
        public void encode(Respawn packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeBytes(packet.rawData());
        }
      };
}
