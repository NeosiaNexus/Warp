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
package dev.warp.protocol.command;

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_13;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_13_1;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_13_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_14;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_14_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_18_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_20_5;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_5;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_6;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_26_1;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_26_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_26_3;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.command.ArgumentParser.PlainParser;
import dev.warp.protocol.command.ArgumentParser.ResourceKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

/**
 * The argument types each protocol version knows, and their registry ids from 1.19.
 *
 * <p>From 1.13 to 1.18.2 the commands packet names an argument type by its identifier; from 1.19 by
 * its id in the {@code minecraft:command_argument_type} registry, which is its position in the
 * order the game registers the types in. Mojang inserts new types in the middle of that order and
 * removes old ones, so ids shift between versions (26.3 moved {@code minecraft:uuid} from 56 to
 * 61): a wrong table desyncs the whole packet, which is how Gate kicked every 26.3 operator
 * (minekube/gate#1230) and BungeeCord its 1.18.2 ones (SpigotMC/BungeeCord#3446).
 *
 * <p>So no id is written by hand here. {@link #TYPES} lists every vanilla type once, in the game's
 * registration order, with the versions that have it; a version's ids are the positions of the
 * types it has, counted in that order. Each version's table is an array built once, looked up by
 * index: no search, no hash order, nothing a plugin can change, unlike Velocity's registry, whose
 * lookup order changed from one start to the next (PaperMC/Velocity#1868). {@code
 * ArgumentTypesTest} checks every version's table, ids and length, against Mojang's own data: the
 * {@code registries.json} report of each release's server from 1.19, and the types its code
 * registers before.
 *
 * <p>Modded types are in no table: from 1.19 their ids depend on the mods a server runs, so a tree
 * holding one decodes as opaque, rather than as whatever vanilla type has that id.
 */
public final class ArgumentTypes {

  /** The first version that sends argument types as registry ids rather than identifiers. */
  public static final ProtocolVersion REGISTRY_SINCE = MINECRAFT_1_19;

  /** The first version whose packet carries the minimum of {@code minecraft:time}: 1.19.4. */
  public static final ProtocolVersion TIME_MINIMUM_SINCE = MINECRAFT_1_19_4;

  /** How the packet writes a type's properties. */
  enum Format {
    NONE,
    FLOAT,
    DOUBLE,
    INTEGER,
    LONG,
    STRING,
    ENTITY,
    SCORE_HOLDER,
    TIME,
    RESOURCE,
    FORGE_ENUM
  }

  /**
   * One vanilla argument type: its identifier, the format of its properties, and the versions that
   * have it.
   */
  private record Type(
      String identifier, Format format, ProtocolVersion since, @Nullable ProtocolVersion until) {}

  /**
   * Every vanilla argument type, in the order the game registers them: from 1.19, a version's
   * registry ids are the positions of the types it has, counted in this order. Types removed before
   * 1.19 come where they stood; their place no longer counts.
   */
  private static final List<Type> TYPES =
      List.of(
          type("brigadier:bool"),
          type("brigadier:float", Format.FLOAT),
          type("brigadier:double", Format.DOUBLE),
          type("brigadier:integer", Format.INTEGER),
          type("brigadier:long", Format.LONG, MINECRAFT_1_14_4),
          type("brigadier:string", Format.STRING),
          type("minecraft:entity", Format.ENTITY),
          type("minecraft:game_profile"),
          type("minecraft:block_pos"),
          type("minecraft:column_pos", MINECRAFT_1_13_1),
          type("minecraft:vec3"),
          type("minecraft:vec2"),
          type("minecraft:block_state"),
          type("minecraft:block_predicate"),
          type("minecraft:item_stack"),
          type("minecraft:item_predicate"),
          type("minecraft:color", MINECRAFT_1_13, MINECRAFT_26_1),
          type("minecraft:team_color", MINECRAFT_26_2),
          type("minecraft:hex_color", MINECRAFT_1_21_6),
          type("minecraft:component"),
          type("minecraft:style", MINECRAFT_1_20_3),
          type("minecraft:message"),
          type("minecraft:nbt", MINECRAFT_1_13, MINECRAFT_1_13_2),
          type("minecraft:nbt_compound_tag", MINECRAFT_1_14),
          type("minecraft:nbt_tag", MINECRAFT_1_14),
          type("minecraft:nbt_path"),
          type("minecraft:objective"),
          type("minecraft:objective_criteria"),
          type("minecraft:operation"),
          type("minecraft:particle"),
          type("minecraft:angle", MINECRAFT_1_16_2),
          type("minecraft:rotation"),
          type("minecraft:scoreboard_slot"),
          type("minecraft:score_holder", Format.SCORE_HOLDER),
          type("minecraft:swizzle"),
          type("minecraft:team"),
          type("minecraft:item_slot"),
          type("minecraft:item_slots", MINECRAFT_1_20_5),
          type("minecraft:resource_location"),
          type("minecraft:mob_effect", MINECRAFT_1_13, MINECRAFT_1_19_2),
          type("minecraft:function"),
          type("minecraft:entity_anchor"),
          type("minecraft:int_range"),
          type("minecraft:float_range"),
          type("minecraft:item_enchantment", MINECRAFT_1_13, MINECRAFT_1_19_2),
          type("minecraft:entity_summon", MINECRAFT_1_13, MINECRAFT_1_19_2),
          type("minecraft:dimension", MINECRAFT_1_13_1),
          type("minecraft:gamemode", MINECRAFT_1_19_3),
          type("minecraft:time", Format.TIME, MINECRAFT_1_14),
          type("minecraft:resource_or_tag", Format.RESOURCE, MINECRAFT_1_18_2),
          type("minecraft:resource_or_tag_key", Format.RESOURCE, MINECRAFT_1_19_3),
          type("minecraft:resource", Format.RESOURCE, MINECRAFT_1_18_2),
          type("minecraft:resource_key", Format.RESOURCE, MINECRAFT_1_19_3),
          type("minecraft:resource_selector", Format.RESOURCE, MINECRAFT_1_21_5),
          type("minecraft:template_mirror", MINECRAFT_1_19),
          type("minecraft:template_rotation", MINECRAFT_1_19),
          type("minecraft:heightmap", MINECRAFT_1_19_4),
          type("minecraft:loot_table", MINECRAFT_1_20_5),
          type("minecraft:loot_predicate", MINECRAFT_1_20_5),
          type("minecraft:loot_modifier", MINECRAFT_1_20_5),
          type("minecraft:context_float_provider", MINECRAFT_26_3),
          type("minecraft:context_int_provider", MINECRAFT_26_3),
          type("minecraft:slot_source", MINECRAFT_26_3),
          type("minecraft:dialog", MINECRAFT_1_21_6),
          type("minecraft:feature", MINECRAFT_26_3),
          type("minecraft:swing_animation", MINECRAFT_26_3),
          type("minecraft:uuid", MINECRAFT_1_16));

  /**
   * Types modded servers send by identifier before 1.19, which Warp reads too: Forge's, and the
   * CrossStitch wrapper ({@link ArgumentParser.WrappedParser}, read apart). Never in a table.
   */
  private static final Map<String, Format> LEGACY_MODDED =
      Map.of(
          ArgumentParser.ForgeEnumParser.IDENTIFIER,
          Format.FORGE_ENUM,
          "forge:modid",
          Format.NONE,
          "forge:structure_type",
          Format.NONE);

  /** Every vanilla type's format, by identifier, whatever the version. */
  private static final Map<String, Format> VANILLA_FORMATS;

  /**
   * The one parser of each known type without properties, vanilla or Forge: decoding a tree
   * allocates none.
   */
  private static final Map<String, PlainParser> PLAIN;

  /** Each version's table, by protocol number: null below 1.13. */
  private static final @Nullable Table[] TABLES;

  static {
    Map<String, Format> formats = new HashMap<>();
    for (Type type : TYPES) {
      formats.put(type.identifier(), type.format());
    }
    VANILLA_FORMATS = Map.copyOf(formats);
    formats.putAll(LEGACY_MODDED);
    Map<String, PlainParser> plain = new HashMap<>();
    formats.forEach(
        (identifier, format) -> {
          if (format == Format.NONE) {
            plain.put(identifier, new PlainParser(identifier));
          }
        });
    PLAIN = Map.copyOf(plain);
    int size = ProtocolVersion.latest().protocol() + 1;
    TABLES = new Table[size];
    for (ProtocolVersion version : ProtocolVersion.values()) {
      if (version.isAtLeast(MINECRAFT_1_13) && TABLES[version.protocol()] == null) {
        TABLES[version.protocol()] = new Table(version);
      }
    }
  }

  private ArgumentTypes() {}

  private static Type type(String identifier) {
    return new Type(identifier, Format.NONE, MINECRAFT_1_13, null);
  }

  private static Type type(String identifier, Format format) {
    return new Type(identifier, format, MINECRAFT_1_13, null);
  }

  private static Type type(String identifier, ProtocolVersion since) {
    return new Type(identifier, Format.NONE, since, null);
  }

  private static Type type(String identifier, ProtocolVersion since, ProtocolVersion until) {
    return new Type(identifier, Format.NONE, since, until);
  }

  private static Type type(String identifier, Format format, ProtocolVersion since) {
    return new Type(identifier, format, since, null);
  }

  // ---------------------------------------------------------------------------
  // Public queries
  // ---------------------------------------------------------------------------

  /**
   * Returns whether a version sends argument types as registry ids (1.19+) rather than as
   * identifiers.
   *
   * @param version a protocol version
   * @return {@code true} from 1.19
   */
  public static boolean usesRegistryIds(ProtocolVersion version) {
    return version.isAtLeast(REGISTRY_SINCE);
  }

  /**
   * Returns whether the vanilla client of a version knows an argument type. A proxy command whose
   * argument type the player's client does not know cannot reach it: fall back to a type it knows,
   * {@code brigadier:string} for instance.
   *
   * @param identifier the type's identifier
   * @param version the client's protocol version
   * @return {@code true} if the type is a vanilla one of that version
   */
  public static boolean isAvailable(String identifier, ProtocolVersion version) {
    Table table = tableOrNull(version);
    return table != null && table.contains(identifier);
  }

  /**
   * Returns the vanilla argument types of a version: from 1.19 in registry id order, the position
   * of each being its id; before, in the order the game registers them.
   *
   * @param version a protocol version from 1.13
   * @return the identifiers
   * @throws IllegalArgumentException if the version has no commands packet
   */
  public static List<String> identifiers(ProtocolVersion version) {
    return table(version).identifiers();
  }

  /**
   * Returns the registry id of an argument type at a version.
   *
   * @param identifier the type's identifier
   * @param version a protocol version
   * @return its id, or empty before 1.19 or if the version does not have the type
   */
  public static OptionalInt registryId(String identifier, ProtocolVersion version) {
    Table table = tableOrNull(version);
    int id = table == null || !table.usesIds() ? -1 : table.id(identifier);
    return id < 0 ? OptionalInt.empty() : OptionalInt.of(id);
  }

  /**
   * Returns the argument type a registry id stands for at a version.
   *
   * @param id the registry id
   * @param version a protocol version
   * @return the type's identifier, or {@code null} before 1.19 or if no vanilla type has that id
   */
  public static @Nullable String identifier(int id, ProtocolVersion version) {
    Table table = tableOrNull(version);
    return table == null || !table.usesIds() || id < 0 || id >= table.size()
        ? null
        : table.identifier(id);
  }

  // ---------------------------------------------------------------------------
  // Codec support
  // ---------------------------------------------------------------------------

  /** Whether a known type has properties, in which case no {@link PlainParser} may name it. */
  static boolean hasProperties(String identifier) {
    Format format = legacyFormat(identifier);
    return (format != null && format != Format.NONE)
        || identifier.equals(ArgumentParser.WrappedParser.IDENTIFIER);
  }

  /** The one parser of a known type without properties. */
  static PlainParser plain(String identifier) {
    return Objects.requireNonNull(PLAIN.get(identifier), identifier);
  }

  /** Whether a type is a vanilla one, of any version. */
  static boolean isVanilla(String identifier) {
    return VANILLA_FORMATS.containsKey(identifier);
  }

  /** The format of a type sent by identifier (1.13 to 1.18.2), or {@code null} if unknown. */
  static @Nullable Format legacyFormat(String identifier) {
    Format format = VANILLA_FORMATS.get(identifier);
    return format != null ? format : LEGACY_MODDED.get(identifier);
  }

  /** The kind of a {@link Format#RESOURCE} type. */
  static ResourceKind resourceKind(String identifier) {
    for (ResourceKind kind : ResourceKind.values()) {
      if (kind.identifier().equals(identifier)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("Not a resource argument type: " + identifier);
  }

  /** The table of a version from 1.13. */
  static Table table(ProtocolVersion version) {
    Table table = tableOrNull(version);
    if (table == null) {
      throw new IllegalArgumentException(version.name() + " has no commands packet (1.13+)");
    }
    return table;
  }

  /** Every registered version's protocol is in range: the tables go up to the latest. */
  private static @Nullable Table tableOrNull(ProtocolVersion version) {
    return TABLES[version.protocol()];
  }

  /** One version's argument types: by id (their position) and by identifier. */
  static final class Table {

    private final boolean usesIds;
    private final String[] identifiers;
    private final Format[] formats;

    private final Map<String, Integer> ids;

    Table(ProtocolVersion version) {
      this.usesIds = usesRegistryIds(version);
      List<Type> types = new ArrayList<>();
      for (Type type : TYPES) {
        ProtocolVersion until = type.until();
        if (version.isAtLeast(type.since()) && (until == null || version.isAtMost(until))) {
          types.add(type);
        }
      }
      int size = types.size();
      this.identifiers = new String[size];
      this.formats = new Format[size];
      Map<String, Integer> byIdentifier = new HashMap<>();
      for (int id = 0; id < size; id++) {
        Type type = types.get(id);
        identifiers[id] = type.identifier();
        formats[id] = type.format();
        byIdentifier.put(type.identifier(), id);
      }
      this.ids = Map.copyOf(byIdentifier);
    }

    boolean usesIds() {
      return usesIds;
    }

    int size() {
      return identifiers.length;
    }

    String identifier(int id) {
      return identifiers[id];
    }

    Format format(int id) {
      return formats[id];
    }

    /** The id (position) of a type, or -1 if this version does not have it. */
    int id(String identifier) {
      Integer id = ids.get(identifier);
      return id == null ? -1 : id;
    }

    boolean contains(String identifier) {
      return ids.containsKey(identifier);
    }

    List<String> identifiers() {
      return List.of(identifiers);
    }
  }
}
