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

import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_12_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_14_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_14_4;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_15_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_16;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_18_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_19_3;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_5;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_1_21_6;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_26_1;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_26_2;
import static dev.warp.protocol.ProtocolVersion.MINECRAFT_26_3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.command.ArgumentParser.ResourceKind;
import dev.warp.protocol.command.CommandFixtures.Types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("ArgumentTypes")
class ArgumentTypesTest {

  // ---------------------------------------------------------------------------
  // Against Mojang's data
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("against Mojang's data")
  class AgainstMojang {

    private static final Map<Integer, Types> MOJANG = CommandFixtures.argumentTypes();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.command.CommandFixtures#commandProtocols")
    @DisplayName("should have Mojang's argument types for every protocol, ids in order from 1.19")
    void matchMojang(ProtocolVersion version) {
      Types mojang =
          assertInstanceOf(
              Types.class,
              MOJANG.get(version.protocol()),
              () -> version + ": no fixture, run npm run command-trees");

      List<String> identifiers = ArgumentTypes.identifiers(version);

      // Built again here, not only once when the class loads, so that every step of the derivation
      // runs under test.
      assertEquals(identifiers, new ArgumentTypes.Table(version).identifiers());
      if (version.isAtLeast(MINECRAFT_1_19)) {
        assertEquals("registries.json", mojang.source());
        assertEquals(mojang.types(), identifiers, () -> version + " against " + mojang.version());
      } else {
        assertEquals("server code", mojang.source());
        assertEquals(
            new HashSet<>(mojang.types()),
            new HashSet<>(identifiers),
            () -> version + " against " + mojang.version());
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.protocol.command.CommandFixtures#commandProtocols")
    @DisplayName("should map every registry id to its type and back")
    void mapIdsBothWays(ProtocolVersion version) {
      List<String> identifiers = ArgumentTypes.identifiers(version);

      for (int id = 0; id < identifiers.size(); id++) {
        String identifier = identifiers.get(id);
        if (version.isAtLeast(MINECRAFT_1_19)) {
          assertEquals(identifier, ArgumentTypes.identifier(id, version));
          assertEquals(OptionalInt.of(id), ArgumentTypes.registryId(identifier, version));
        } else {
          assertNull(ArgumentTypes.identifier(id, version));
          assertEquals(OptionalInt.empty(), ArgumentTypes.registryId(identifier, version));
        }
        assertTrue(ArgumentTypes.isAvailable(identifier, version), identifier);
      }
      assertNull(ArgumentTypes.identifier(identifiers.size(), version));
      assertNull(ArgumentTypes.identifier(-1, version));
    }

    @Test
    @DisplayName("should have a fixture of exactly the protocols with a commands packet")
    void coverEveryProtocol() {
      List<Integer> protocols =
          CommandFixtures.commandProtocols().map(ProtocolVersion::protocol).sorted().toList();

      assertEquals(protocols, MOJANG.keySet().stream().sorted().toList());
    }
  }

  // ---------------------------------------------------------------------------
  // Version boundaries
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("on version boundaries")
  class Boundaries {

    static Stream<Arguments> availability() {
      return Stream.of(
          Arguments.of("brigadier:long", MINECRAFT_1_14_3, MINECRAFT_1_14_4),
          Arguments.of("minecraft:uuid", MINECRAFT_1_15_2, MINECRAFT_1_16),
          Arguments.of("minecraft:resource", ProtocolVersion.MINECRAFT_1_18, MINECRAFT_1_18_2),
          Arguments.of("minecraft:template_mirror", MINECRAFT_1_18_2, MINECRAFT_1_19),
          Arguments.of("minecraft:resource_key", MINECRAFT_1_19_2, MINECRAFT_1_19_3),
          Arguments.of("minecraft:gamemode", MINECRAFT_1_19_2, MINECRAFT_1_19_3),
          Arguments.of(
              "minecraft:resource_selector", ProtocolVersion.MINECRAFT_1_21_4, MINECRAFT_1_21_5),
          Arguments.of("minecraft:hex_color", MINECRAFT_1_21_5, MINECRAFT_1_21_6),
          Arguments.of("minecraft:dialog", MINECRAFT_1_21_5, MINECRAFT_1_21_6),
          Arguments.of("minecraft:team_color", MINECRAFT_26_1, MINECRAFT_26_2),
          Arguments.of("minecraft:swing_animation", MINECRAFT_26_2, MINECRAFT_26_3));
    }

    @ParameterizedTest(name = "{0} from {2}")
    @MethodSource("availability")
    @DisplayName("should know an argument type from the version that added it")
    void availableFrom(String identifier, ProtocolVersion before, ProtocolVersion since) {
      assertFalse(ArgumentTypes.isAvailable(identifier, before));
      assertTrue(ArgumentTypes.isAvailable(identifier, since));
    }

    static Stream<Arguments> removals() {
      return Stream.of(
          Arguments.of(
              "minecraft:nbt", ProtocolVersion.MINECRAFT_1_13_2, ProtocolVersion.MINECRAFT_1_14),
          Arguments.of("minecraft:mob_effect", MINECRAFT_1_19_2, MINECRAFT_1_19_3),
          Arguments.of("minecraft:item_enchantment", MINECRAFT_1_19_2, MINECRAFT_1_19_3),
          Arguments.of("minecraft:entity_summon", MINECRAFT_1_19_2, MINECRAFT_1_19_3),
          Arguments.of("minecraft:color", MINECRAFT_26_1, MINECRAFT_26_2));
    }

    @ParameterizedTest(name = "{0} until {1}")
    @MethodSource("removals")
    @DisplayName("should forget an argument type from the version that removed it")
    void availableUntil(String identifier, ProtocolVersion last, ProtocolVersion after) {
      assertTrue(ArgumentTypes.isAvailable(identifier, last));
      assertFalse(ArgumentTypes.isAvailable(identifier, after));
    }

    @Test
    @DisplayName("should move ids when a version inserts types before them, as 26.3 did")
    void shiftIds() {
      assertEquals(OptionalInt.of(56), ArgumentTypes.registryId("minecraft:uuid", MINECRAFT_26_2));
      assertEquals(OptionalInt.of(61), ArgumentTypes.registryId("minecraft:uuid", MINECRAFT_26_3));
      assertEquals(
          OptionalInt.of(55), ArgumentTypes.registryId("minecraft:dialog", MINECRAFT_26_2));
      assertEquals(
          OptionalInt.of(58), ArgumentTypes.registryId("minecraft:dialog", MINECRAFT_26_3));
    }

    @Test
    @DisplayName("should send registry ids from 1.19 and identifiers before")
    void registryIdsFrom119() {
      assertFalse(ArgumentTypes.usesRegistryIds(MINECRAFT_1_18_2));
      assertTrue(ArgumentTypes.usesRegistryIds(MINECRAFT_1_19));
    }

    @Test
    @DisplayName("should know no argument type before 1.13, which has no commands packet")
    void nothingBefore113() {
      assertFalse(ArgumentTypes.isAvailable("brigadier:bool", MINECRAFT_1_12_2));
      assertEquals(
          OptionalInt.empty(), ArgumentTypes.registryId("brigadier:bool", MINECRAFT_1_12_2));
      assertNull(ArgumentTypes.identifier(0, MINECRAFT_1_12_2));
      assertThrows(
          IllegalArgumentException.class, () -> ArgumentTypes.identifiers(MINECRAFT_1_12_2));
    }

    @Test
    @DisplayName("should know no modded argument type, on any version")
    void noModdedType() {
      for (ProtocolVersion version : List.of(MINECRAFT_1_16, MINECRAFT_26_3)) {
        assertFalse(ArgumentTypes.isAvailable("forge:enum", version));
        assertFalse(ArgumentTypes.isAvailable("crossstitch:mod_argument", version));
        assertEquals(OptionalInt.empty(), ArgumentTypes.registryId("forge:enum", version));
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Formats
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("formats")
  class Formats {

    @Test
    @DisplayName("should say which argument types have properties")
    void properties() {
      List<String> with = new ArrayList<>();
      for (String identifier :
          List.of(
              "brigadier:float",
              "brigadier:double",
              "brigadier:integer",
              "brigadier:long",
              "brigadier:string",
              "minecraft:entity",
              "minecraft:score_holder",
              "minecraft:time",
              "minecraft:resource",
              "minecraft:resource_or_tag",
              "minecraft:resource_key",
              "minecraft:resource_or_tag_key",
              "minecraft:resource_selector",
              "forge:enum",
              "crossstitch:mod_argument")) {
        if (ArgumentTypes.hasProperties(identifier)) {
          with.add(identifier);
        }
      }

      assertEquals(15, with.size(), () -> "with properties: " + with);
      assertFalse(ArgumentTypes.hasProperties("brigadier:bool"));
      assertFalse(ArgumentTypes.hasProperties("forge:modid"));
      assertFalse(ArgumentTypes.hasProperties("minecraft:uuid"));
      assertFalse(ArgumentTypes.hasProperties("example:unknown"));
    }

    @Test
    @DisplayName("should read vanilla and Forge types by identifier, and nothing else")
    void legacyFormats() {
      assertEquals(ArgumentTypes.Format.ENTITY, ArgumentTypes.legacyFormat("minecraft:entity"));
      assertEquals(ArgumentTypes.Format.FORGE_ENUM, ArgumentTypes.legacyFormat("forge:enum"));
      assertEquals(ArgumentTypes.Format.NONE, ArgumentTypes.legacyFormat("forge:modid"));
      assertEquals(ArgumentTypes.Format.NONE, ArgumentTypes.legacyFormat("forge:structure_type"));
      assertNull(ArgumentTypes.legacyFormat("example:unknown"));
      assertTrue(ArgumentTypes.isVanilla("minecraft:nbt"));
      assertFalse(ArgumentTypes.isVanilla("forge:modid"));
    }

    @Test
    @DisplayName("should share one parser per type without properties, vanilla or Forge")
    void sharedPlainParsers() {
      assertSame(ArgumentTypes.plain("minecraft:vec3"), ArgumentTypes.plain("minecraft:vec3"));
      assertEquals("forge:modid", ArgumentTypes.plain("forge:modid").identifier());
      assertThrows(NullPointerException.class, () -> ArgumentTypes.plain("brigadier:integer"));
    }

    @Test
    @DisplayName("should give each resource argument type its kind")
    void resourceKinds() {
      for (ResourceKind kind : ResourceKind.values()) {
        assertEquals(kind, ArgumentTypes.resourceKind(kind.identifier()));
      }
      assertThrows(
          IllegalArgumentException.class, () -> ArgumentTypes.resourceKind("minecraft:entity"));
    }
  }
}
