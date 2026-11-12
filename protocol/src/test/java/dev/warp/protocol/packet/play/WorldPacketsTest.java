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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.RegistryData;

import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The codecs of the virtual world's packets. */
@DisplayName("world packets")
class WorldPacketsTest {

  static Stream<ProtocolVersion> everyVersion() {
    return ProtocolVersion.values().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyVersion")
  @DisplayName("should round-trip the client's movement packets")
  void movementRoundTrip(ProtocolVersion version) {
    roundTrip(
        ClientMovement.PositionRotation.CODEC,
        new ClientMovement.PositionRotation(8.5, 72.25, 3.5, 90f, -10f, false),
        version);
    roundTrip(
        ClientMovement.Position.CODEC, new ClientMovement.Position(8.5, 71.9, 3.5, true), version);
    roundTrip(ClientMovement.Rotation.CODEC, new ClientMovement.Rotation(1f, 2f, true), version);
    roundTrip(ClientMovement.Flags.CODEC, new ClientMovement.Flags(true), version);
  }

  @Test
  @DisplayName("should read 1.7's stance and write 1.21.2's flag byte")
  void layoutEdges() {
    ByteBuf buf = Unpooled.buffer();
    try {
      ClientMovement.Position.CODEC.encode(
          new ClientMovement.Position(1, 64, 2, true), buf, ProtocolVersion.MINECRAFT_1_7_6);
      assertEquals(8 * 4 + 1, buf.readableBytes(), "x, feet, stance, z and on ground");
      buf.skipBytes(16);
      assertEquals(64 + ClientMovement.LEGACY_STANCE, buf.readDouble());
      buf.clear();
      ClientMovement.Flags.CODEC.encode(
          new ClientMovement.Flags(true), buf, ProtocolVersion.MINECRAFT_1_21_4);
      assertEquals(ClientMovement.ON_GROUND_BIT, buf.readByte());
    } finally {
      buf.release();
    }
  }

  @Test
  @DisplayName("should read only the on ground bit of 1.21.2's flag byte")
  void flagByteCarriesOtherBits() {
    int horizontalCollision = 0x02;
    ByteBuf buf = Unpooled.buffer();
    try {
      buf.writeByte(horizontalCollision);
      buf.writeByte(horizontalCollision | ClientMovement.ON_GROUND_BIT);

      ClientMovement.Flags airborne =
          ClientMovement.Flags.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);
      ClientMovement.Flags grounded =
          ClientMovement.Flags.CODEC.decode(buf, ProtocolVersion.MINECRAFT_1_21_4);

      assertFalse(airborne.onGround(), "a collision alone is not the ground");
      assertTrue(grounded.onGround());
    } finally {
      buf.release();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyVersion")
  @DisplayName("should encode the world's clientbound packets for every version")
  void clientboundEncode(ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    try {
      EmptyChunk.CODEC.encode(new EmptyChunk(0, 0), buf, version);
      assertTrue(buf.readableBytes() > 12, "a chunk column");
      buf.clear();
      SyncPlayerPosition.CODEC.encode(new SyncPlayerPosition(7, 8.5, 72, 3.5, 0, 0), buf, version);
      assertTrue(buf.readableBytes() >= 33);
      buf.clear();
      BlockUpdate.CODEC.encode(new BlockUpdate(8, 64, 3, BlockUpdate.STONE), buf, version);
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
        buf.readLong();
        int block = VarInt.read(buf);
        assertEquals(version.isAtLeast(ProtocolVersion.MINECRAFT_1_13) ? 1 : 16, block);
      }
      buf.clear();
      SetDefaultSpawnPosition.CODEC.encode(new SetDefaultSpawnPosition(8, 65, 3), buf, version);
      GameEvent.CODEC.encode(new GameEvent(GameEvent.START_WAITING_FOR_CHUNKS, 0), buf, version);
    } finally {
      buf.release();
    }
  }

  static Stream<ProtocolVersion> paletteVersions() {
    return Stream.of(
        ProtocolVersion.MINECRAFT_1_9,
        ProtocolVersion.MINECRAFT_1_12_2,
        ProtocolVersion.MINECRAFT_1_13,
        ProtocolVersion.MINECRAFT_1_14,
        ProtocolVersion.MINECRAFT_1_15_2,
        ProtocolVersion.MINECRAFT_1_16,
        ProtocolVersion.MINECRAFT_1_16_4);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("paletteVersions")
  @DisplayName(
      "should send 1.9 to 1.16 one section of air, which clients reading a zero bitmask as 1.8's"
          + " unload keep")
  void airSection(ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    try {
      EmptyChunk.CODEC.encode(new EmptyChunk(0, 0), buf, version);
      buf.skipBytes(8); // chunk x, chunk z
      assertTrue(buf.readBoolean(), "full chunk");
      if (version.isBetween(ProtocolVersion.MINECRAFT_1_16, ProtocolVersion.MINECRAFT_1_16_1)) {
        assertTrue(buf.readBoolean(), "ignore old data");
      }
      assertEquals(1, VarInt.read(buf), "the lowest section is present");
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_14)) {
        skipHeightmaps(buf);
      }
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_16_2)) {
        int biomes = VarInt.read(buf);
        assertEquals(1024, biomes);
        for (int i = 0; i < biomes; i++) {
          assertEquals(0, VarInt.read(buf));
        }
      } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_15)) {
        buf.skipBytes(1024 * 4);
      }
      int size = VarInt.read(buf);
      int dataStart = buf.readerIndex();
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_14)) {
        assertEquals(0, buf.readShort(), "no block but air");
      }
      assertEquals(4, buf.readUnsignedByte(), "the narrowest palette these clients accept");
      assertEquals(1, VarInt.read(buf), "one palette entry");
      assertEquals(0, VarInt.read(buf), "air");
      assertEquals(256, VarInt.read(buf), "4096 blocks of 4 bits in longs");
      buf.skipBytes(256 * 8);
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_14)) {
        buf.skipBytes(2048 + 2048); // block light, sky light
      }
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_13)) {
        buf.skipBytes(256); // biomes as bytes
      } else if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_15)) {
        buf.skipBytes(256 * 4); // biomes as ints
      }
      assertEquals(size, buf.readerIndex() - dataStart, "the data is exactly the section");
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9_4)) {
        assertEquals(0, VarInt.read(buf), "no block entity");
      }
      assertEquals(0, buf.readableBytes(), "nothing after");
    } finally {
      buf.release();
    }
  }

  static Stream<ProtocolVersion> modernChunkVersions() {
    return Stream.of(
        ProtocolVersion.MINECRAFT_1_17,
        ProtocolVersion.MINECRAFT_1_17_1,
        ProtocolVersion.MINECRAFT_1_18,
        ProtocolVersion.MINECRAFT_1_18_2,
        ProtocolVersion.MINECRAFT_1_19_4,
        ProtocolVersion.MINECRAFT_1_20,
        ProtocolVersion.MINECRAFT_1_20_2,
        ProtocolVersion.MINECRAFT_1_20_3,
        ProtocolVersion.MINECRAFT_1_20_5,
        ProtocolVersion.MINECRAFT_1_21_4,
        ProtocolVersion.MINECRAFT_1_21_5,
        ProtocolVersion.MINECRAFT_26_3);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("modernChunkVersions")
  @DisplayName("should lay out the chunk of 1.17 and later exactly, with its light and heightmaps")
  void modernChunk(ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    try {
      EmptyChunk.CODEC.encode(new EmptyChunk(3, -2), buf, version);
      assertEquals(3, buf.readInt());
      assertEquals(-2, buf.readInt());
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_18)) {
        assertEquals(0, VarInt.read(buf), "an empty BitSet of sections");
        readHeightmaps(buf, version);
        assertEquals(1024, VarInt.read(buf), "biomes");
        for (int i = 0; i < 1024; i++) {
          assertEquals(0, VarInt.read(buf));
        }
        assertEquals(0, VarInt.read(buf), "no section data");
        assertEquals(0, VarInt.read(buf), "no block entity");
        assertEquals(0, buf.readableBytes());
        return;
      }
      readHeightmaps(buf, version);
      int sections = version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5) ? 24 : 16;
      assertEquals(sections, EmptyChunk.sections(version));
      boolean lengthPrefixed = version.isOlderThan(ProtocolVersion.MINECRAFT_1_21_5);
      assertEquals(sections * (lengthPrefixed ? 8 : 6), VarInt.read(buf), "the data length");
      for (int i = 0; i < sections; i++) {
        assertEquals(0, buf.readShort(), "non-air blocks");
        assertEquals(0, buf.readByte(), "single-value block palette");
        assertEquals(0, VarInt.read(buf), "air");
        if (lengthPrefixed) {
          assertEquals(0, VarInt.read(buf), "no block data");
        }
        assertEquals(0, buf.readByte(), "single-value biome palette");
        assertEquals(0, VarInt.read(buf), "the first biome");
        if (lengthPrefixed) {
          assertEquals(0, VarInt.read(buf), "no biome data");
        }
      }
      assertEquals(0, VarInt.read(buf), "no block entity");
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20)) {
        assertTrue(buf.readBoolean(), "trust edges");
      }
      for (int i = 0; i < 6; i++) {
        assertEquals(0, VarInt.read(buf), "light mask or array " + i);
      }
      assertEquals(0, buf.readableBytes());
    } finally {
      buf.release();
    }
  }

  /** The heightmaps: NBT with a root name until 1.20.1, nameless until 1.21.4, a count after. */
  private static void readHeightmaps(ByteBuf buf, ProtocolVersion version) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_5)) {
      assertEquals(0, VarInt.read(buf), "no heightmap");
      return;
    }
    assertEquals(10, buf.readByte(), "a compound");
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_2)) {
      assertEquals(0, buf.readUnsignedShort(), "an empty root name");
    }
    int longs = version.isAtLeast(ProtocolVersion.MINECRAFT_1_16) ? 37 : 36;
    for (String name : new String[] {"MOTION_BLOCKING", "WORLD_SURFACE"}) {
      assertEquals(12, buf.readByte(), "a long array");
      byte[] bytes = new byte[buf.readUnsignedShort()];
      buf.readBytes(bytes);
      assertEquals(name, new String(bytes, java.nio.charset.StandardCharsets.US_ASCII));
      assertEquals(longs, buf.readInt());
      for (int i = 0; i < longs; i++) {
        assertEquals(0, buf.readLong());
      }
    }
    assertEquals(0, buf.readByte(), "the end of the compound");
  }

  @Test
  @DisplayName("should write the 1.14 and 1.15 heightmaps with 36 longs, 1.16's with 37")
  void heightmapLongs() {
    for (ProtocolVersion version :
        new ProtocolVersion[] {ProtocolVersion.MINECRAFT_1_14, ProtocolVersion.MINECRAFT_1_15_2}) {
      ByteBuf buf = Unpooled.buffer();
      try {
        EmptyChunk.CODEC.encode(new EmptyChunk(0, 0), buf, version);
        buf.skipBytes(8 + 1);
        VarInt.read(buf);
        readHeightmaps(buf, version);
      } finally {
        buf.release();
      }
    }
  }

  @Test
  @DisplayName("should lay out the teleport for every edge of its history")
  void teleportLayouts() {
    SyncPlayerPosition packet = new SyncPlayerPosition(7, 8.5, 72, 3.5, 90, -45);
    ByteBuf buf = Unpooled.buffer();
    try {
      // 1.7: the eyes, not the feet, and an on-ground boolean
      SyncPlayerPosition.CODEC.encode(packet, buf, ProtocolVersion.MINECRAFT_1_7_6);
      assertEquals(8.5, buf.readDouble());
      assertEquals(72 + ClientMovement.LEGACY_STANCE, buf.readDouble());
      assertEquals(3.5, buf.readDouble());
      assertEquals(90f, buf.readFloat());
      assertEquals(-45f, buf.readFloat());
      assertFalse(buf.readBoolean());
      assertEquals(0, buf.readableBytes());
      buf.clear();

      // 1.8: relative flags, no id
      SyncPlayerPosition.CODEC.encode(packet, buf, ProtocolVersion.MINECRAFT_1_8);
      assertEquals(8.5, buf.readDouble());
      assertEquals(72, buf.readDouble());
      assertEquals(3.5, buf.readDouble());
      buf.skipBytes(8);
      assertEquals(0, buf.readByte(), "absolute");
      assertEquals(0, buf.readableBytes());
      buf.clear();

      // 1.9 to 1.16: the teleport id
      SyncPlayerPosition.CODEC.encode(packet, buf, ProtocolVersion.MINECRAFT_1_12_2);
      buf.skipBytes(8 * 3 + 4 * 2 + 1);
      assertEquals(7, VarInt.read(buf));
      assertEquals(0, buf.readableBytes());
      buf.clear();

      // 1.17 to 1.19.3: dismount vehicle
      for (ProtocolVersion version :
          new ProtocolVersion[] {
            ProtocolVersion.MINECRAFT_1_17, ProtocolVersion.MINECRAFT_1_19_3
          }) {
        SyncPlayerPosition.CODEC.encode(packet, buf, version);
        buf.skipBytes(8 * 3 + 4 * 2 + 1);
        assertEquals(7, VarInt.read(buf));
        assertFalse(buf.readBoolean(), "dismount vehicle in " + version);
        assertEquals(0, buf.readableBytes());
        buf.clear();
      }

      // 1.19.4 to 1.21.1: the flag is gone
      SyncPlayerPosition.CODEC.encode(packet, buf, ProtocolVersion.MINECRAFT_1_19_4);
      buf.skipBytes(8 * 3 + 4 * 2 + 1);
      assertEquals(7, VarInt.read(buf));
      assertEquals(0, buf.readableBytes());
      buf.clear();

      // 1.21.2: the id first, a velocity, int flags
      SyncPlayerPosition.CODEC.encode(packet, buf, ProtocolVersion.MINECRAFT_1_21_2);
      assertEquals(7, VarInt.read(buf));
      assertEquals(8.5, buf.readDouble());
      assertEquals(72, buf.readDouble());
      assertEquals(3.5, buf.readDouble());
      for (int i = 0; i < 3; i++) {
        assertEquals(0, buf.readDouble(), "velocity");
      }
      assertEquals(90f, buf.readFloat());
      assertEquals(-45f, buf.readFloat());
      assertEquals(0, buf.readInt(), "absolute");
      assertEquals(0, buf.readableBytes());
    } finally {
      buf.release();
    }
  }

  @Test
  @DisplayName("should lay out the spawn position and the block update per version")
  void positionLayouts() {
    ByteBuf buf = Unpooled.buffer();
    try {
      SetDefaultSpawnPosition.CODEC.encode(
          new SetDefaultSpawnPosition(8, 65, 3), buf, ProtocolVersion.MINECRAFT_1_7_6);
      assertEquals(8, buf.readInt());
      assertEquals(65, buf.readInt());
      assertEquals(3, buf.readInt());
      assertEquals(0, buf.readableBytes());
      buf.clear();

      SetDefaultSpawnPosition.CODEC.encode(
          new SetDefaultSpawnPosition(8, 65, 3), buf, ProtocolVersion.MINECRAFT_1_8);
      assertEquals((8L << 38) | (65L << 26) | 3L, buf.readLong());
      assertEquals(0, buf.readableBytes());
      buf.clear();

      SetDefaultSpawnPosition.CODEC.encode(
          new SetDefaultSpawnPosition(8, 65, 3), buf, ProtocolVersion.MINECRAFT_1_16_4);
      assertEquals((8L << 38) | (3L << 12) | 65L, buf.readLong());
      assertEquals(0, buf.readableBytes());
      buf.clear();

      SetDefaultSpawnPosition.CODEC.encode(
          new SetDefaultSpawnPosition(8, 65, 3), buf, ProtocolVersion.MINECRAFT_1_17);
      assertEquals((8L << 38) | (3L << 12) | 65L, buf.readLong());
      assertEquals(0f, buf.readFloat(), "angle");
      assertEquals(0, buf.readableBytes());
      buf.clear();

      BlockUpdate.CODEC.encode(
          new BlockUpdate(8, 64, 3, BlockUpdate.STONE), buf, ProtocolVersion.MINECRAFT_1_7_6);
      assertEquals(8, buf.readInt());
      assertEquals(64, buf.readUnsignedByte());
      assertEquals(3, buf.readInt());
      assertEquals(1, VarInt.read(buf), "stone");
      assertEquals(0, buf.readByte(), "metadata");
      assertEquals(0, buf.readableBytes());
      buf.clear();

      BlockUpdate.CODEC.encode(
          new BlockUpdate(8, 64, 3, BlockUpdate.STONE), buf, ProtocolVersion.MINECRAFT_1_8);
      assertEquals((8L << 38) | (64L << 26) | 3L, buf.readLong());
      assertEquals(1 << 4, VarInt.read(buf), "stone, metadata 0");
      assertEquals(0, buf.readableBytes());
    } finally {
      buf.release();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyVersion")
  @DisplayName("should round-trip the answers the world reads")
  void answersRoundTrip(ProtocolVersion version) {
    roundTrip(TeleportConfirm.CODEC, new TeleportConfirm(300), version);
    roundTrip(PlayPing.CODEC, new PlayPing(-7), version);
    roundTrip(PlayPong.CODEC, new PlayPong(Integer.MIN_VALUE), version);
    roundTrip(WindowConfirmation.CODEC, new WindowConfirmation(0, (short) -31000, true), version);
    roundTrip(TickEnd.CODEC, new TickEnd(), version);
    ByteBuf buf = Unpooled.buffer();
    try {
      TeleportConfirm.CODEC.encode(new TeleportConfirm(300), buf, version);
      assertEquals(2, buf.readableBytes(), "a VarInt");
      buf.clear();
      WindowConfirmation.CODEC.encode(new WindowConfirmation(0, (short) 5, false), buf, version);
      assertEquals(4, buf.readableBytes(), "window id, action, accepted");
      buf.clear();
      TickEnd.CODEC.encode(new TickEnd(), buf, version);
      assertEquals(0, buf.readableBytes(), "no field");
    } finally {
      buf.release();
    }
  }

  @Test
  @DisplayName("should tell the movements that carry a position from those that do not")
  void movementKinds() {
    assertTrue(new ClientMovement.Position(1, 2, 3, false).hasPosition());
    assertTrue(new ClientMovement.PositionRotation(1, 2, 3, 0, 0, false).hasPosition());
    ClientMovement rotation = new ClientMovement.Rotation(0, 0, true);
    ClientMovement flags = new ClientMovement.Flags(true);
    assertFalse(rotation.hasPosition());
    assertFalse(flags.hasPosition());
    for (ClientMovement movement : new ClientMovement[] {rotation, flags}) {
      assertEquals(0, movement.x());
      assertEquals(0, movement.y());
      assertEquals(0, movement.z());
      assertTrue(movement.onGround());
    }
  }

  @Test
  @DisplayName(
      "should write 1.7's stance after the feet, 1.21.2's flags as a byte, booleans between")
  void movementEncodeEdges() {
    ByteBuf buf = Unpooled.buffer();
    try {
      ClientMovement.PositionRotation.CODEC.encode(
          new ClientMovement.PositionRotation(1, 64, 3, 0, 0, true),
          buf,
          ProtocolVersion.MINECRAFT_1_7_6);
      assertEquals(1, buf.readDouble());
      assertEquals(64, buf.readDouble());
      assertEquals(64 + ClientMovement.LEGACY_STANCE, buf.readDouble());
      assertEquals(3, buf.readDouble());
      buf.skipBytes(8);
      assertTrue(buf.readBoolean());
      assertEquals(0, buf.readableBytes());
      buf.clear();

      ClientMovement.Position.CODEC.encode(
          new ClientMovement.Position(1, 64, 3, true), buf, ProtocolVersion.MINECRAFT_1_7_6);
      assertEquals(8 * 4 + 1, buf.readableBytes());
      buf.skipBytes(8);
      assertEquals(64, buf.readDouble());
      assertEquals(64 + ClientMovement.LEGACY_STANCE, buf.readDouble());
      buf.clear();

      ClientMovement.Flags.CODEC.encode(
          new ClientMovement.Flags(true), buf, ProtocolVersion.MINECRAFT_1_20_1);
      assertEquals(1, buf.readableBytes());
      assertTrue(buf.readBoolean());
      buf.clear();

      ClientMovement.Rotation.CODEC.encode(
          new ClientMovement.Rotation(0, 0, false), buf, ProtocolVersion.MINECRAFT_1_21_2);
      buf.skipBytes(8);
      assertEquals(0, buf.readByte(), "no flag");
      assertEquals(0, buf.readableBytes());
    } finally {
      buf.release();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"1.20.2", "1.20.4", "1.20.5", "26.3"})
  @DisplayName("should name the registry from 1.20.5, and write 1.20.2's one nameless compound")
  void registryData(String name) {
    ProtocolVersion version =
        ProtocolVersion.values().stream()
            .filter(v -> v.name().equals(name))
            .findFirst()
            .orElseThrow();
    RegistryData data = new RegistryData("minecraft:dimension_type", new byte[] {1, 2, 3});
    ByteBuf buf = Unpooled.buffer();
    try {
      RegistryData.CODEC.encode(data, buf, version);
      boolean named = version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5);
      assertEquals(named ? 1 + 24 + 3 : 3, buf.readableBytes());
      RegistryData read = RegistryData.CODEC.decode(buf, version);
      assertEquals(named ? "minecraft:dimension_type" : "", read.registryId());
      assertEquals(3, read.rawEntries().length);
      assertEquals(0, buf.readableBytes());
    } finally {
      buf.release();
    }
  }

  @Test
  @DisplayName("should have world registries for the configuration and play states only")
  void worldRegistries() {
    assertTrue(
        StateRegistry.world(ProtocolState.PLAY).lookup(ProtocolVersion.MINECRAFT_1_21_4, 0x00)
            != null,
        "the teleport confirmation");
    assertTrue(
        StateRegistry.world(ProtocolState.CONFIGURATION)
                .lookup(ProtocolVersion.MINECRAFT_1_21_4, 0x07)
            != null,
        "the client's known packs");
    assertThrows(IllegalArgumentException.class, () -> StateRegistry.world(ProtocolState.LOGIN));
    assertThrows(IllegalArgumentException.class, () -> StateRegistry.world(ProtocolState.STATUS));
  }

  /** An unnamed compound of long arrays, as 1.14 to 1.20.1 write the heightmaps. */
  private static void skipHeightmaps(ByteBuf buf) {
    assertEquals(10, buf.readByte(), "a compound");
    buf.skipBytes(2); // empty root name
    for (byte tag = buf.readByte(); tag != 0; tag = buf.readByte()) {
      assertEquals(12, tag, "a long array");
      buf.skipBytes(buf.readUnsignedShort()); // the name
      buf.skipBytes(buf.readInt() * 8);
    }
  }

  @Test
  @DisplayName("should send 1.8 one empty section, not the ground-up unload a zero bitmask means")
  void legacyChunk18() {
    ByteBuf buf = Unpooled.buffer();
    try {
      EmptyChunk.CODEC.encode(new EmptyChunk(0, 0), buf, ProtocolVersion.MINECRAFT_1_8);
      buf.skipBytes(8); // chunk x, chunk z
      assertTrue(buf.readBoolean(), "ground-up continuous");
      assertEquals(1, buf.readUnsignedShort(), "the lowest section is present");
      int size = VarInt.read(buf);
      assertEquals(4096 * 2 + 2048 + 2048 + 256, size, "blocks, block light, sky light, biomes");
      assertEquals(size, buf.readableBytes());
    } finally {
      buf.release();
    }
  }

  @Test
  @DisplayName("should send 1.7 one empty section, deflated with the biomes")
  void legacyChunk17() throws DataFormatException {
    ByteBuf buf = Unpooled.buffer();
    try {
      EmptyChunk.CODEC.encode(new EmptyChunk(0, 0), buf, ProtocolVersion.MINECRAFT_1_7_6);
      buf.skipBytes(8);
      assertTrue(buf.readBoolean(), "ground-up continuous");
      assertEquals(1, buf.readUnsignedShort(), "primary bitmap: the lowest section");
      assertEquals(0, buf.readUnsignedShort(), "add bitmap");
      int length = buf.readInt();
      assertEquals(length, buf.readableBytes());
      byte[] compressed = new byte[length];
      buf.readBytes(compressed);
      Inflater inflater = new Inflater();
      try {
        inflater.setInput(compressed);
        byte[] data = new byte[4096 + 2048 + 2048 + 2048 + 256 + 1];
        int inflated = inflater.inflate(data);
        assertTrue(inflater.finished(), "the whole column");
        assertEquals(data.length - 1, inflated, "blocks, metadata, block light, sky light, biomes");
      } finally {
        inflater.end();
      }
    } finally {
      buf.release();
    }
  }

  @Test
  @DisplayName("should pack block positions with y in the middle before 1.14 and at the end after")
  void positionPacking() {
    long old = BlockUpdate.packPosition(8, 64, 3, ProtocolVersion.MINECRAFT_1_13_2);
    long modern = BlockUpdate.packPosition(8, 64, 3, ProtocolVersion.MINECRAFT_1_14);

    assertEquals((8L << 38) | (64L << 26) | 3L, old);
    assertEquals((8L << 38) | (3L << 12) | 64L, modern);
  }

  private static <T extends dev.warp.protocol.packet.Packet> void roundTrip(
      dev.warp.protocol.packet.PacketCodec<T> codec, T packet, ProtocolVersion version) {
    ByteBuf buf = Unpooled.buffer();
    try {
      codec.encode(packet, buf, version);
      assertEquals(packet, codec.decode(buf, version), version.toString());
      assertEquals(0, buf.readableBytes());
    } finally {
      buf.release();
    }
  }
}
