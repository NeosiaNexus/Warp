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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.VarInt;

import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
