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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.config.KnownPacks;
import dev.warp.protocol.packet.config.RegistryData;
import dev.warp.protocol.packet.play.JoinGame;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** What the world declares to each version. */
@DisplayName("world layouts")
class WorldLayoutsTest {

  private final WorldLayouts layouts = new WorldLayouts();

  static Stream<ProtocolVersion> everyVersion() {
    return ProtocolVersion.values().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyVersion")
  @DisplayName("should have a Join Game that encodes for every version")
  void joinGameEncodes(ProtocolVersion version) {
    JoinGame joinGame = layouts.of(version).joinGame(42);
    ByteBuf buf = Unpooled.buffer();
    try {
      JoinGame.CODEC.encode(joinGame, buf, version);
      assertTrue(buf.readableBytes() > 4);
      if (!version.supportsConfigurationState()) {
        JoinGame decoded = JoinGame.CODEC.decode(buf, version);
        assertEquals(42, decoded.entityId());
        JoinGame.Decoded body = assertInstanceOf(JoinGame.Decoded.class, decoded.body());
        assertEquals(WorldLayouts.GAME_MODE_ADVENTURE, body.spawn().gameMode());
        assertEquals(0, buf.readableBytes(), "every byte read back");
      }
    } finally {
      buf.release();
    }
  }

  @Test
  @DisplayName("should offer the core pack at every release of a shared protocol (1.21.7, 1.21.8)")
  void knownPacksPerRelease() {
    KnownPacks packs = layouts.of(ProtocolVersion.MINECRAFT_1_21_8).knownPacks();

    assertNotNull(packs);
    assertEquals(
        List.of(
            new KnownPacks.Pack("minecraft", "core", "1.21.7"),
            new KnownPacks.Pack("minecraft", "core", "1.21.8")),
        packs.packs());
  }

  @Test
  @DisplayName("should send id-only registries from 1.20.5: a dimension, a biome, the damage types")
  void idRegistries() {
    WorldLayouts.Layout layout = layouts.of(ProtocolVersion.MINECRAFT_1_21_4);

    List<RegistryData> registries = layout.registries();

    assertEquals(
        List.of("minecraft:dimension_type", "minecraft:worldgen/biome", "minecraft:damage_type"),
        registries.stream().map(RegistryData::registryId).toList());
    ByteBuf entries = Unpooled.wrappedBuffer(registries.getFirst().rawEntries());
    try {
      assertEquals(1, VarInt.read(entries));
      assertEquals(WorldLayouts.OVERWORLD, McString.read(entries));
      assertEquals(false, entries.readBoolean(), "no data: the client's pack has it");
    } finally {
      entries.release();
    }
    ByteBuf damage = Unpooled.wrappedBuffer(registries.getLast().rawEntries());
    try {
      assertEquals(24, VarInt.read(damage), "the eager damage types, campfire included");
    } finally {
      damage.release();
    }
  }

  @Test
  @DisplayName("should send one nameless NBT compound to 1.20.2 through 1.20.4, no known packs")
  void singleRegistryData() {
    WorldLayouts.Layout layout = layouts.of(ProtocolVersion.MINECRAFT_1_20_4);

    assertNull(layout.knownPacks());
    assertEquals(1, layout.registries().size());
    byte[] nbt = layout.registries().getFirst().rawEntries();
    assertEquals(10, nbt[0], "a compound");
    assertEquals(10, nbt[1], "a compound entry follows at once: no root name");
  }

  @Test
  @DisplayName("should put a named codec with the dimension's NBT in a 1.16.2 Join Game")
  void legacyCodec() {
    JoinGame.Decoded body =
        assertInstanceOf(
            JoinGame.Decoded.class, layouts.of(ProtocolVersion.MINECRAFT_1_16_4).joinBody());

    assertEquals(10, body.registry()[0]);
    assertEquals(0, body.registry()[1] | body.registry()[2], "an empty root name");
    assertTrue(body.spawn().dimensionTypeData().length > 20, "the dimension type as NBT");
    assertEquals(List.of(WorldLayouts.OVERWORLD), body.worldNames());
  }

  @Test
  @DisplayName("should register the system and game info chat types 1.19.0 resolves by id")
  void chatTypes119() {
    JoinGame.Decoded body =
        assertInstanceOf(
            JoinGame.Decoded.class, layouts.of(ProtocolVersion.MINECRAFT_1_19).joinBody());
    String registry = new String(body.registry(), StandardCharsets.ISO_8859_1);

    assertTrue(registry.contains("minecraft:chat"), registry);
    assertTrue(registry.contains("minecraft:system"), "SystemChatMessage writes type 1");
    assertTrue(registry.contains("minecraft:game_info"), "the action bar is type 2");
    assertTrue(registry.contains("priority"), "the 1.19.0 narration shape");
  }

  @Test
  @DisplayName(
      "should register the chat type alone from 1.19.1, where system messages carry a flag")
  void chatTypes1191() {
    JoinGame.Decoded body =
        assertInstanceOf(
            JoinGame.Decoded.class, layouts.of(ProtocolVersion.MINECRAFT_1_19_2).joinBody());
    String registry = new String(body.registry(), StandardCharsets.ISO_8859_1);

    assertTrue(registry.contains("minecraft:chat"), registry);
    assertFalse(registry.contains("minecraft:system"), registry);
    assertFalse(registry.contains("minecraft:game_info"), registry);
  }

  @Test
  @DisplayName("should name the dimension type and need no NBT for it from 1.19")
  void dimensionByName() {
    JoinGame.Decoded body =
        assertInstanceOf(
            JoinGame.Decoded.class, layouts.of(ProtocolVersion.MINECRAFT_1_19_4).joinBody());

    assertEquals(WorldLayouts.OVERWORLD, body.spawn().dimensionType());
    assertEquals(0, body.spawn().dimensionTypeData().length);
  }
}
