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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;

import io.netty.buffer.ByteBuf;
import org.jspecify.annotations.Nullable;

/**
 * Where and how the player spawns: the fields {@link JoinGame} and {@link Respawn} share before
 * 1.20.2, which a proxy needs to build the Respawn that follows the Join Game of a new server.
 *
 * <p>The dimension changed shape twice: a numeric ID up to 1.15.2, a dimension type identifier in
 * 1.16 and 1.16.1, the dimension type itself (an NBT compound) from 1.16.2 to 1.18.2, and an
 * identifier again from 1.19. A field the protocol version does not have keeps its default value:
 * zero, {@code false}, an empty string or array, or {@code null}.
 *
 * @param dimension the dimension ID up to 1.15.2: -1 for the nether, 0 for the overworld, 1 for the
 *     end
 * @param dimensionType the dimension type identifier in 1.16, 1.16.1 and from 1.19
 * @param dimensionTypeData the dimension type, an NBT compound exactly as on the wire, from 1.16.2
 *     to 1.18.2
 * @param worldName the identifier of the world the player is in, from 1.16
 * @param hashedSeed the first eight bytes of the SHA-256 of the world seed, from 1.15
 * @param difficulty the difficulty up to 1.13.2 (0 peaceful to 3 hard)
 * @param gameMode the game mode (0 survival, 1 creative, 2 adventure, 3 spectator), without the
 *     hardcore flag
 * @param previousGameMode the previous game mode, -1 for none, from 1.16
 * @param levelType the level type up to 1.15.2 ({@code default}, {@code flat}, ...)
 * @param debug whether the world is a debug world, from 1.16
 * @param flat whether the world is superflat, from 1.16
 * @param lastDeathLocation where the player last died, from 1.19, or {@code null}
 * @param portalCooldown the ticks before the player may use a portal again, from 1.20
 */
@SuppressWarnings("ArrayRecordComponent") // dimensionTypeData is never mutated
public record SpawnInfo(
    int dimension,
    String dimensionType,
    byte[] dimensionTypeData,
    String worldName,
    long hashedSeed,
    int difficulty,
    int gameMode,
    int previousGameMode,
    String levelType,
    boolean debug,
    boolean flat,
    @Nullable DeathLocation lastDeathLocation,
    int portalCooldown) {

  /**
   * Where the player last died.
   *
   * @param worldName the identifier of the world
   * @param position the block position, packed in a long like every position in the protocol
   */
  public record DeathLocation(String worldName, long position) {}

  /**
   * Returns this spawn in another numeric dimension (versions up to 1.15.2).
   *
   * @param dimension the dimension ID: -1 nether, 0 overworld, 1 end
   * @return a copy with {@code dimension} replaced
   */
  public SpawnInfo withDimension(int dimension) {
    return new SpawnInfo(
        dimension,
        dimensionType,
        dimensionTypeData,
        worldName,
        hashedSeed,
        difficulty,
        gameMode,
        previousGameMode,
        levelType,
        debug,
        flat,
        lastDeathLocation,
        portalCooldown);
  }

  // ---------------------------------------------------------------------------
  // Wire helpers shared by JoinGame and Respawn
  // ---------------------------------------------------------------------------

  /** Whether the dimension type is sent as an NBT compound: from 1.16.2 to 1.18.2. */
  static boolean dimensionTypeIsNbt(ProtocolVersion version) {
    return version.isAtLeast(MINECRAFT_1_16_2) && version.isOlderThan(MINECRAFT_1_19);
  }

  /** Reads an optional death location (from 1.19). */
  static @Nullable DeathLocation readDeathLocation(ByteBuf buf) {
    return buf.readBoolean() ? new DeathLocation(McString.read(buf), buf.readLong()) : null;
  }

  /** Writes an optional death location (from 1.19). */
  static void writeDeathLocation(ByteBuf buf, @Nullable DeathLocation location) {
    buf.writeBoolean(location != null);
    if (location != null) {
      McString.write(buf, location.worldName());
      buf.writeLong(location.position());
    }
  }
}
