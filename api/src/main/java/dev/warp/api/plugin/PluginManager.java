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

import java.util.List;
import java.util.Optional;

/**
 * The plugins the proxy found, in the order it loaded them.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface PluginManager {

  /**
   * Returns every plugin found, enabled or not, dependencies before the plugins that need them.
   *
   * @return the plugins, unmodifiable
   */
  List<PluginContainer> plugins();

  /**
   * Returns a plugin by identifier.
   *
   * @param id the identifier
   * @return the plugin, or empty if no plugin has that identifier
   */
  Optional<PluginContainer> plugin(String id);

  /**
   * Returns whether a plugin is enabled. Check this before touching the classes of an optional
   * dependency.
   *
   * @param id the identifier
   * @return {@code true} if the plugin exists and is {@link PluginState#ENABLED}
   */
  boolean isEnabled(String id);

  /**
   * Returns the plugin a main class instance belongs to.
   *
   * @param instance a plugin's main class instance
   * @return its plugin, or empty if it is not a plugin instance
   */
  Optional<PluginContainer> fromInstance(Object instance);
}
