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
package dev.warp.api.player;

/**
 * Something a player's client may lack, depending on its version, that a player receives as an
 * Adventure {@link net.kyori.adventure.audience.Audience}.
 *
 * <p>An Audience method whose feature the client lacks does nothing for that player, as Adventure
 * has every audience do with what it cannot show: an audience of several players reaches those that
 * can show it, and never fails because one client is old. {@link Player#supports} tells a plugin
 * beforehand, so that it can send something else instead (a chat message in place of a boss bar,
 * say). Chat messages reach every client and have no feature.
 */
public enum AudienceFeature {

  /** The action bar above the hotbar ({@code sendActionBar}), from 1.8. */
  ACTION_BAR,

  /** Titles and subtitles ({@code showTitle} and the other title methods), from 1.8. */
  TITLE,

  /** The text above and below the tab list ({@code sendPlayerListHeaderAndFooter}), from 1.8. */
  PLAYER_LIST_HEADER_AND_FOOTER,

  /**
   * Sounds played at a position ({@code playSound(Sound, double, double, double)}): every version.
   */
  POSITIONED_SOUND,

  /**
   * Sounds that follow the player or another player on the same server ({@code playSound(Sound)}
   * and {@code playSound(Sound, Emitter)}), from 1.19.3: before, the client only takes a sound
   * following an entity by its id in the client's sound registry, which the proxy never learns.
   */
  EMITTED_SOUND,

  /**
   * Stopping sounds ({@code stopSound}), from 1.9.4 (the {@code MC|StopSound} plugin message until
   * 1.12.2, a packet from 1.13).
   */
  STOP_SOUND,

  /** Boss bars ({@code showBossBar}, {@code hideBossBar}), from 1.9, whose client has a packet. */
  BOSS_BAR,

  /**
   * Server resource packs ({@code sendResourcePacks}): every version. A 1.7 client receives the URL
   * alone and reports nothing back (see {@link #RESOURCE_PACK_STATUS}); before 1.20.3 a client
   * holds one server pack at a time (see {@link #MULTIPLE_RESOURCE_PACKS}).
   */
  RESOURCE_PACK,

  /** Clients that report what became of a resource pack, from 1.8. */
  RESOURCE_PACK_STATUS,

  /**
   * Several resource packs at once, each named by its UUID, and their removal ({@code
   * removeResourcePacks}, {@code clearResourcePacks}), from 1.20.3.
   */
  MULTIPLE_RESOURCE_PACKS,

  /** Deleting a signed chat message from the chat ({@code deleteMessage}), from 1.19.1. */
  DELETE_MESSAGE,

  /** Closing the dialog on screen ({@code closeDialog}), from 1.21.6, which added dialogs. */
  CLOSE_DIALOG
}
