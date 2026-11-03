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
import dev.warp.protocol.packet.play.SoundEffect;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;

/**
 * The wire ids of Adventure's enums, spelled out rather than read from their order: a constant
 * Adventure adds fails the build here instead of reaching a client as an id it does not know.
 */
final class AdventureIds {

  private AdventureIds() {}

  /**
   * Returns the wire id of a sound source for a client (see {@link SoundEffect#source}: ui, from
   * 1.21.6, is master before).
   *
   * @param source the source
   * @param version the client's protocol version
   * @return the id
   */
  static int source(Sound.Source source, ProtocolVersion version) {
    int id =
        switch (source) {
          case MASTER -> 0;
          case MUSIC -> 1;
          case RECORD -> 2;
          case WEATHER -> 3;
          case BLOCK -> 4;
          case HOSTILE -> 5;
          case NEUTRAL -> 6;
          case PLAYER -> 7;
          case AMBIENT -> 8;
          case VOICE -> 9;
          case UI -> 10;
        };
    return SoundEffect.source(id, version);
  }

  /**
   * Returns the wire id of a boss bar color.
   *
   * @param color the color
   * @return the id
   */
  static int color(BossBar.Color color) {
    return switch (color) {
      case PINK -> 0;
      case BLUE -> 1;
      case RED -> 2;
      case GREEN -> 3;
      case YELLOW -> 4;
      case PURPLE -> 5;
      case WHITE -> 6;
    };
  }

  /**
   * Returns the wire id of a boss bar overlay.
   *
   * @param overlay the overlay
   * @return the id
   */
  static int overlay(BossBar.Overlay overlay) {
    return switch (overlay) {
      case PROGRESS -> 0;
      case NOTCHED_6 -> 1;
      case NOTCHED_10 -> 2;
      case NOTCHED_12 -> 3;
      case NOTCHED_20 -> 4;
    };
  }
}
