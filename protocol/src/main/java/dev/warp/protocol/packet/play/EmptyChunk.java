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
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.PacketCodec;

import java.util.Arrays;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;

/**
 * One chunk column with nothing in it (Chunk Data, "Chunk Data and Update Light" from 1.18): air
 * from bottom to top, biome 0 everywhere, no block entity and no light. Encode-only: the anti-bot's
 * virtual world sends the column under the player, so that the client ticks its physics; a server's
 * chunks stay opaque.
 *
 * <p>Before 1.9 the column carries its lowest section, empty: a ground-up column with no section is
 * how those clients are told to unload the chunk, and block updates into an unloaded chunk are
 * dropped (the platform would never exist).
 *
 * <p>The layout changed in nearly every release: 1.7 (zlib-compressed data), 1.8 (two bitmasks
 * become one), 1.9 (VarInt bitmask), 1.9.4 (block entities), 1.13 (int biomes), 1.14 (heightmaps),
 * 1.15 (biomes out of the data), 1.16 (an "ignore old data" flag, gone in 1.16.2 with VarInt
 * biomes), 1.17 (a BitSet of sections), 1.18 (every section always present, light in the same
 * packet), 1.20 (no "trust edges"), 1.20.2 (nameless NBT), 1.21.5 (heightmaps as a list, palettes
 * without a length). The section count follows the dimension the world declares: 16 for the proxy's
 * own 256-block dimension, 24 for the vanilla overworld a 1.20.5+ client uses.
 *
 * @param chunkX the chunk x
 * @param chunkZ the chunk z
 */
public record EmptyChunk(int chunkX, int chunkZ) implements PlayPacket {

  private static final int BIOME_COLUMNS = 256;

  /** 1.15 to 1.17: a biome per 4x4x4 cell of a 256-block column. */
  private static final int BIOME_CELLS = 1024;

  /** NBT compound tag type and the end tag. */
  private static final int TAG_COMPOUND = 10;

  private static final int TAG_LONG_ARRAY = 12;
  private static final int TAG_END = 0;

  /** Codec for the clientbound empty chunk. */
  public static final PacketCodec<EmptyChunk> CODEC =
      new PacketCodec<>() {
        @Override
        public EmptyChunk decode(ByteBuf buf, ProtocolVersion version) {
          throw new UnsupportedOperationException("encode-only");
        }

        @Override
        public void encode(EmptyChunk packet, ByteBuf buf, ProtocolVersion version) {
          buf.writeInt(packet.chunkX());
          buf.writeInt(packet.chunkZ());
          if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_8)) {
            encode17(buf);
          } else if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_9)) {
            encode18(buf);
          } else if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_14)) {
            encode19(buf, version);
          } else if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_18)) {
            encode114(buf, version);
          } else {
            encode118(buf, version);
          }
        }
      };

  /**
   * Returns how many 16-block sections the client expects in a column: the height of the dimension
   * the virtual world declares, over 16.
   *
   * @param version the client's version
   * @return 24 from 1.20.5 (the vanilla overworld), 16 before (the proxy's own dimension)
   */
  public static int sections(ProtocolVersion version) {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5) ? 24 : 16;
  }

  // ---------------------------------------------------------------------------
  // Layouts
  // ---------------------------------------------------------------------------

  /** 1.7 and 1.8: the lowest section is sent, empty, so that the client loads the column. */
  private static final int LEGACY_SECTION_BIT = 1;

  /** 1.8: 4096 shorts of block state, then the block light and the sky light nibbles. */
  private static final int SECTION_BYTES_18 = 4096 * 2 + 2048 + 2048;

  /** 1.7: 4096 block ids, then the metadata, block light and sky light nibbles. */
  private static final int SECTION_BYTES_17 = 4096 + 2048 + 2048 + 2048;

  /**
   * The one section 1.9 to 1.16 get, of air: a full chunk whose bitmask is zero is a legal empty
   * chunk to the vanilla client, but an unload to clients that kept 1.8's reading of it
   * (mineflayer), which then have no column to stand in and never move. The palette has one entry
   * (air) at the smallest width these clients accept, 4 bits: 256 longs of zero.
   */
  private static final int PALETTE_BITS = 4;

  private static final int SECTION_LONGS = 4096 * PALETTE_BITS / Long.SIZE;

  /** 1.9 to 1.13: bits, palette, data, then block light and sky light (the overworld has both). */
  private static final int SECTION_BYTES_19 = 1 + 1 + 1 + 2 + SECTION_LONGS * 8 + 2048 + 2048;

  /** 1.14 to 1.16: the count of non-air blocks first, the light in a packet of its own. */
  private static final int SECTION_BYTES_114 = 2 + 1 + 1 + 1 + 2 + SECTION_LONGS * 8;

  /** 1.7's column, deflated once: one empty section and the biomes, all zero. */
  private static final byte[] DEFLATED_17 = deflate(new byte[SECTION_BYTES_17 + BIOME_COLUMNS]);

  /** 1.7: ground-up, one empty section, the biomes, all deflated. */
  private static void encode17(ByteBuf buf) {
    buf.writeBoolean(true); // ground-up continuous
    buf.writeShort(LEGACY_SECTION_BIT); // primary bitmap
    buf.writeShort(0); // add bitmap
    buf.writeInt(DEFLATED_17.length);
    buf.writeBytes(DEFLATED_17);
  }

  private static byte[] deflate(byte[] data) {
    byte[] compressed = new byte[data.length + 64];
    Deflater deflater = new Deflater();
    int length;
    try {
      deflater.setInput(data);
      deflater.finish();
      length = deflater.deflate(compressed);
      if (!deflater.finished()) {
        throw new IllegalStateException("zeros did not deflate into " + compressed.length);
      }
    } finally {
      deflater.end();
    }
    return Arrays.copyOf(compressed, length);
  }

  /** 1.8: ground-up, a short bitmask with one empty section, the biomes as bytes. */
  private static void encode18(ByteBuf buf) {
    buf.writeBoolean(true);
    buf.writeShort(LEGACY_SECTION_BIT);
    VarInt.write(buf, SECTION_BYTES_18 + BIOME_COLUMNS);
    buf.writeZero(SECTION_BYTES_18 + BIOME_COLUMNS);
  }

  /** 1.9 to 1.13.2: a VarInt bitmask, the biomes as bytes then ints, block entities from 1.9.4. */
  private static void encode19(ByteBuf buf, ProtocolVersion version) {
    buf.writeBoolean(true); // full chunk
    VarInt.write(buf, LEGACY_SECTION_BIT); // the lowest section, of air
    int biomeBytes =
        version.isAtLeast(ProtocolVersion.MINECRAFT_1_13) ? BIOME_COLUMNS * 4 : BIOME_COLUMNS;
    VarInt.write(buf, SECTION_BYTES_19 + biomeBytes);
    writePalette(buf);
    buf.writeZero(2048); // block light
    buf.writeZero(2048); // sky light
    buf.writeZero(biomeBytes);
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9_4)) {
      VarInt.write(buf, 0); // block entities
    }
  }

  /** The air section's palette and data, as 1.9 to 1.16 lay them out. */
  private static void writePalette(ByteBuf buf) {
    buf.writeByte(PALETTE_BITS);
    VarInt.write(buf, 1); // one palette entry
    VarInt.write(buf, 0); // air
    VarInt.write(buf, SECTION_LONGS);
    buf.writeZero(SECTION_LONGS * 8);
  }

  /** 1.14 to 1.17.1: heightmaps, biomes in or out of the data, a BitSet of sections from 1.17. */
  private static void encode114(ByteBuf buf, ProtocolVersion version) {
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_17)) {
      buf.writeBoolean(true); // full chunk
      if (version.isBetween(ProtocolVersion.MINECRAFT_1_16, ProtocolVersion.MINECRAFT_1_16_1)) {
        buf.writeBoolean(true); // ignore old data
      }
      VarInt.write(buf, LEGACY_SECTION_BIT); // primary bitmask: the lowest section, of air
    } else {
      VarInt.write(buf, 0); // an empty BitSet of sections
    }
    writeHeightmaps(buf, version);
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_16_2)) {
      VarInt.write(buf, BIOME_CELLS);
      buf.writeZero(BIOME_CELLS); // 1024 VarInts of zero
    } else if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_15)) {
      buf.writeZero(BIOME_CELLS * 4);
    }
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_17)) {
      VarInt.write(buf, 0); // no section data
    } else {
      int biomeBytes = version.isOlderThan(ProtocolVersion.MINECRAFT_1_15) ? BIOME_COLUMNS * 4 : 0;
      VarInt.write(buf, SECTION_BYTES_114 + biomeBytes);
      buf.writeShort(0); // non-air blocks
      writePalette(buf);
      buf.writeZero(biomeBytes); // 1.14: the data ends with the biomes
    }
    VarInt.write(buf, 0); // block entities
  }

  /** 1.18+: every section present and empty, then the light data. */
  private static void encode118(ByteBuf buf, ProtocolVersion version) {
    writeHeightmaps(buf, version);
    boolean lengthPrefixed = version.isOlderThan(ProtocolVersion.MINECRAFT_1_21_5);
    int sections = sections(version);
    int sectionBytes = lengthPrefixed ? 8 : 6;
    VarInt.write(buf, sections * sectionBytes);
    for (int i = 0; i < sections; i++) {
      buf.writeShort(0); // non-air blocks
      buf.writeByte(0); // block palette: single value
      VarInt.write(buf, 0); // air
      if (lengthPrefixed) {
        VarInt.write(buf, 0);
      }
      buf.writeByte(0); // biome palette: single value
      VarInt.write(buf, 0); // the first biome
      if (lengthPrefixed) {
        VarInt.write(buf, 0);
      }
    }
    VarInt.write(buf, 0); // block entities
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20)) {
      buf.writeBoolean(true); // trust edges
    }
    VarInt.write(buf, 0); // sky light mask
    VarInt.write(buf, 0); // block light mask
    VarInt.write(buf, 0); // empty sky light mask
    VarInt.write(buf, 0); // empty block light mask
    VarInt.write(buf, 0); // sky light arrays
    VarInt.write(buf, 0); // block light arrays
  }

  /**
   * Writes the heightmaps: MOTION_BLOCKING and WORLD_SURFACE as zeroed long arrays in an NBT
   * compound (named until 1.20.1, nameless from 1.20.2), an empty list from 1.21.5.
   */
  private static void writeHeightmaps(ByteBuf buf, ProtocolVersion version) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_5)) {
      VarInt.write(buf, 0);
      return;
    }
    // 9 bits per column: 36 longs packed across boundaries (1.14, 1.15), 37 when 1.16 stopped
    // spanning longs (seven entries per long).
    int longs = version.isAtLeast(ProtocolVersion.MINECRAFT_1_16) ? 37 : 36;
    buf.writeByte(TAG_COMPOUND);
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_2)) {
      buf.writeShort(0); // root name
    }
    writeLongArray(buf, "MOTION_BLOCKING", longs);
    writeLongArray(buf, "WORLD_SURFACE", longs);
    buf.writeByte(TAG_END);
  }

  private static void writeLongArray(ByteBuf buf, String name, int longs) {
    buf.writeByte(TAG_LONG_ARRAY);
    buf.writeShort(name.length());
    buf.writeCharSequence(name, java.nio.charset.StandardCharsets.US_ASCII);
    buf.writeInt(longs);
    buf.writeZero(longs * 8);
  }
}
