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
package dev.warp.proxy.world;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.config.KnownPacks;
import dev.warp.protocol.packet.config.RegistryData;
import dev.warp.protocol.packet.play.JoinGame;
import dev.warp.protocol.packet.play.SpawnInfo;
import dev.warp.protocol.packet.play.SystemChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.jspecify.annotations.Nullable;

/**
 * What the virtual world declares to a client of each version so that it accepts a world: the Join
 * Game and, where the version needs them, the registries (section 9.8.2 of the anti-bot research).
 *
 * <ul>
 *   <li>1.7 to 1.15: nothing but a numeric dimension.
 *   <li>1.16 to 1.20.1: a dimension codec inside Join Game, hand-written and minimal (one
 *       dimension, one biome, and from 1.19 the chat types and damage types the client resolves
 *       eagerly).
 *   <li>1.20.2 to 1.20.4: the same codec as one Registry Data packet.
 *   <li>1.20.5 and newer: Select Known Packs with {@code minecraft:core} at every version of the
 *       client's protocol, then Registry Data packets whose entries are ids without data: the
 *       client uses its built-in copy. No table per release is needed, only the names of the
 *       entries the client resolves eagerly.
 * </ul>
 *
 * <p>Built once per version on first use, then shared: the Join Game body and the registries never
 * change; the entity id is the session's.
 */
public final class WorldLayouts {

  /** The one dimension and world of the virtual world. */
  public static final String OVERWORLD = "minecraft:overworld";

  /** The one biome: id 0 in every registry the world declares. */
  static final String PLAINS = "minecraft:plains";

  /** Adventure mode: the player cannot break the platform. */
  static final int GAME_MODE_ADVENTURE = 2;

  /** The world's height in blocks before 1.20.5, where the proxy declares its own dimension. */
  static final int HEIGHT = 256;

  private static final int VIEW_DISTANCE = 2;

  /** The sea level a 1.21.2+ Join Game names; cosmetic. */
  private static final int SEA_LEVEL = 63;

  /**
   * Damage types a client resolves when its level is created (its {@code DamageSources}): every one
   * of them must exist, or the client crashes on join (the LimboAPI 1.19.4 lesson).
   */
  private static final List<String> EAGER_DAMAGE_TYPES =
      List.of(
          "in_fire",
          "lightning_bolt",
          "on_fire",
          "lava",
          "hot_floor",
          "in_wall",
          "cramming",
          "drown",
          "starve",
          "cactus",
          "fall",
          "fly_into_wall",
          "out_of_world",
          "generic",
          "magic",
          "wither",
          "dragon_breath",
          "dry_out",
          "sweet_berry_bush",
          "freeze",
          "stalagmite",
          "outside_border",
          "generic_kill");

  /** Added to the eager set in 1.21. */
  private static final String CAMPFIRE = "campfire";

  /**
   * The world as one version's client expects it.
   *
   * @param version the version
   * @param joinBody the Join Game body, the entity id apart
   * @param knownPacks the packs a 1.20.5+ client is asked to acknowledge, else {@code null}
   * @param registries the Registry Data packets (1.20.2+), in order
   */
  public record Layout(
      ProtocolVersion version,
      JoinGame.Body joinBody,
      @Nullable KnownPacks knownPacks,
      List<RegistryData> registries) {

    /**
     * Returns the Join Game for a session.
     *
     * @param entityId the player's entity id in the world
     * @return the packet
     */
    public JoinGame joinGame(int entityId) {
      return new JoinGame(entityId, false, joinBody);
    }
  }

  private final Map<ProtocolVersion, Layout> layouts = new ConcurrentHashMap<>();

  /** Creates the layouts, built lazily per version. */
  public WorldLayouts() {}

  /**
   * Returns the layout of a version.
   *
   * @param version the client's version
   * @return its layout
   */
  public Layout of(ProtocolVersion version) {
    return layouts.computeIfAbsent(version, WorldLayouts::build);
  }

  // ---------------------------------------------------------------------------
  // Per version
  // ---------------------------------------------------------------------------

  private static Layout build(ProtocolVersion version) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
      return new Layout(version, opaqueBody(version), knownPacks(version), idRegistries(version));
    }
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_2)) {
      byte[] codec = codec(version).nameless();
      return new Layout(version, opaqueBody(version), null, List.of(new RegistryData("", codec)));
    }
    return new Layout(version, decodedBody(version), null, List.of());
  }

  /** The Join Game body before 1.20.2, with the codec inside from 1.16. */
  private static JoinGame.Decoded decodedBody(ProtocolVersion version) {
    byte[] empty = new byte[0];
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_16)) {
      SpawnInfo spawn =
          new SpawnInfo(
              0, "", empty, "", 0, 0, GAME_MODE_ADVENTURE, 0, "flat", false, true, null, 0);
      return new JoinGame.Decoded(1, List.of(), empty, VIEW_DISTANCE, 0, false, true, spawn);
    }
    byte[] registry;
    byte[] dimensionTypeData = empty;
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_16_2)) {
      registry = codec116().named();
    } else {
      registry = codec(version).named();
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_19)) {
        dimensionTypeData = dimensionElement(version).named();
      }
    }
    SpawnInfo spawn =
        new SpawnInfo(
            0,
            OVERWORLD,
            dimensionTypeData,
            OVERWORLD,
            0,
            0,
            GAME_MODE_ADVENTURE,
            -1,
            "",
            false,
            true,
            null,
            0);
    return new JoinGame.Decoded(
        1, List.of(OVERWORLD), registry, VIEW_DISTANCE, VIEW_DISTANCE, false, true, spawn);
  }

  /** The Join Game body from 1.20.2: verbatim bytes after the hardcore flag. */
  private static JoinGame.Opaque opaqueBody(ProtocolVersion version) {
    var buf = Unpooled.buffer();
    try {
      VarInt.write(buf, 1);
      McString.write(buf, OVERWORLD);
      VarInt.write(buf, 1); // max players
      VarInt.write(buf, VIEW_DISTANCE);
      VarInt.write(buf, VIEW_DISTANCE); // simulation distance
      buf.writeBoolean(false); // reduced debug info
      buf.writeBoolean(true); // respawn screen
      buf.writeBoolean(false); // limited crafting
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
        VarInt.write(buf, 0); // dimension type: the first (only) entry of the registry
      } else {
        McString.write(buf, OVERWORLD);
      }
      McString.write(buf, OVERWORLD);
      buf.writeLong(0); // hashed seed
      buf.writeByte(GAME_MODE_ADVENTURE);
      buf.writeByte(-1); // previous game mode
      buf.writeBoolean(false); // debug
      buf.writeBoolean(true); // flat
      buf.writeBoolean(false); // no last death location
      VarInt.write(buf, 0); // portal cooldown
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2)) {
        VarInt.write(buf, SEA_LEVEL);
      }
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_26_2)) {
        buf.writeBoolean(false); // online mode, as a backend behind the proxy declares
      }
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
        buf.writeBoolean(false); // enforces secure chat
      }
      return new JoinGame.Opaque(ByteBufUtil.getBytes(buf));
    } finally {
      buf.release();
    }
  }

  /**
   * The core pack at every release of the client's protocol: 1.21.7 and 1.21.8 share one protocol
   * and a client acknowledges the pack whose version string is its own.
   */
  private static KnownPacks knownPacks(ProtocolVersion version) {
    List<KnownPacks.Pack> packs = new ArrayList<>();
    for (ProtocolVersion release : ProtocolVersion.values()) {
      if (release.protocol() == version.protocol()) {
        packs.add(new KnownPacks.Pack("minecraft", "core", release.name()));
      }
    }
    return new KnownPacks(packs);
  }

  /** 1.20.5+: one Registry Data per registry, every entry an id without data. */
  private static List<RegistryData> idRegistries(ProtocolVersion version) {
    List<String> damage = new ArrayList<>(EAGER_DAMAGE_TYPES);
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21)) {
      damage.add(CAMPFIRE);
    }
    return List.of(
        idRegistry("minecraft:dimension_type", List.of(OVERWORLD)),
        idRegistry("minecraft:worldgen/biome", List.of(PLAINS)),
        idRegistry("minecraft:damage_type", damage.stream().map(WorldLayouts::vanilla).toList()));
  }

  private static RegistryData idRegistry(String registry, List<String> entries) {
    var buf = Unpooled.buffer();
    try {
      VarInt.write(buf, entries.size());
      for (String entry : entries) {
        McString.write(buf, entry);
        buf.writeBoolean(false); // no data: the client's own pack has it
      }
      return new RegistryData(registry, ByteBufUtil.getBytes(buf));
    } finally {
      buf.release();
    }
  }

  private static String vanilla(String path) {
    return "minecraft:" + path;
  }

  // ---------------------------------------------------------------------------
  // Hand-written registries, 1.16 to 1.20.4
  // ---------------------------------------------------------------------------

  /** 1.16 and 1.16.1: a list of dimensions, each with its name among its fields. */
  private static Nbt.Compound codec116() {
    Nbt.Compound overworld =
        new Nbt.Compound()
            .put("name", OVERWORLD)
            .put("natural", true)
            .put("ambient_light", 0f)
            .put("has_ceiling", false)
            .put("has_skylight", true)
            .put("shrunk", false)
            .put("ultrawarm", false)
            .put("has_raids", true)
            .put("respawn_anchor_works", false)
            .put("bed_works", true)
            .put("piglin_safe", false)
            .put("logical_height", HEIGHT)
            .put("infiniburn", "minecraft:infiniburn_overworld");
    return new Nbt.Compound().putCompounds("dimension", List.of(overworld));
  }

  /** 1.16.2 to 1.20.4: one registry per networked registry the client has. */
  private static Nbt.Compound codec(ProtocolVersion version) {
    Nbt.Compound codec =
        new Nbt.Compound()
            .put(
                "minecraft:dimension_type",
                registry(
                    "minecraft:dimension_type",
                    List.of(entry(OVERWORLD, dimensionElement(version)))))
            .put(
                "minecraft:worldgen/biome",
                registry(
                    "minecraft:worldgen/biome", List.of(entry(PLAINS, biomeElement(version)))));
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
      codec.put("minecraft:chat_type", registry("minecraft:chat_type", chatTypes(version)));
    }
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_4)) {
      List<Nbt.Compound> damage = new ArrayList<>();
      int id = 0;
      for (String type : EAGER_DAMAGE_TYPES) {
        damage.add(entry(vanilla(type), id++, damageElement(type)));
      }
      codec.put("minecraft:damage_type", registry("minecraft:damage_type", damage));
      codec.put("minecraft:trim_pattern", registry("minecraft:trim_pattern", List.of()));
      codec.put("minecraft:trim_material", registry("minecraft:trim_material", List.of()));
    }
    return codec;
  }

  private static Nbt.Compound registry(String type, List<Nbt.Compound> entries) {
    return new Nbt.Compound().put("type", type).putCompounds("value", entries);
  }

  private static Nbt.Compound entry(String name, Nbt.Compound element) {
    return entry(name, 0, element);
  }

  private static Nbt.Compound entry(String name, int id, Nbt.Compound element) {
    return new Nbt.Compound().put("name", name).put("id", id).put("element", element);
  }

  private static Nbt.Compound dimensionElement(ProtocolVersion version) {
    Nbt.Compound element =
        new Nbt.Compound()
            .put("piglin_safe", false)
            .put("natural", true)
            .put("ambient_light", 0f)
            .put(
                "infiniburn",
                version.isAtLeast(ProtocolVersion.MINECRAFT_1_18_2)
                    ? "#minecraft:infiniburn_overworld"
                    : "minecraft:infiniburn_overworld")
            .put("respawn_anchor_works", false)
            .put("has_skylight", true)
            .put("bed_works", true)
            .put("effects", OVERWORLD)
            .put("has_raids", true)
            .put("logical_height", HEIGHT)
            .put("coordinate_scale", 1.0d)
            .put("ultrawarm", false)
            .put("has_ceiling", false);
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_17)) {
      element.put("min_y", 0).put("height", HEIGHT);
    }
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
      element.put("monster_spawn_light_level", 0).put("monster_spawn_block_light_limit", 0);
    }
    return element;
  }

  private static Nbt.Compound biomeElement(ProtocolVersion version) {
    Nbt.Compound element = new Nbt.Compound();
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_4)) {
      element.put("has_precipitation", false);
    } else {
      element.put("precipitation", "none");
    }
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_18)) {
      element.put("depth", 0.125f);
    }
    element.put("temperature", 0.8f);
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_18)) {
      element.put("scale", 0.05f);
    }
    element.put("downfall", 0.4f);
    if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_19)) {
      element.put("category", "plains");
    }
    element.put(
        "effects",
        new Nbt.Compound()
            .put("sky_color", 7907327)
            .put("water_fog_color", 329011)
            .put("fog_color", 12638463)
            .put("water_color", 4159204));
    return element;
  }

  /**
   * The chat types the client resolves by id. 1.19.0 alone names the type of a system message by id
   * (1 system, 2 game info in the action bar, see {@code SystemChatMessage}): the proxy's hold
   * messages would point at nothing without them. From 1.19.1 a system message carries a boolean.
   */
  private static List<Nbt.Compound> chatTypes(ProtocolVersion version) {
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
      return List.of(entry("minecraft:chat", chatElement()));
    }
    Nbt.Compound decoration =
        new Nbt.Compound()
            .put("translation_key", "chat.type.text")
            .putStrings("parameters", List.of("sender", "content"))
            .put("style", new Nbt.Compound());
    Nbt.Compound narration =
        new Nbt.Compound()
            .put("translation_key", "chat.type.text.narrate")
            .putStrings("parameters", List.of("sender", "content"))
            .put("style", new Nbt.Compound());
    Nbt.Compound chat =
        new Nbt.Compound()
            .put("chat", new Nbt.Compound().put("decoration", decoration))
            .put(
                "narration",
                new Nbt.Compound().put("decoration", narration).put("priority", "chat"));
    Nbt.Compound system =
        new Nbt.Compound()
            .put("chat", new Nbt.Compound())
            .put("narration", new Nbt.Compound().put("priority", "system"));
    Nbt.Compound gameInfo = new Nbt.Compound().put("overlay", new Nbt.Compound());
    return List.of(
        entry("minecraft:chat", 0, chat),
        entry("minecraft:system", SystemChatMessage.SYSTEM, system),
        entry("minecraft:game_info", SystemChatMessage.GAME_INFO, gameInfo));
  }

  /** 1.19.1+: the "chat" type as the vanilla registry defines it. */
  private static Nbt.Compound chatElement() {
    return new Nbt.Compound()
        .put(
            "chat",
            new Nbt.Compound()
                .put("translation_key", "chat.type.text")
                .putStrings("parameters", List.of("sender", "content")))
        .put(
            "narration",
            new Nbt.Compound()
                .put("translation_key", "chat.type.text.narrate")
                .putStrings("parameters", List.of("sender", "content")));
  }

  private static Nbt.Compound damageElement(String type) {
    return new Nbt.Compound().put("message_id", type).put("scaling", "never").put("exhaustion", 0f);
  }
}
