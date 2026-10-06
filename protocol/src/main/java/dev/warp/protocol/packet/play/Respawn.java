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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_13_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_15;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McNbt;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import io.netty.buffer.ByteBuf;

/**
 * Server moves the player to another world, or respawns it after death ({@code S→C}).
 *
 * <p>Before 1.20.2 a proxy switches a player between servers with the new server's {@link JoinGame}
 * followed by a Respawn into the same world, built by {@link #of(SpawnInfo)}: the Join Game resets
 * the client's world and entity ID, the Respawn makes it load the new world like after a dimension
 * change. Respawns the servers send are forwarded untouched, never decoded. The proxy never sends
 * one from 1.20.2, where the configuration phase replaces this technique, so the codec covers the
 * versions before.
 *
 * <p>Layout: up to 1.15.2 the numeric dimension, the difficulty (up to 1.13.2), the hashed seed
 * (from 1.15), the game mode and the level type. From 1.16 the dimension type (an identifier, or an
 * NBT compound from 1.16.2 to 1.18.2), the world name, the hashed seed, the game modes, the debug
 * and flat flags, what the client keeps (a boolean before 1.19.3, a byte after), the last death
 * location (from 1.19) and the portal cooldown (from 1.20). Velocity's {@code RespawnPacket} and
 * minecraft-data's {@code packet_respawn} describe the same fields.
 *
 * @param spawn where and how the player respawns
 * @param dataKept what the client keeps from the player entity it replaces: 0 for nothing; before
 *     1.19.3 any other value keeps everything, from 1.19.3 bit 0 keeps the attributes and bit 1 the
 *     entity data
 */
public record Respawn(SpawnInfo spawn, int dataKept) implements PlayPacket {

  /** Longest level type vanilla reads (up to 1.15.2). */
  private static final int MAX_LEVEL_TYPE_LENGTH = 16;

  private static final byte[] EMPTY = new byte[0];

  /**
   * Builds the Respawn that completes a server switch before 1.20.2: into the world the new
   * server's {@link JoinGame} describes, keeping nothing from the previous player entity.
   *
   * @param spawn the spawn of the new server's Join Game
   * @return the respawn packet
   */
  public static Respawn of(SpawnInfo spawn) {
    return new Respawn(spawn, 0);
  }

  /** Codec for reading and writing respawn packets, before 1.20.2. */
  public static final PacketCodec<Respawn> CODEC =
      new PacketCodec<>() {
        @Override
        public Respawn decode(ByteBuf buf, ProtocolVersion version) {
          requireBeforeConfigurationPhase(version);
          if (version.isOlderThan(MINECRAFT_1_16)) {
            int dimension = buf.readInt();
            int difficulty = version.isAtMost(MINECRAFT_1_13_2) ? buf.readUnsignedByte() : 0;
            long hashedSeed = version.isAtLeast(MINECRAFT_1_15) ? buf.readLong() : 0;
            int gameMode = buf.readUnsignedByte();
            String levelType = McString.read(buf, MAX_LEVEL_TYPE_LENGTH);
            return new Respawn(
                new SpawnInfo(
                    dimension,
                    "",
                    EMPTY,
                    "",
                    hashedSeed,
                    difficulty,
                    gameMode,
                    0,
                    levelType,
                    false,
                    false,
                    null,
                    0),
                0);
          }
          String dimensionType = "";
          byte[] dimensionTypeData = EMPTY;
          if (SpawnInfo.dimensionTypeIsNbt(version)) {
            dimensionTypeData = McNbt.readNamed(buf);
          } else {
            dimensionType = McString.read(buf);
          }
          String worldName = McString.read(buf);
          long hashedSeed = buf.readLong();
          int gameMode = buf.readUnsignedByte();
          int previousGameMode = buf.readByte();
          boolean debug = buf.readBoolean();
          boolean flat = buf.readBoolean();
          int dataKept =
              version.isAtLeast(MINECRAFT_1_19_3)
                  ? buf.readUnsignedByte()
                  : buf.readBoolean() ? 1 : 0;
          SpawnInfo.DeathLocation lastDeathLocation =
              version.isAtLeast(MINECRAFT_1_19) ? SpawnInfo.readDeathLocation(buf) : null;
          int portalCooldown = version.isAtLeast(MINECRAFT_1_20) ? VarInt.read(buf) : 0;
          return new Respawn(
              new SpawnInfo(
                  0,
                  dimensionType,
                  dimensionTypeData,
                  worldName,
                  hashedSeed,
                  0,
                  gameMode,
                  previousGameMode,
                  "",
                  debug,
                  flat,
                  lastDeathLocation,
                  portalCooldown),
              dataKept);
        }

        @Override
        public void encode(Respawn packet, ByteBuf buf, ProtocolVersion version) {
          requireBeforeConfigurationPhase(version);
          SpawnInfo spawn = packet.spawn();
          if (version.isOlderThan(MINECRAFT_1_16)) {
            buf.writeInt(spawn.dimension());
            if (version.isAtMost(MINECRAFT_1_13_2)) {
              buf.writeByte(spawn.difficulty());
            }
            if (version.isAtLeast(MINECRAFT_1_15)) {
              buf.writeLong(spawn.hashedSeed());
            }
            buf.writeByte(spawn.gameMode());
            McString.write(buf, spawn.levelType(), MAX_LEVEL_TYPE_LENGTH);
            return;
          }
          if (SpawnInfo.dimensionTypeIsNbt(version)) {
            buf.writeBytes(spawn.dimensionTypeData());
          } else {
            McString.write(buf, spawn.dimensionType());
          }
          McString.write(buf, spawn.worldName());
          buf.writeLong(spawn.hashedSeed());
          buf.writeByte(spawn.gameMode());
          buf.writeByte(spawn.previousGameMode());
          buf.writeBoolean(spawn.debug());
          buf.writeBoolean(spawn.flat());
          if (version.isAtLeast(MINECRAFT_1_19_3)) {
            buf.writeByte(packet.dataKept());
          } else {
            buf.writeBoolean(packet.dataKept() != 0);
          }
          if (version.isAtLeast(MINECRAFT_1_19)) {
            SpawnInfo.writeDeathLocation(buf, spawn.lastDeathLocation());
          }
          if (version.isAtLeast(MINECRAFT_1_20)) {
            VarInt.write(buf, spawn.portalCooldown());
          }
        }
      };

  private static void requireBeforeConfigurationPhase(ProtocolVersion version) {
    if (version.supportsConfigurationState()) {
      throw new IllegalArgumentException(
          "Respawn is only encoded before 1.20.2, where it switches servers: " + version);
    }
  }
}
