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
package dev.warp.api.fleet;

/** How the fleet reaches its store. */
public enum StoreState {
  /**
   * No store is configured: the fleet is this proxy alone. Every call answers from local state and
   * nothing is ever degraded.
   */
  SINGLE_NODE,
  /** The store answers: the other proxies' players and nodes are fresh. */
  CONNECTED,
  /**
   * The store is unreachable: the view of the other proxies is frozen as last seen, presence writes
   * queue up for the reconnection, and logins follow {@code fleet.on-store-down}.
   */
  DEGRADED
}
