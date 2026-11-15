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
package dev.warp.api.event.fleet;

import dev.warp.api.fleet.FleetPlayer;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Fired when a player on another proxy of the fleet moves to a backend. A player of this proxy
 * fires {@link dev.warp.api.event.player.ServerConnectedEvent} instead.
 *
 * @param player the player, {@link FleetPlayer#server()} its new backend
 * @param previousServer the backend it left, or {@code null} when it had none yet
 */
public record FleetPlayerServerChangeEvent(FleetPlayer player, @Nullable String previousServer) {

  /**
   * Returns the backend the player left.
   *
   * @return its name, or empty when the player was on none
   */
  public Optional<String> previousServerIfAny() {
    return Optional.ofNullable(previousServer);
  }
}
