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
 * Server sends the initial game join data ({@code S→C}).
 *
 * <p>This packet has undergone massive structural changes across 1.16, 1.18, 1.19, 1.20.2, and
 * 1.20.5. For proxy purposes, only the entity ID, hardcore flag, and game mode are needed (for
 * server-switch dimension tricks). The remaining fields are captured as raw bytes for faithful
 * re-encoding.
 *
 * <p>Full-fidelity decode will be added when the plugin API requires individual field access.
 *
 * @param entityId the player's entity ID (used for entity ID rewriting during server switch)
 * @param isHardcore whether the server is in hardcore mode
 * @param gameMode the player's current game mode (0–3)
 * @param rawRemainder the remaining packet bytes after the decoded fields
 */
public record JoinGame(int entityId, boolean isHardcore, int gameMode, byte[] rawRemainder)
    implements PlayPacket {

  /** Codec for reading and writing join game packets (minimal proxy decode). */
  public static final PacketCodec<JoinGame> CODEC =
      new PacketCodec<>() {
        @Override
        public JoinGame decode(ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_16_2)) {
            int entityId = buf.readInt();
            boolean isHardcore = buf.readBoolean();
            int gameMode = buf.readUnsignedByte();
            byte[] rawRemainder = new byte[buf.readableBytes()];
            buf.readBytes(rawRemainder);
            return new JoinGame(entityId, isHardcore, gameMode, rawRemainder);
          } else {
            int entityId = buf.readInt();
            int rawGameMode = buf.readUnsignedByte();
            boolean isHardcore = (rawGameMode & 0x08) != 0;
            int gameMode = rawGameMode & ~0x08;
            byte[] rawRemainder = new byte[buf.readableBytes()];
            buf.readBytes(rawRemainder);
            return new JoinGame(entityId, isHardcore, gameMode, rawRemainder);
          }
        }

        @Override
        public void encode(JoinGame packet, ByteBuf buf, ProtocolVersion version) {
          if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_16_2)) {
            buf.writeInt(packet.entityId());
            buf.writeBoolean(packet.isHardcore());
            buf.writeByte(packet.gameMode());
          } else {
            buf.writeInt(packet.entityId());
            buf.writeByte(packet.isHardcore() ? (packet.gameMode() | 0x08) : packet.gameMode());
          }
          buf.writeBytes(packet.rawRemainder());
        }
      };
}
