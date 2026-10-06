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

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.BossBar;
import dev.warp.protocol.packet.play.ClearTitles;
import dev.warp.protocol.packet.play.LegacyPlayerInfo;
import dev.warp.protocol.packet.play.PlayPacket;
import dev.warp.protocol.packet.play.PlayerInfo;
import dev.warp.protocol.packet.play.PlayerInfoRemove;
import dev.warp.protocol.packet.play.PlayerInfoUpdate;
import dev.warp.protocol.packet.play.TabListHeaderFooter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a server leaves on a client before 1.20.2 that the Join Game of the next server does not
 * clear, and the packets that clear it when the player switches servers.
 *
 * <p>A Join Game gives the client a new world, and with it a new scoreboard, entities and chunks.
 * The tab list, its header and footer, the boss bars and the title on screen belong to the client's
 * interface instead and stay: the tab list would keep listing the previous server's players, its
 * boss bars would stay on screen forever. Velocity clears the same things. The header, footer and
 * title are always reset; the tab list entries and boss bars are removed one by one, so their UUIDs
 * are followed here as the server adds and removes them; a 1.7 client keys its tab list by name
 * instead, so its names are followed (Velocity's {@code VelocityTabListLegacy}). A loaded resource
 * pack stays as well, but no packet before 1.20.3 can unload it.
 *
 * <p>Only fed before 1.20.2, where the packets it follows are watched: the decoder reports their
 * additions and removals (every 1.7 tab list packet, as one cannot tell a new name from a latency
 * update), read in place, and forwards the packets themselves untouched. Accessed from the player's
 * event loop only.
 */
final class ServerLeftovers {

  /** Tab list entries the current server added. */
  private final Set<UUID> tabListEntries = new HashSet<>();

  /** Names the current server listed on a 1.7 client, whose tab list is keyed by name. */
  private final Set<String> legacyTabListNames = new HashSet<>();

  /** Boss bars the current server shows. */
  private final Set<UUID> bossBars = new HashSet<>();

  /**
   * Follows a packet the current server sends the client.
   *
   * @param packet what the decoder read of a packet forwarded to the client
   */
  void track(PlayPacket packet) {
    switch (packet) {
      case LegacyPlayerInfo info -> {
        // Listing a name already listed only updates its latency: the set keeps it once.
        if (info.online()) {
          legacyTabListNames.add(info.name());
        } else {
          legacyTabListNames.remove(info.name());
        }
      }
      case PlayerInfo info -> {
        if (info.action() == PlayerInfo.ADD_PLAYER) {
          tabListEntries.addAll(info.profileIds());
        } else if (info.action() == PlayerInfo.REMOVE_PLAYER) {
          info.profileIds().forEach(tabListEntries::remove);
        }
      }
      case PlayerInfoUpdate update -> {
        if ((update.actions() & PlayerInfoUpdate.ADD_PLAYER) != 0) {
          tabListEntries.addAll(update.profileIds());
        }
      }
      case PlayerInfoRemove remove -> remove.profileIds().forEach(tabListEntries::remove);
      case BossBar bossBar -> {
        if (bossBar.action() == BossBar.ADD) {
          bossBars.add(bossBar.uuid());
        } else if (bossBar.action() == BossBar.REMOVE) {
          bossBars.remove(bossBar.uuid());
        }
      }
      default -> {
        // Nothing else outlives a Join Game.
      }
    }
  }

  /**
   * Returns the packets that clear what the current server left, and forgets it: the next server
   * starts from a clean client.
   *
   * @param version the client's protocol version, before 1.20.2
   * @return the packets to send before the next server's Join Game
   */
  List<PlayPacket> clear(ProtocolVersion version) {
    List<PlayPacket> packets = new ArrayList<>(legacyTabListNames.size() + bossBars.size() + 4);
    for (String name : legacyTabListNames) {
      packets.add(LegacyPlayerInfo.remove(name)); // one entry per packet on 1.7
    }
    legacyTabListNames.clear();
    if (!tabListEntries.isEmpty()) {
      List<UUID> entries = List.copyOf(tabListEntries);
      packets.add(
          version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_3)
              ? new PlayerInfoRemove(entries)
              : PlayerInfo.remove(entries));
      tabListEntries.clear();
    }
    for (UUID bossBar : bossBars) {
      packets.add(BossBar.remove(bossBar));
    }
    bossBars.clear();
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_8)) {
      packets.add(TabListHeaderFooter.empty(version));
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_17)) {
        // The Title packet's reset alone blanks the title but shows the last subtitle again for a
        // whole title duration (1.8.9 GuiIngame#displayTitle): hide first, then reset the times.
        packets.add(new ClearTitles(false));
      }
      packets.add(new ClearTitles(true));
    }
    return packets;
  }
}
