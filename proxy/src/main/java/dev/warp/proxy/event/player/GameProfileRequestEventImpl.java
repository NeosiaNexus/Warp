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
package dev.warp.proxy.event.player;

import dev.warp.api.event.player.GameProfileRequestEvent;
import dev.warp.api.player.GameProfile;
import dev.warp.api.player.InboundConnection;

import java.util.Objects;

/** {@link GameProfileRequestEvent} as the proxy fires it. */
public final class GameProfileRequestEventImpl implements GameProfileRequestEvent {

  private final InboundConnection connection;
  private final boolean onlineMode;
  private final GameProfile originalProfile;
  private volatile GameProfile profile;

  /**
   * Creates the event.
   *
   * @param connection the connection logging in
   * @param onlineMode whether the profile was authenticated with Mojang
   * @param profile the profile as authenticated or derived
   */
  public GameProfileRequestEventImpl(
      InboundConnection connection, boolean onlineMode, GameProfile profile) {
    this.connection = connection;
    this.onlineMode = onlineMode;
    this.originalProfile = profile;
    this.profile = profile;
  }

  @Override
  public InboundConnection connection() {
    return connection;
  }

  @Override
  public boolean isOnlineMode() {
    return onlineMode;
  }

  @Override
  public GameProfile originalProfile() {
    return originalProfile;
  }

  @Override
  public GameProfile profile() {
    return profile;
  }

  @Override
  public void setProfile(GameProfile profile) {
    this.profile = Objects.requireNonNull(profile, "profile");
  }
}
