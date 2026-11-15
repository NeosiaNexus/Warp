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

/** Where a proxy of the fleet stands, as its last heartbeat said. */
public enum NodeState {
  /** It accepts players. */
  READY,
  /** It is draining: it moves its players away and should receive no new ones. */
  DRAINING,
  /** Its heartbeat expired, or it said goodbye: it is being reaped and leaves the fleet. */
  DOWN
}
