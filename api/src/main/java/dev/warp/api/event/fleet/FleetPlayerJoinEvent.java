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

/**
 * Fired when a player logs in to another proxy of the fleet. A player logging in to this proxy
 * fires {@link dev.warp.api.event.player.PostLoginEvent} instead.
 *
 * @param player the player
 */
public record FleetPlayerJoinEvent(FleetPlayer player) {}
