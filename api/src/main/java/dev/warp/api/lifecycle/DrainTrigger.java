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
package dev.warp.api.lifecycle;

/** What started a drain. */
public enum DrainTrigger {

  /** The process received SIGTERM, and {@code drain.on-sigterm} is on. */
  SIGNAL,

  /** {@code POST /drain} on the management server. */
  HTTP,

  /** A plugin called {@link dev.warp.api.Warp#drain()}. */
  PLUGIN,

  /**
   * The {@code /shutdown} command, typed at the console or by a player granted {@code
   * warp.command.shutdown}.
   */
  COMMAND
}
