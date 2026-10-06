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

import static dev.warp.protocol.packet.play.SwitchPacketFixtures.bytes;
import static dev.warp.protocol.packet.play.SwitchPacketFixtures.decode;
import static dev.warp.protocol.packet.play.SwitchPacketFixtures.encode;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The packets Warp reads and writes to switch servers before 1.20.2, checked against the bytes an
 * independent implementation writes for every era (node-minecraft-protocol, see {@code
 * switch-packets.txt}): decoding must read every field and writing must give the same bytes back.
 */
@DisplayName("Server switch packets before 1.20.2")
class ServerSwitchPacketsTest {

  private static final UUID ALICE = UUID.fromString("5c39a8cb-1a3a-4c86-9a47-0f0aaf0e3a01");
  private static final UUID BOB = UUID.fromString("0f3e1b7c-7b5d-4b55-8e0a-2c3d4e5f6a7b");

  // ---------------------------------------------------------------------------
  // Join Game
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("JoinGame")
  class JoinGameCodec {

    static Stream<ProtocolVersion> decodedVersions() {
      return SwitchPacketFixtures.versionsWith("respawn");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decodedVersions")
    @DisplayName("should decode every field and write the same bytes back")
    void roundTrip(ProtocolVersion version) {
      JoinGame joinGame = decode(JoinGame.CODEC, version, "join_game");

      assertEquals(42, joinGame.entityId());
      assertFalse(joinGame.hardcore());
      JoinGame.Decoded body = assertInstanceOf(JoinGame.Decoded.class, joinGame.body());
      assertEquals(2, body.spawn().gameMode());
      assertEquals(20, body.maxPlayers());
      assertArrayEquals(bytes(version, "join_game"), encode(JoinGame.CODEC, joinGame, version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decodedVersions")
    @DisplayName("should read the dimension in the shape of its version")
    void dimension(ProtocolVersion version) {
      SpawnInfo spawn =
          ((JoinGame.Decoded) decode(JoinGame.CODEC, version, "join_game").body()).spawn();

      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_16)) {
        assertEquals(0, spawn.dimension());
        assertEquals("default", spawn.levelType());
        return;
      }
      assertEquals("minecraft:overworld", spawn.worldName());
      assertEquals(-1, spawn.previousGameMode());
      assertTrue(spawn.flat());
      if (SpawnInfo.dimensionTypeIsNbt(version)) {
        assertEquals(0x0A, spawn.dimensionTypeData()[0], "a compound tag");
        assertEquals("", spawn.dimensionType());
      } else {
        assertEquals("minecraft:overworld", spawn.dimensionType());
        assertEquals(0, spawn.dimensionTypeData().length);
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decodedVersions")
    @DisplayName("should read the last death location from 1.19 and the portal cooldown from 1.20")
    void laterFields(ProtocolVersion version) {
      SpawnInfo spawn =
          ((JoinGame.Decoded) decode(JoinGame.CODEC, version, "join_game").body()).spawn();

      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19)) {
        assertNotNull(spawn.lastDeathLocation());
        assertEquals("minecraft:overworld", spawn.lastDeathLocation().worldName());
      } else {
        assertNull(spawn.lastDeathLocation());
      }
      assertEquals(
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_20) ? 3 : 0, spawn.portalCooldown());
    }

    @Test
    @DisplayName("should pack the hardcore flag into the game mode byte before 1.16.2")
    void hardcoreBit() {
      JoinGame joinGame = decode(JoinGame.CODEC, ProtocolVersion.MINECRAFT_1_8, "join_game");
      JoinGame hardcore = new JoinGame(joinGame.entityId(), true, joinGame.body());

      byte[] wire = encode(JoinGame.CODEC, hardcore, ProtocolVersion.MINECRAFT_1_8);

      assertEquals(0x08 | 2, wire[4]);
      assertTrue(decodeBytes(wire, ProtocolVersion.MINECRAFT_1_8).hardcore());
    }

    @Test
    @DisplayName("should write another numeric dimension before 1.16")
    void otherDimension() {
      JoinGame joinGame = decode(JoinGame.CODEC, ProtocolVersion.MINECRAFT_1_12_2, "join_game");
      JoinGame.Decoded body = (JoinGame.Decoded) joinGame.body();
      JoinGame nether =
          new JoinGame(
              joinGame.entityId(),
              joinGame.hardcore(),
              body.withSpawn(body.spawn().withDimension(-1)));

      byte[] wire = encode(JoinGame.CODEC, nether, ProtocolVersion.MINECRAFT_1_12_2);

      // entity ID (int), game mode (byte), then the dimension as an int since 1.9.1
      assertArrayEquals(new byte[] {-1, -1, -1, -1}, Arrays.copyOfRange(wire, 5, 9));
    }

    @Test
    @DisplayName("should carry everything after the hardcore flag verbatim from 1.20.2")
    void opaqueFrom1202() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_20_2;
      JoinGame joinGame = decode(JoinGame.CODEC, version, "join_game");

      assertEquals(42, joinGame.entityId());
      assertTrue(joinGame.hardcore());
      assertInstanceOf(JoinGame.Opaque.class, joinGame.body());
      assertArrayEquals(bytes(version, "join_game"), encode(JoinGame.CODEC, joinGame, version));
    }

    @Test
    @DisplayName("should refuse a body that does not match the version")
    void bodyMustMatchVersion() {
      JoinGame decoded = decode(JoinGame.CODEC, ProtocolVersion.MINECRAFT_1_20_1, "join_game");
      JoinGame opaque = decode(JoinGame.CODEC, ProtocolVersion.MINECRAFT_1_20_2, "join_game");

      assertThrows(
          IllegalArgumentException.class,
          () -> encode(JoinGame.CODEC, decoded, ProtocolVersion.MINECRAFT_1_20_2));
      assertThrows(
          IllegalArgumentException.class,
          () -> encode(JoinGame.CODEC, opaque, ProtocolVersion.MINECRAFT_1_20_1));
    }

    private static JoinGame decodeBytes(byte[] wire, ProtocolVersion version) {
      ByteBuf buf = Unpooled.wrappedBuffer(wire);
      try {
        return JoinGame.CODEC.decode(buf, version);
      } finally {
        buf.release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Respawn
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Respawn")
  class RespawnCodec {

    static Stream<ProtocolVersion> respawnVersions() {
      return SwitchPacketFixtures.versionsWith("respawn");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("respawnVersions")
    @DisplayName("should build the respawn into the world of the join game, keeping nothing")
    void ofJoinGame(ProtocolVersion version) {
      SpawnInfo spawn =
          ((JoinGame.Decoded) decode(JoinGame.CODEC, version, "join_game").body()).spawn();

      byte[] wire = encode(Respawn.CODEC, Respawn.of(spawn), version);

      assertArrayEquals(bytes(version, "respawn"), wire);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("respawnVersions")
    @DisplayName("should decode a respawn and write the same bytes back")
    void roundTrip(ProtocolVersion version) {
      Respawn respawn = decode(Respawn.CODEC, version, "respawn");

      assertEquals(0, respawn.dataKept());
      assertEquals(2, respawn.spawn().gameMode());
      assertArrayEquals(bytes(version, "respawn"), encode(Respawn.CODEC, respawn, version));
    }

    @Test
    @DisplayName("should refuse the versions with a configuration phase")
    void notFrom1202() {
      SpawnInfo spawn =
          ((JoinGame.Decoded)
                  decode(JoinGame.CODEC, ProtocolVersion.MINECRAFT_1_20_1, "join_game").body())
              .spawn();

      assertThrows(
          IllegalArgumentException.class,
          () -> encode(Respawn.CODEC, Respawn.of(spawn), ProtocolVersion.MINECRAFT_1_20_2));
    }
  }

  // ---------------------------------------------------------------------------
  // What a Join Game does not clear
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("TabListHeaderFooter")
  class TabListHeaderFooterCodec {

    static Stream<ProtocolVersion> headerFooterVersions() {
      return SwitchPacketFixtures.versionsWith("header_footer_empty");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("headerFooterVersions")
    @DisplayName("should clear the header and footer with empty components")
    void empty(ProtocolVersion version) {
      byte[] wire = encode(TabListHeaderFooter.CODEC, TabListHeaderFooter.empty(version), version);

      assertArrayEquals(bytes(version, "header_footer_empty"), wire);
      TabListHeaderFooter decoded =
          decode(TabListHeaderFooter.CODEC, version, "header_footer_empty");
      assertArrayEquals(TabListHeaderFooter.empty(version).header(), decoded.header());
    }
  }

  @Nested
  @DisplayName("ClearTitles")
  class ClearTitlesCodec {

    static Stream<ProtocolVersion> titleVersions() {
      return SwitchPacketFixtures.versionsWith("clear_titles_reset");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("titleVersions")
    @DisplayName("should reset or hide the title with the action of the version")
    void resetAndHide(ProtocolVersion version) {
      assertArrayEquals(
          bytes(version, "clear_titles_reset"),
          encode(ClearTitles.CODEC, new ClearTitles(true), version));
      assertArrayEquals(
          bytes(version, "clear_titles_hide"),
          encode(ClearTitles.CODEC, new ClearTitles(false), version));
      assertTrue(decode(ClearTitles.CODEC, version, "clear_titles_reset").reset());
      assertFalse(decode(ClearTitles.CODEC, version, "clear_titles_hide").reset());
    }
  }

  @Nested
  @DisplayName("BossBar")
  class BossBarCodec {

    static Stream<ProtocolVersion> bossBarVersions() {
      return SwitchPacketFixtures.versionsWith("boss_bar_add");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bossBarVersions")
    @DisplayName("should read the bar and the action, and write the same bytes back")
    void roundTrip(ProtocolVersion version) {
      for (String fixture : List.of("boss_bar_add", "boss_bar_health", "boss_bar_remove")) {
        BossBar bossBar = decode(BossBar.CODEC, version, fixture);

        assertEquals(ALICE, bossBar.uuid());
        assertArrayEquals(bytes(version, fixture), encode(BossBar.CODEC, bossBar, version));
      }
      assertEquals(BossBar.ADD, decode(BossBar.CODEC, version, "boss_bar_add").action());
      assertEquals(BossBar.REMOVE, decode(BossBar.CODEC, version, "boss_bar_remove").action());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bossBarVersions")
    @DisplayName("should remove a boss bar")
    void remove(ProtocolVersion version) {
      assertArrayEquals(
          bytes(version, "boss_bar_remove"), encode(BossBar.CODEC, BossBar.remove(ALICE), version));
    }
  }

  @Nested
  @DisplayName("PlayerInfo (1.8 to 1.19.2)")
  class PlayerInfoCodec {

    static Stream<ProtocolVersion> playerInfoVersions() {
      return SwitchPacketFixtures.versionsWith("player_info_add");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("playerInfoVersions")
    @DisplayName("should read the action and the entries' UUIDs, and write the same bytes back")
    void roundTrip(ProtocolVersion version) {
      PlayerInfo added = decode(PlayerInfo.CODEC, version, "player_info_add");
      PlayerInfo latency = decode(PlayerInfo.CODEC, version, "player_info_latency");
      PlayerInfo renamed = decode(PlayerInfo.CODEC, version, "player_info_display_name");
      PlayerInfo removed = decode(PlayerInfo.CODEC, version, "player_info_remove");

      assertEquals(PlayerInfo.ADD_PLAYER, added.action());
      assertEquals(List.of(ALICE, BOB), added.profileIds());
      assertEquals(List.of(ALICE), latency.profileIds());
      assertEquals(List.of(BOB), renamed.profileIds());
      assertEquals(PlayerInfo.REMOVE_PLAYER, removed.action());
      assertEquals(List.of(ALICE, BOB), removed.profileIds());
      for (String fixture :
          List.of(
              "player_info_add",
              "player_info_latency",
              "player_info_display_name",
              "player_info_remove")) {
        PlayerInfo info = decode(PlayerInfo.CODEC, version, fixture);
        assertArrayEquals(bytes(version, fixture), encode(PlayerInfo.CODEC, info, version));
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("playerInfoVersions")
    @DisplayName("should remove players from the tab list")
    void remove(ProtocolVersion version) {
      assertArrayEquals(
          bytes(version, "player_info_remove"),
          encode(PlayerInfo.CODEC, PlayerInfo.remove(List.of(ALICE, BOB)), version));
    }
  }

  @Nested
  @DisplayName("PlayerInfoUpdate and PlayerInfoRemove (1.19.3 to 1.20.1)")
  class PlayerInfoUpdateCodec {

    static Stream<ProtocolVersion> playerInfoUpdateVersions() {
      return SwitchPacketFixtures.versionsWith("player_info_update_all");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("playerInfoUpdateVersions")
    @DisplayName("should read every action of every entry, and write the same bytes back")
    void update(ProtocolVersion version) {
      PlayerInfoUpdate all = decode(PlayerInfoUpdate.CODEC, version, "player_info_update_all");
      PlayerInfoUpdate latency =
          decode(PlayerInfoUpdate.CODEC, version, "player_info_update_latency");

      assertEquals(0x3F, all.actions());
      assertEquals(List.of(ALICE, BOB), all.profileIds());
      assertEquals(PlayerInfoUpdate.UPDATE_LATENCY, latency.actions());
      assertEquals(List.of(ALICE), latency.profileIds());
      assertArrayEquals(
          bytes(version, "player_info_update_all"), encode(PlayerInfoUpdate.CODEC, all, version));
      assertArrayEquals(
          bytes(version, "player_info_update_latency"),
          encode(PlayerInfoUpdate.CODEC, latency, version));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("playerInfoUpdateVersions")
    @DisplayName("should read and write the removal of players")
    void remove(ProtocolVersion version) {
      PlayerInfoRemove removed = decode(PlayerInfoRemove.CODEC, version, "player_info_remove");

      assertEquals(List.of(ALICE, BOB), removed.profileIds());
      assertArrayEquals(
          bytes(version, "player_info_remove"),
          encode(PlayerInfoRemove.CODEC, new PlayerInfoRemove(List.of(ALICE, BOB)), version));
    }
  }

  // ---------------------------------------------------------------------------
  // Commands before 1.19
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("LegacyChatMessage")
  class LegacyChatMessageCodec {

    static Stream<ProtocolVersion> chatVersions() {
      return SwitchPacketFixtures.versionsWith("chat_server");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chatVersions")
    @DisplayName("should read a command typed in chat and write it back")
    void command(ProtocolVersion version) {
      LegacyChatMessage message = decode(LegacyChatMessage.CODEC, version, "chat_server");

      assertEquals("/server survival", message.message());
      assertArrayEquals(
          bytes(version, "chat_server"), encode(LegacyChatMessage.CODEC, message, version));
    }
  }
}
