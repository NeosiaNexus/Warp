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
package dev.warp.api;

import dev.warp.api.plugin.PluginManager;

/**
 * Entry point to the Warp API: the proxy itself.
 *
 * <p>Plugins receive it in their constructor (see {@link dev.warp.api.plugin.PluginContext}); code
 * that cannot be injected can use {@link WarpProvider#get()}.
 *
 * <p>Implemented by the proxy; plugins must not implement it. Methods are added as the API grows.
 */
public interface Warp {

  /**
   * Returns the proxy version string.
   *
   * @return the version string (e.g. {@code "0.1.0-beta.2"})
   */
  String version();

  /**
   * Returns the plugins the proxy found.
   *
   * @return the plugin manager
   */
  PluginManager plugins();
}
