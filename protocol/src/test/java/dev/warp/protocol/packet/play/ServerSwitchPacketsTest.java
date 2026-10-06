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
import static dev.warp.protocol.packet.play.SwitchPacketFixtures.watch;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.SwitchPacketFixtures.Watched;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The packets Warp reads and writes to switch servers before 1.20.2, checked against the bytes an
 * independent implementation writes for every era (node-minecraft-protocol, see {@code
 * switch-packets.txt}): decoding must read every field and writing must give the same bytes back.
 * The tab list and boss bar packets are watched rather than decoded: watching must find every UUID
 * in the layout of each version, and read no further than it needs.
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

    /** A boss bar's UUID and its one-byte action: all the watch ever reads. */
    private static final int UUID_AND_ACTION = 17;

    static Stream<ProtocolVersion> bossBarVersions() {
      return SwitchPacketFixtures.versionsWith("boss_bar_add");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bossBarVersions")
    @DisplayName("should watch the bars added and removed, reading only the UUID and the action")
    void watchAddedAndRemoved(ProtocolVersion version) {
      Watched<BossBar> added = watch(BossBar.WATCH, version, "boss_bar_add");
      Watched<BossBar> removed = watch(BossBar.WATCH, version, "boss_bar_remove");

      assertEquals(new BossBar(ALICE, BossBar.ADD), added.packet());
      assertEquals(UUID_AND_ACTION, added.read(), "title, health, color, division, flags unread");
      assertEquals(new BossBar(ALICE, BossBar.REMOVE), removed.packet());
      assertEquals(removed.length(), removed.read());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bossBarVersions")
    @DisplayName("should report nothing for an update, reading no further than its action")
    void watchUpdate(ProtocolVersion version) {
      Watched<BossBar> health = watch(BossBar.WATCH, version, "boss_bar_health");

      assertNull(health.packet());
      assertEquals(UUID_AND_ACTION, health.read());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bossBarVersions")
    @DisplayName("should write the removal of a bar, and read it back")
    void remove(ProtocolVersion version) {
      assertArrayEquals(
          bytes(version, "boss_bar_remove"), encode(BossBar.CODEC, BossBar.remove(ALICE), version));
      assertEquals(BossBar.remove(ALICE), decode(BossBar.CODEC, version, "boss_bar_remove"));
    }

    @Test
    @DisplayName("should refuse to write or read anything but a removal: other fields are not kept")
    void onlyRemovals() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_12_2;

      assertThrows(
          IllegalArgumentException.class,
          () -> encode(BossBar.CODEC, new BossBar(ALICE, BossBar.ADD), version));
      assertThrows(DecoderException.class, () -> decode(BossBar.CODEC, version, "boss_bar_health"));
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
    @DisplayName("should watch the players added, skipping the fields of the version between UUIDs")
    void watchAdded(ProtocolVersion version) {
      Watched<PlayerInfo> added = watch(PlayerInfo.WATCH, version, "player_info_add");

      assertEquals(new PlayerInfo(PlayerInfo.ADD_PLAYER, List.of(ALICE, BOB)), added.packet());
      assertEquals(added.length(), added.read(), "every entry skipped to its last byte");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("playerInfoVersions")
    @DisplayName("should watch the players removed")
    void watchRemoved(ProtocolVersion version) {
      Watched<PlayerInfo> removed = watch(PlayerInfo.WATCH, version, "player_info_remove");

      assertEquals(new PlayerInfo(PlayerInfo.REMOVE_PLAYER, List.of(ALICE, BOB)), removed.packet());
      assertEquals(removed.length(), removed.read());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("playerInfoVersions")
    @DisplayName("should report nothing for an update, reading no further than its action")
    void watchUpdates(ProtocolVersion version) {
      for (String fixture : List.of("player_info_latency", "player_info_display_name")) {
        Watched<PlayerInfo> update = watch(PlayerInfo.WATCH, version, fixture);

        assertNull(update.packet(), fixture);
        assertEquals(1, update.read(), fixture);
      }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("playerInfoVersions")
    @DisplayName("should write the removal of players, and read it back")
    void remove(ProtocolVersion version) {
      PlayerInfo removal = PlayerInfo.remove(List.of(ALICE, BOB));

      assertArrayEquals(
          bytes(version, "player_info_remove"), encode(PlayerInfo.CODEC, removal, version));
      assertEquals(removal, decode(PlayerInfo.CODEC, version, "player_info_remove"));
    }

    @Test
    @DisplayName("should refuse to write or read anything but a removal: other fields are not kept")
    void onlyRemovals() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_12_2;
      PlayerInfo added = new PlayerInfo(PlayerInfo.ADD_PLAYER, List.of(ALICE));

      assertThrows(IllegalArgumentException.class, () -> encode(PlayerInfo.CODEC, added, version));
      assertThrows(
          DecoderException.class, () -> decode(PlayerInfo.CODEC, version, "player_info_latency"));
    }

    @Test
    @DisplayName("should reject an entry count the packet cannot hold")
    void impossibleCount() {
      ByteBuf buf = Unpooled.wrappedBuffer(new byte[] {PlayerInfo.ADD_PLAYER, (byte) 0xE8, 0x07});
      try {
        assertThrows(
            DecoderException.class,
            () -> PlayerInfo.WATCH.watch(buf, ProtocolVersion.MINECRAFT_1_12_2));
      } finally {
        buf.release();
      }
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
    @DisplayName("should watch the players added, skipping every flagged field between UUIDs")
    void watchAdded(ProtocolVersion version) {
      Watched<PlayerInfoUpdate> all =
          watch(PlayerInfoUpdate.WATCH, version, "player_info_update_all");

      assertEquals(new PlayerInfoUpdate(0x3F, List.of(ALICE, BOB)), all.packet());
      assertEquals(all.length(), all.read(), "every entry skipped to its last byte");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("playerInfoUpdateVersions")
    @DisplayName("should report nothing for an update adding no player, reading only its flags")
    void watchUpdate(ProtocolVersion version) {
      Watched<PlayerInfoUpdate> latency =
          watch(PlayerInfoUpdate.WATCH, version, "player_info_update_latency");

      assertNull(latency.packet());
      assertEquals(1, latency.read());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("playerInfoUpdateVersions")
    @DisplayName("should watch, write and read the removal of players")
    void remove(ProtocolVersion version) {
      PlayerInfoRemove removal = new PlayerInfoRemove(List.of(ALICE, BOB));
      Watched<PlayerInfoRemove> removed =
          watch(PlayerInfoRemove.WATCH, version, "player_info_remove");

      assertEquals(removal, removed.packet());
      assertEquals(removed.length(), removed.read());
      assertArrayEquals(
          bytes(version, "player_info_remove"), encode(PlayerInfoRemove.CODEC, removal, version));
      assertEquals(removal, decode(PlayerInfoRemove.CODEC, version, "player_info_remove"));
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
