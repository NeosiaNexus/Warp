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
package dev.warp.api.fleet.spi;

/** The outcome of {@link FleetStore#claim}. */
public sealed interface ClaimResult {

  /** The player was unowned, or owned by a node that is gone: the caller owns it now. */
  record Claimed() implements ClaimResult {}

  /**
   * A live node owns the player: the caller applies the duplicate-login policy.
   *
   * @param nodeId the owner
   * @param incarnation the owner's incarnation when it claimed the player
   */
  record Owned(String nodeId, long incarnation) implements ClaimResult {}
}
