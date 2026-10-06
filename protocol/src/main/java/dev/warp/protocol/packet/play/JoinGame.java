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
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_14;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_15;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_18;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_8;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_9_1;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McNbt;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.play.SpawnInfo.DeathLocation;

import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Server puts the player in a world ({@code S→C}): the first packet of the PLAY state.
 *
 * <p>Before 1.20.2 the proxy decodes it completely. Those clients have no configuration phase, so a
 * server switch hands them the new server's Join Game followed by a {@link Respawn} built from it
 * ({@link Respawn#of(SpawnInfo)}), which needs every field. From 1.20.2 the configuration phase
 * resets the client instead, and the proxy reads only the entity ID and the hardcore flag: the rest
 * is carried verbatim.
 *
 * <p>The layout changed in 1.9.1 (numeric dimension widened to an int), 1.14 (difficulty removed,
 * view distance added), 1.15 (hashed seed, respawn screen), 1.16 (registry codec, world names,
 * dimension identifiers), 1.16.2 (hardcore flag on its own, dimension type as NBT), 1.18
 * (simulation distance), 1.19 (dimension type identifier again, last death location), 1.20 (portal
 * cooldown) and 1.20.2 (configuration phase). Velocity's {@code JoinGamePacket} and
 * minecraft-data's {@code packet_login} describe the same fields.
 *
 * @param entityId the player's entity ID on this server
 * @param hardcore whether the world is in hardcore mode
 * @param body everything else: decoded before 1.20.2, verbatim from 1.20.2
 */
public record JoinGame(int entityId, boolean hardcore, JoinGame.Body body) implements PlayPacket {

  /** Before 1.16.2, the hardcore flag is bit 3 of the game mode byte. */
  private static final int HARDCORE_BIT = 0x08;

  /** Longest level type vanilla reads (up to 1.15.2). */
  private static final int MAX_LEVEL_TYPE_LENGTH = 16;

  private static final byte[] EMPTY = new byte[0];

  /** The fields after the entity ID and the hardcore flag. */
  public sealed interface Body permits Decoded, Opaque {}

  /**
   * Everything after the hardcore flag, decoded (before 1.20.2). Fields the version does not have
   * keep their default value, as in {@link SpawnInfo}.
   *
   * @param maxPlayers the maximum player count (unused by clients)
   * @param worldNames the identifiers of every world on the server, from 1.16
   * @param registry the registry codec (dimension types, biomes, ...), an NBT compound exactly as
   *     on the wire, from 1.16
   * @param viewDistance the server's view distance, from 1.14
   * @param simulationDistance the server's simulation distance, from 1.18
   * @param reducedDebugInfo whether the debug screen hides coordinates, from 1.8
   * @param respawnScreen whether the client shows the death screen, from 1.15
   * @param spawn where and how the player spawns
   */
  @SuppressWarnings("ArrayRecordComponent") // registry is never mutated
  public record Decoded(
      int maxPlayers,
      List<String> worldNames,
      byte[] registry,
      int viewDistance,
      int simulationDistance,
      boolean reducedDebugInfo,
      boolean respawnScreen,
      SpawnInfo spawn)
      implements Body {

    /** Copies the world names into an unmodifiable list. */
    public Decoded {
      worldNames = List.copyOf(worldNames);
    }

    /**
     * Returns this body with another spawn.
     *
     * @param spawn the new spawn
     * @return a copy with {@code spawn} replaced
     */
    public Decoded withSpawn(SpawnInfo spawn) {
      return new Decoded(
          maxPlayers,
          worldNames,
          registry,
          viewDistance,
          simulationDistance,
          reducedDebugInfo,
          respawnScreen,
          spawn);
    }
  }

  /**
   * Everything after the hardcore flag, verbatim (from 1.20.2).
   *
   * @param bytes the remaining packet bytes
   */
  @SuppressWarnings("ArrayRecordComponent") // bytes are never mutated
  public record Opaque(byte[] bytes) implements Body {}

  /** Codec for reading and writing join game packets. */
  public static final PacketCodec<JoinGame> CODEC =
      new PacketCodec<>() {
        @Override
        public JoinGame decode(ByteBuf buf, ProtocolVersion version) {
          int entityId = buf.readInt();
          if (version.supportsConfigurationState()) {
            boolean hardcore = buf.readBoolean();
            byte[] rest = new byte[buf.readableBytes()];
            buf.readBytes(rest);
            return new JoinGame(entityId, hardcore, new Opaque(rest));
          }
          boolean hardcore;
          int gameMode;
          if (version.isAtLeast(MINECRAFT_1_16_2)) {
            hardcore = buf.readBoolean();
            gameMode = buf.readUnsignedByte();
          } else {
            int flags = buf.readUnsignedByte();
            hardcore = (flags & HARDCORE_BIT) != 0;
            gameMode = flags & ~HARDCORE_BIT;
          }
          Decoded body =
              version.isAtLeast(MINECRAFT_1_16)
                  ? decodeFrom116(buf, version, gameMode)
                  : decodeBefore116(buf, version, gameMode);
          return new JoinGame(entityId, hardcore, body);
        }

        @Override
        public void encode(JoinGame packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeInt(packet.entityId());
          switch (packet.body()) {
            case Opaque opaque -> {
              requireOpaque(version, true);
              buf.writeBoolean(packet.hardcore());
              buf.writeBytes(opaque.bytes());
            }
            case Decoded decoded -> {
              requireOpaque(version, false);
              int gameMode = decoded.spawn().gameMode();
              if (version.isAtLeast(MINECRAFT_1_16_2)) {
                buf.writeBoolean(packet.hardcore());
                buf.writeByte(gameMode);
              } else {
                buf.writeByte(packet.hardcore() ? gameMode | HARDCORE_BIT : gameMode);
              }
              if (version.isAtLeast(MINECRAFT_1_16)) {
                encodeFrom116(decoded, buf, version);
              } else {
                encodeBefore116(decoded, buf, version);
              }
            }
          }
        }
      };

  // ---------------------------------------------------------------------------
  // Up to 1.15.2: numeric dimension, level type
  // ---------------------------------------------------------------------------

  private static Decoded decodeBefore116(ByteBuf buf, ProtocolVersion version, int gameMode) {
    int dimension = version.isAtLeast(MINECRAFT_1_9_1) ? buf.readInt() : buf.readByte();
    int difficulty = version.isAtMost(MINECRAFT_1_13_2) ? buf.readUnsignedByte() : 0;
    long hashedSeed = version.isAtLeast(MINECRAFT_1_15) ? buf.readLong() : 0;
    int maxPlayers = buf.readUnsignedByte();
    String levelType = McString.read(buf, MAX_LEVEL_TYPE_LENGTH);
    int viewDistance = version.isAtLeast(MINECRAFT_1_14) ? VarInt.read(buf) : 0;
    boolean reducedDebugInfo = version.isAtLeast(MINECRAFT_1_8) && buf.readBoolean();
    boolean respawnScreen = version.isAtLeast(MINECRAFT_1_15) && buf.readBoolean();
    SpawnInfo spawn =
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
            0);
    return new Decoded(
        maxPlayers, List.of(), EMPTY, viewDistance, 0, reducedDebugInfo, respawnScreen, spawn);
  }

  private static void encodeBefore116(Decoded body, ByteBuf buf, ProtocolVersion version) {
    SpawnInfo spawn = body.spawn();
    if (version.isAtLeast(MINECRAFT_1_9_1)) {
      buf.writeInt(spawn.dimension());
    } else {
      buf.writeByte(spawn.dimension());
    }
    if (version.isAtMost(MINECRAFT_1_13_2)) {
      buf.writeByte(spawn.difficulty());
    }
    if (version.isAtLeast(MINECRAFT_1_15)) {
      buf.writeLong(spawn.hashedSeed());
    }
    buf.writeByte(body.maxPlayers());
    McString.write(buf, spawn.levelType(), MAX_LEVEL_TYPE_LENGTH);
    if (version.isAtLeast(MINECRAFT_1_14)) {
      VarInt.write(buf, body.viewDistance());
    }
    if (version.isAtLeast(MINECRAFT_1_8)) {
      buf.writeBoolean(body.reducedDebugInfo());
    }
    if (version.isAtLeast(MINECRAFT_1_15)) {
      buf.writeBoolean(body.respawnScreen());
    }
  }

  // ---------------------------------------------------------------------------
  // 1.16 to 1.20.1: registry codec, dimension identifiers
  // ---------------------------------------------------------------------------

  private static Decoded decodeFrom116(ByteBuf buf, ProtocolVersion version, int gameMode) {
    int previousGameMode = buf.readByte();
    List<String> worldNames = readIdentifiers(buf);
    byte[] registry = McNbt.readNamed(buf);
    String dimensionType = "";
    byte[] dimensionTypeData = EMPTY;
    if (SpawnInfo.dimensionTypeIsNbt(version)) {
      dimensionTypeData = McNbt.readNamed(buf);
    } else {
      dimensionType = McString.read(buf);
    }
    String worldName = McString.read(buf);
    long hashedSeed = buf.readLong();
    int maxPlayers =
        version.isAtLeast(MINECRAFT_1_16_2) ? VarInt.read(buf) : buf.readUnsignedByte();
    int viewDistance = VarInt.read(buf);
    int simulationDistance = version.isAtLeast(MINECRAFT_1_18) ? VarInt.read(buf) : 0;
    boolean reducedDebugInfo = buf.readBoolean();
    boolean respawnScreen = buf.readBoolean();
    boolean debug = buf.readBoolean();
    boolean flat = buf.readBoolean();
    DeathLocation lastDeathLocation =
        version.isAtLeast(MINECRAFT_1_19) ? SpawnInfo.readDeathLocation(buf) : null;
    int portalCooldown = version.isAtLeast(MINECRAFT_1_20) ? VarInt.read(buf) : 0;
    SpawnInfo spawn =
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
            portalCooldown);
    return new Decoded(
        maxPlayers,
        worldNames,
        registry,
        viewDistance,
        simulationDistance,
        reducedDebugInfo,
        respawnScreen,
        spawn);
  }

  private static void encodeFrom116(Decoded body, ByteBuf buf, ProtocolVersion version) {
    SpawnInfo spawn = body.spawn();
    buf.writeByte(spawn.previousGameMode());
    VarInt.write(buf, body.worldNames().size());
    for (String worldName : body.worldNames()) {
      McString.write(buf, worldName);
    }
    buf.writeBytes(body.registry());
    if (SpawnInfo.dimensionTypeIsNbt(version)) {
      buf.writeBytes(spawn.dimensionTypeData());
    } else {
      McString.write(buf, spawn.dimensionType());
    }
    McString.write(buf, spawn.worldName());
    buf.writeLong(spawn.hashedSeed());
    if (version.isAtLeast(MINECRAFT_1_16_2)) {
      VarInt.write(buf, body.maxPlayers());
    } else {
      buf.writeByte(body.maxPlayers());
    }
    VarInt.write(buf, body.viewDistance());
    if (version.isAtLeast(MINECRAFT_1_18)) {
      VarInt.write(buf, body.simulationDistance());
    }
    buf.writeBoolean(body.reducedDebugInfo());
    buf.writeBoolean(body.respawnScreen());
    buf.writeBoolean(spawn.debug());
    buf.writeBoolean(spawn.flat());
    if (version.isAtLeast(MINECRAFT_1_19)) {
      SpawnInfo.writeDeathLocation(buf, spawn.lastDeathLocation());
    }
    if (version.isAtLeast(MINECRAFT_1_20)) {
      VarInt.write(buf, spawn.portalCooldown());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static List<String> readIdentifiers(ByteBuf buf) {
    int count = VarInt.read(buf);
    // Each identifier takes at least its one-byte length prefix.
    if (count < 0 || count > buf.readableBytes()) {
      throw new DecoderException("Invalid world name count: " + count);
    }
    List<String> identifiers = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      identifiers.add(McString.read(buf));
    }
    return identifiers;
  }

  private static void requireOpaque(ProtocolVersion version, boolean opaque) {
    if (version.supportsConfigurationState() != opaque) {
      throw new IllegalArgumentException(
          "A "
              + (opaque ? "verbatim" : "decoded")
              + " join game cannot be written for "
              + version
              + ": decoded before 1.20.2, verbatim from 1.20.2");
    }
  }
}
