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
package dev.warp.protocol.bench;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.codec.VarLong;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Deterministic, synthetic corpus of clientbound PLAY packets ({@code [VarInt packet id][body]}).
 *
 * <p>The shapes mimic the structure — and therefore the compressibility — of real vanilla traffic:
 * paletted chunk sections with layered terrain and full sky-light arrays, delta-encoded entity
 * movement, entity metadata, block-update batches and profile-heavy player-info updates. They are
 * <b>not</b> real captures: corpora recorded from live servers will supersede this generator for
 * published numbers. All packet ids are drawn from ids the proxy does not register, so every packet
 * takes the blind-forwarding path.
 */
public final class PacketCorpus {

  /** Traffic profiles, each stressing a different cost of the forwarding path. */
  public enum Workload {
    /** Chunk packets only — the bandwidth-dominant case (joins, teleports, elytra flight). */
    CHUNK,
    /** Entity movement only — the packet-rate-dominant case (crowded hubs, PvP). */
    ENTITY_MOVE,
    /**
     * Gameplay mix by packet count: 60% movement, 22% metadata, 12% block updates and player info,
     * 4% chunks, 2% keep-alive-sized packets. Bytes are dominated by chunks, packets by movement.
     */
    MIXED
  }

  private static final int SECTIONS = 24;
  private static final int SECTION_VOLUME = 4096;
  private static final int LIGHT_ARRAY_BYTES = 2048;
  private static final String[] IDENTIFIERS = {
    "minecraft:stone", "minecraft:grass_block", "minecraft:oak_log", "minecraft:player",
    "minecraft:zombie", "minecraft:cow", "minecraft:diamond_sword", "minecraft:textures"
  };

  private PacketCorpus() {}

  /**
   * Generates a corpus of {@code count} packets for the given workload.
   *
   * @param workload the traffic profile
   * @param count the number of packets to generate
   * @param version the protocol version whose unregistered PLAY ids are used as packet ids
   * @param seed the random seed — the same seed always yields byte-identical corpora
   * @return unpooled heap buffers, one per packet, owned by the caller
   */
  public static List<ByteBuf> generate(
      Workload workload, int count, ProtocolVersion version, long seed) {
    int[] ids = blindPacketIds(version);
    SplittableRandom rng = new SplittableRandom(seed);
    List<ByteBuf> packets = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      ByteBuf buf = Unpooled.buffer();
      switch (workload) {
        case CHUNK -> chunk(buf, ids[0], rng);
        case ENTITY_MOVE -> entityMove(buf, ids[1], rng);
        case MIXED -> mixed(buf, ids, rng);
      }
      packets.add(buf);
    }
    return packets;
  }

  /**
   * Returns packet ids in {@code [0, 0x80)} that have no codec in the clientbound PLAY registry,
   * i.e. ids that the proxy blind-forwards.
   *
   * @param version the protocol version
   * @return at least five unregistered ids, in ascending order
   */
  public static int[] blindPacketIds(ProtocolVersion version) {
    PacketRegistry registry = StateRegistry.get(ProtocolState.PLAY, PacketDirection.CLIENTBOUND);
    int[] ids = new int[5];
    int found = 0;
    for (int id = 0x10; id < 0x80 && found < ids.length; id++) {
      if (registry.lookup(version, id) == null) {
        ids[found++] = id;
      }
    }
    if (found < ids.length) {
      throw new IllegalStateException("Not enough unregistered packet ids for " + version);
    }
    return ids;
  }

  // ---------------------------------------------------------------------------
  // Shapes
  // ---------------------------------------------------------------------------

  private static void mixed(ByteBuf buf, int[] ids, SplittableRandom rng) {
    int roll = rng.nextInt(100);
    if (roll < 60) {
      entityMove(buf, ids[1], rng);
    } else if (roll < 82) {
      entityMetadata(buf, ids[2], rng);
    } else if (roll < 88) {
      blockUpdates(buf, ids[3], rng);
    } else if (roll < 94) {
      playerInfo(buf, ids[4], rng);
    } else if (roll < 98) {
      chunk(buf, ids[0], rng);
    } else {
      VarInt.write(buf, ids[2]);
      buf.writeLong(rng.nextLong());
    }
  }

  /** Move Entity Pos/Rot: entity id, three short deltas, two angles, on-ground flag (~12 B). */
  private static void entityMove(ByteBuf buf, int id, SplittableRandom rng) {
    VarInt.write(buf, id);
    VarInt.write(buf, rng.nextInt(1, 5000));
    buf.writeShort(rng.nextInt(-256, 256));
    buf.writeShort(rng.nextInt(-32, 32));
    buf.writeShort(rng.nextInt(-256, 256));
    buf.writeByte(rng.nextInt(256));
    buf.writeByte(rng.nextInt(-64, 64));
    buf.writeBoolean(rng.nextInt(10) != 0);
  }

  /** Set Entity Data: a handful of indexed, typed values (~30–250 B, mostly below threshold). */
  private static void entityMetadata(ByteBuf buf, int id, SplittableRandom rng) {
    VarInt.write(buf, id);
    VarInt.write(buf, rng.nextInt(1, 5000));
    int entries = rng.nextInt(1, 12);
    for (int i = 0; i < entries; i++) {
      buf.writeByte(i);
      int type = rng.nextInt(4);
      VarInt.write(buf, type);
      switch (type) {
        case 0 -> buf.writeByte(rng.nextInt(256));
        case 1 -> VarInt.write(buf, rng.nextInt(1 << 20));
        case 2 -> buf.writeFloat((float) rng.nextDouble(20));
        default -> McString.write(buf, IDENTIFIERS[rng.nextInt(IDENTIFIERS.length)]);
      }
    }
    buf.writeByte(0xFF);
  }

  /** Update Section Blocks: section position plus packed (state id, local position) longs. */
  private static void blockUpdates(ByteBuf buf, int id, SplittableRandom rng) {
    VarInt.write(buf, id);
    buf.writeLong(rng.nextLong());
    int count = rng.nextInt(30, 250);
    VarInt.write(buf, count);
    for (int i = 0; i < count; i++) {
      long state = 1 + rng.nextInt(12) * 16L;
      VarLong.write(buf, state << 12 | rng.nextInt(SECTION_VOLUME));
    }
  }

  /** Player Info Update: profiles with names and base64-like texture properties (~0.4–3 KiB). */
  private static void playerInfo(ByteBuf buf, int id, SplittableRandom rng) {
    VarInt.write(buf, id);
    buf.writeByte(0x3F);
    int players = rng.nextInt(1, 6);
    VarInt.write(buf, players);
    for (int i = 0; i < players; i++) {
      buf.writeLong(rng.nextLong());
      buf.writeLong(rng.nextLong());
      McString.write(buf, "Player" + rng.nextInt(100_000));
      VarInt.write(buf, 1);
      McString.write(buf, IDENTIFIERS[7]);
      McString.write(buf, base64Like(rng, rng.nextInt(300, 500)));
      buf.writeBoolean(false);
      VarInt.write(buf, rng.nextInt(4));
      buf.writeBoolean(true);
      VarInt.write(buf, rng.nextInt(20, 200));
    }
  }

  /**
   * Level Chunk With Light: heightmaps, 24 paletted sections and light arrays, generated from a
   * small terrain model (smooth surface, dirt and grass layers, stone/deepslate with blobs of
   * granite, diorite, andesite, tuff and gravel, ore veins, worm caves, trees and grass). Raw size
   * is dominated by sky-light arrays (~40–60 KiB raw), as in vanilla.
   */
  private static void chunk(ByteBuf buf, int id, SplittableRandom rng) {
    VarInt.write(buf, id);
    buf.writeInt(rng.nextInt(-2000, 2000));
    buf.writeInt(rng.nextInt(-2000, 2000));
    Terrain terrain = Terrain.random(rng);

    // Heightmaps: 3 types, 256 heights packed as 9-bit entries, 7 per long.
    VarInt.write(buf, 3);
    for (int type = 0; type < 3; type++) {
      VarInt.write(buf, type);
      VarInt.write(buf, 37);
      for (int l = 0; l < 37; l++) {
        long packed = 0;
        for (int e = 0; e < 7 && l * 7 + e < 256; e++) {
          packed |= (long) (terrain.height[l * 7 + e] + 1 + type) << (e * 9);
        }
        buf.writeLong(packed);
      }
    }

    ByteBuf sections = Unpooled.buffer(32 * 1024);
    for (int s = 0; s < SECTIONS; s++) {
      writeSection(sections, s, terrain, rng);
    }
    VarInt.write(buf, sections.readableBytes());
    buf.writeBytes(sections);
    sections.release();

    // Block entities: none or one small chest.
    if (rng.nextInt(4) == 0) {
      VarInt.write(buf, 1);
      buf.writeByte(rng.nextInt(256));
      buf.writeShort(rng.nextInt(-64, 320));
      VarInt.write(buf, 2);
      buf.writeByte(0x0A);
      buf.writeByte(0x00);
    } else {
      VarInt.write(buf, 0);
    }

    // Light masks cover 26 sections (one border section below and above). Sky light is sent from
    // the lowest section touching the surface upwards; block light for two cave sections.
    int firstSky = terrain.minHeight() / 16 + 1;
    int skySections = SECTIONS + 2 - firstSky;
    long skyMask = ((1L << skySections) - 1) << firstSky;
    long blockMask = 0b11L << 4;
    long all = (1L << (SECTIONS + 2)) - 1;
    for (long mask : new long[] {skyMask, blockMask, ~skyMask & all, ~blockMask & all}) {
      VarInt.write(buf, 1);
      buf.writeLong(mask);
    }
    VarInt.write(buf, skySections);
    for (int s = firstSky; s < SECTIONS + 2; s++) {
      VarInt.write(buf, LIGHT_ARRAY_BYTES);
      int minY = (s - 1) * 16;
      for (int b = 0; b < LIGHT_ARRAY_BYTES; b++) {
        int low = skyLight(terrain, 2 * b, minY);
        int high = skyLight(terrain, 2 * b + 1, minY);
        buf.writeByte(low | high << 4);
      }
    }
    VarInt.write(buf, 2);
    for (int s = 0; s < 2; s++) {
      VarInt.write(buf, LIGHT_ARRAY_BYTES);
      int torchX = rng.nextInt(16);
      int torchY = rng.nextInt(16);
      int torchZ = rng.nextInt(16);
      for (int b = 0; b < LIGHT_ARRAY_BYTES; b++) {
        int low = torchLight(2 * b, torchX, torchY, torchZ);
        int high = torchLight(2 * b + 1, torchX, torchY, torchZ);
        buf.writeByte(low | high << 4);
      }
    }
  }

  private static void writeSection(
      ByteBuf buf, int section, Terrain terrain, SplittableRandom rng) {
    int minY = section * 16;
    if (minY > terrain.maxHeight() + 8) {
      // Open sky: single-valued block and biome containers.
      buf.writeShort(0);
      buf.writeByte(0);
      VarInt.write(buf, Terrain.AIR);
      buf.writeByte(0);
      VarInt.write(buf, 1);
      return;
    }
    int nonAir = 0;
    long[] data = new long[SECTION_VOLUME * 4 / 64];
    for (int i = 0; i < SECTION_VOLUME; i++) {
      int block = terrain.blockAt(i & 15, minY + (i >> 8), (i >> 4) & 15, rng);
      if (block != Terrain.AIR) {
        nonAir++;
      }
      data[i >> 4] |= (long) block << ((i & 15) * 4);
    }
    buf.writeShort(nonAir);
    // A 16-entry palette at 4 bits per block; palette index i maps to global state id 1 + i * 17.
    buf.writeByte(4);
    VarInt.write(buf, 16);
    for (int p = 0; p < 16; p++) {
      VarInt.write(buf, 1 + p * 17);
    }
    for (long l : data) {
      buf.writeLong(l);
    }
    // Biomes: single-valued.
    buf.writeByte(0);
    VarInt.write(buf, 1 + (section & 1));
  }

  private static int skyLight(Terrain terrain, int index, int minY) {
    int column = index & 255;
    int y = minY + (index >> 8);
    int depth = terrain.height[column] - y;
    return depth < 0 ? 15 : Math.max(0, 14 - 2 * depth);
  }

  private static int torchLight(int index, int torchX, int torchY, int torchZ) {
    int distance =
        Math.abs((index & 15) - torchX)
            + Math.abs((index >> 8) - torchY)
            + Math.abs(((index >> 4) & 15) - torchZ);
    return Math.max(0, 14 - distance);
  }

  /** Per-chunk terrain features; block ids are palette indices. */
  private record Terrain(int[] height, int[][] blobs, int[][] veins, int[][] trees, double phase) {

    static final int AIR = 0;
    static final int STONE = 1;
    static final int DEEPSLATE = 2;
    static final int DIRT = 7;
    static final int GRASS_BLOCK = 8;
    static final int ORE = 9;
    static final int LOG = 14;
    static final int LEAVES = 15;
    static final int SHORT_GRASS = 13;

    static Terrain random(SplittableRandom rng) {
      int base = rng.nextInt(136, 148);
      double fx = rng.nextDouble(0.15, 0.5);
      double fz = rng.nextDouble(0.15, 0.5);
      double phase = rng.nextDouble(2 * Math.PI);
      int[] height = new int[256];
      for (int i = 0; i < 256; i++) {
        double wave = 3 * Math.sin((i & 15) * fx + phase) + 3 * Math.cos((i >> 4) * fz);
        height[i] = base + (int) Math.round(wave);
      }
      return new Terrain(
          height, features(rng, 8, 2, 5, 3, 7), features(rng, 10, 1, 2, 9, 12), trees(rng), phase);
    }

    /** Spheres {x, y, z, radius, block} spread over the underground part of the chunk. */
    private static int[][] features(
        SplittableRandom rng, int count, int minRadius, int maxRadius, int minBlock, int maxBlock) {
      int[][] spheres = new int[count][];
      for (int i = 0; i < count; i++) {
        spheres[i] =
            new int[] {
              rng.nextInt(16),
              rng.nextInt(0, 130),
              rng.nextInt(16),
              rng.nextInt(minRadius, maxRadius + 1),
              rng.nextInt(minBlock, maxBlock + 1)
            };
      }
      return spheres;
    }

    private static int[][] trees(SplittableRandom rng) {
      int[][] trees = new int[rng.nextInt(0, 3)][];
      for (int i = 0; i < trees.length; i++) {
        trees[i] = new int[] {rng.nextInt(2, 14), rng.nextInt(2, 14)};
      }
      return trees;
    }

    int maxHeight() {
      int max = 0;
      for (int h : height) {
        max = Math.max(max, h);
      }
      return max;
    }

    int minHeight() {
      int min = Integer.MAX_VALUE;
      for (int h : height) {
        min = Math.min(min, h);
      }
      return min;
    }

    int blockAt(int x, int y, int z, SplittableRandom rng) {
      int surface = height[z << 4 | x];
      if (y > surface) {
        for (int[] tree : trees) {
          int dx = Math.abs(x - tree[0]);
          int dz = Math.abs(z - tree[1]);
          int above = y - surface;
          if (dx == 0 && dz == 0 && above <= 5) {
            return LOG;
          }
          if (dx <= 2 && dz <= 2 && above >= 4 && above <= 7) {
            return LEAVES;
          }
        }
        return y == surface + 1 && rng.nextInt(5) == 0 ? SHORT_GRASS : AIR;
      }
      if (y == surface) {
        return GRASS_BLOCK;
      }
      if (y > surface - 4) {
        return DIRT;
      }
      if (Math.sin(x * 0.45 + y * 0.3 + phase) + Math.cos(z * 0.4 - y * 0.25) > 1.45) {
        return AIR; // worm cave
      }
      for (int[] vein : veins) {
        if (inSphere(vein, x, y, z)) {
          return vein[4];
        }
      }
      for (int[] blob : blobs) {
        if (inSphere(blob, x, y, z)) {
          return blob[4];
        }
      }
      if (rng.nextInt(400) == 0) {
        return ORE;
      }
      return y < 64 ? DEEPSLATE : STONE;
    }

    private static boolean inSphere(int[] sphere, int x, int y, int z) {
      int dx = x - sphere[0];
      int dy = y - sphere[1];
      int dz = z - sphere[2];
      return dx * dx + dy * dy + dz * dz <= sphere[3] * sphere[3];
    }
  }

  // ---------------------------------------------------------------------------
  // Primitives
  // ---------------------------------------------------------------------------

  private static String base64Like(SplittableRandom rng, int length) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    StringBuilder sb = new StringBuilder(length);
    // Real texture blobs are base64 JSON: long runs repeat across profiles.
    String prefix = "ewogICJ0aW1lc3RhbXAiIDogMTcw";
    sb.append(prefix);
    while (sb.length() < length) {
      sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
    }
    return sb.toString();
  }
}
