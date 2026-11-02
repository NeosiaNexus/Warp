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
package dev.warp.proxy.plugin;

import dev.warp.api.plugin.PluginDescription;

import java.nio.file.Path;

/**
 * A plugin jar whose descriptor was read and is valid, not loaded yet.
 *
 * @param jar the jar file
 * @param description what the descriptor declares
 */
record PluginCandidate(Path jar, PluginDescription description) {

  /**
   * Returns the plugin's identifier.
   *
   * @return the identifier
   */
  String id() {
    return description.id();
  }
}
