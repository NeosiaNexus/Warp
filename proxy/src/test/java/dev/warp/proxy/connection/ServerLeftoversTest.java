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
package dev.warp.proxy.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.protocol.packet.play.PlayerInfo;
import dev.warp.protocol.packet.play.PlayerInfoRemove;
import dev.warp.protocol.packet.play.PlayerInfoUpdate;
import dev.warp.protocol.packet.play.TabListHeaderFooter;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Server leftovers")
class ServerLeftoversTest {

  private static final UUID ALICE = UUID.randomUUID();
  private static final UUID BOB = UUID.randomUUID();
  private static final UUID BAR = UUID.randomUUID();

  private final ServerLeftovers leftovers = new ServerLeftovers();

  @Nested
  @DisplayName("tab list")
  class TabList {

    @Test
    @DisplayName("should remove the players added and not removed since, before 1.19.3")
    void playerInfo() {
      leftovers.track(new PlayerInfo(PlayerInfo.ADD_PLAYER, List.of(ALICE, BOB)));
      leftovers.track(new PlayerInfo(PlayerInfo.UPDATE_LATENCY, List.of(ALICE)));
      leftovers.track(new PlayerInfo(PlayerInfo.REMOVE_PLAYER, List.of(BOB)));

      List<PlayPacket> packets = leftovers.clear(ProtocolVersion.MINECRAFT_1_12_2);

      PlayerInfo removal = assertInstanceOf(PlayerInfo.class, packets.getFirst());
      assertEquals(PlayerInfo.REMOVE_PLAYER, removal.action());
      assertEquals(List.of(ALICE), removal.profileIds());
    }

    @Test
    @DisplayName("should remove the players added and not removed since, from 1.19.3")
    void playerInfoUpdate() {
      leftovers.track(new PlayerInfoUpdate(PlayerInfoUpdate.ADD_PLAYER, List.of(ALICE, BOB)));
      leftovers.track(
          new PlayerInfoUpdate(PlayerInfoUpdate.UPDATE_LATENCY, List.of(UUID.randomUUID())));
      leftovers.track(new PlayerInfoRemove(List.of(ALICE)));

      List<PlayPacket> packets = leftovers.clear(ProtocolVersion.MINECRAFT_1_19_4);

      PlayerInfoRemove removal = assertInstanceOf(PlayerInfoRemove.class, packets.getFirst());
      assertEquals(List.of(BOB), removal.profileIds());
    }
  }

  @Nested
  @DisplayName("boss bars")
  class BossBars {

    @Test
    @DisplayName("should remove the boss bars still shown")
    void removed() {
      UUID gone = UUID.randomUUID();
      leftovers.track(new BossBar(BAR, BossBar.ADD));
      leftovers.track(new BossBar(gone, BossBar.ADD));
      leftovers.track(new BossBar(gone, BossBar.REMOVE));
      leftovers.track(new BossBar(BAR, 2));

      List<PlayPacket> packets = leftovers.clear(ProtocolVersion.MINECRAFT_1_16_4);

      BossBar removal = assertInstanceOf(BossBar.class, packets.getFirst());
      assertEquals(BAR, removal.uuid());
      assertEquals(BossBar.REMOVE, removal.action());
    }
  }

  @Nested
  @DisplayName("clear")
  class Clear {

    @Test
    @DisplayName("should always clear the tab list header, footer and title from 1.8")
    void alwaysReset() {
      leftovers.track(new KeepAlive(1));

      List<PlayPacket> packets = leftovers.clear(ProtocolVersion.MINECRAFT_1_8);

      assertEquals(3, packets.size());
      assertInstanceOf(TabListHeaderFooter.class, packets.get(0));
      assertEquals(new ClearTitles(false), packets.get(1));
      assertEquals(new ClearTitles(true), packets.get(2));
    }

    @Test
    @DisplayName("should hide the title before resetting it up to 1.16.5: a reset alone shows it")
    void titleHiddenThenReset() {
      List<PlayPacket> packets = leftovers.clear(ProtocolVersion.MINECRAFT_1_16_4);

      assertEquals(List.of(new ClearTitles(false), new ClearTitles(true)), packets.subList(1, 3));
    }

    @Test
    @DisplayName("should clear the title with one Clear Titles packet from 1.17")
    void titleClearedFrom117() {
      List<PlayPacket> packets = leftovers.clear(ProtocolVersion.MINECRAFT_1_17);

      assertEquals(2, packets.size());
      assertInstanceOf(TabListHeaderFooter.class, packets.get(0));
      assertEquals(new ClearTitles(true), packets.get(1));
    }

    @Test
    @DisplayName("should send nothing to 1.7 clients, which have no header, footer or title")
    void nothingBefore18() {
      assertEquals(List.of(), leftovers.clear(ProtocolVersion.MINECRAFT_1_7_6));
    }

    @Test
    @DisplayName("should forget what it cleared: the next server starts clean")
    void forgets() {
      leftovers.track(new PlayerInfo(PlayerInfo.ADD_PLAYER, List.of(ALICE)));
      leftovers.track(new BossBar(BAR, BossBar.ADD));
      leftovers.clear(ProtocolVersion.MINECRAFT_1_12_2);

      List<PlayPacket> packets = leftovers.clear(ProtocolVersion.MINECRAFT_1_12_2);

      assertEquals(
          new HashSet<>(List.of(TabListHeaderFooter.class, ClearTitles.class)),
          new HashSet<>(packets.stream().map(Object::getClass).toList()));
    }
  }
}
