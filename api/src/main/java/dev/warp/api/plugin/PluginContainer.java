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

import java.nio.file.Path;
import java.util.Optional;

/**
 * A plugin known to the proxy: its description, state and instance.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface PluginContainer {

  /**
   * Returns what the plugin declares about itself.
   *
   * @return the description
   */
  PluginDescription description();

  /**
   * Returns the plugin's lifecycle state.
   *
   * @return the state
   */
  PluginState state();

  /**
   * Returns the plugin's main class instance, once constructed.
   *
   * @return the instance, or empty if it was not constructed (still loading, or failed before)
   */
  Optional<Object> instance();

  /**
   * Returns why the plugin failed.
   *
   * @return the failure when {@link #state()} is {@link PluginState#FAILED}, otherwise empty
   */
  Optional<Throwable> failure();

  /**
   * Returns the directory where the plugin keeps its files: {@code plugins/<id>} next to the proxy.
   * Created on first access.
   *
   * @return the data directory
   */
  Path dataDirectory();
}
