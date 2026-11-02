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
package dev.warp.api.event.player;

import dev.warp.api.player.GameProfile;
import dev.warp.api.player.InboundConnection;

/**
 * Fired once the player's profile is known (from Mojang in online mode, derived from the name in
 * offline mode), before anything else sees it. Replace the profile here to give an offline player a
 * skin, or to map players to other UUIDs; the profile set here is the one the player logs in with
 * and that backends receive.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface GameProfileRequestEvent {

  /**
   * Returns the connection logging in.
   *
   * @return the connection
   */
  InboundConnection connection();

  /**
   * Returns whether the profile was authenticated with Mojang.
   *
   * @return {@code true} in online mode
   */
  boolean isOnlineMode();

  /**
   * Returns the profile before any handler changed it.
   *
   * @return the original profile
   */
  GameProfile originalProfile();

  /**
   * Returns the profile the player will log in with.
   *
   * @return the current profile
   */
  GameProfile profile();

  /**
   * Replaces the profile the player will log in with.
   *
   * @param profile the new profile
   */
  void setProfile(GameProfile profile);
}
