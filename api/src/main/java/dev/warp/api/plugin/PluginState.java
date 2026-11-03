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
package dev.warp.api.plugin;

/** Where a plugin stands in its lifecycle. */
public enum PluginState {

  /** Found and being constructed or enabled. */
  LOADING,

  /** Constructed and enabled: its listeners, commands and tasks are live. */
  ENABLED,

  /**
   * Failed to construct or enable, or a required dependency did. Everything it had registered was
   * removed; see {@link PluginContainer#failure()}.
   */
  FAILED,

  /** Disabled during shutdown. Its listeners, commands and tasks are gone. */
  DISABLED
}
