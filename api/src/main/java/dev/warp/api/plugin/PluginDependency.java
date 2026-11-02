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

import java.util.Objects;

/**
 * A dependency of a plugin on another, see {@link Dependency}.
 *
 * @param id the identifier of the plugin depended on
 * @param optional whether the depending plugin still loads without it
 */
public record PluginDependency(String id, boolean optional) {

  /**
   * Validates the dependency.
   *
   * @throws IllegalArgumentException if the identifier is not a valid plugin identifier
   */
  public PluginDependency {
    Objects.requireNonNull(id, "id");
    if (!PluginDescription.isValidId(id)) {
      throw new IllegalArgumentException("Invalid dependency id '" + id + "'");
    }
  }
}
