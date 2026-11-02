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

import dev.warp.api.Warp;

import java.nio.file.Path;

import org.slf4j.Logger;

/**
 * Everything the proxy gives one plugin, bound to that plugin.
 *
 * <p>The constructor of a plugin's main class receives its parameters by type. These types can be
 * injected:
 *
 * <ul>
 *   <li>{@link PluginContext}: this object;
 *   <li>{@link Warp}: the proxy;
 *   <li>{@link Logger}: a logger named after the plugin's identifier;
 *   <li>{@link PluginContainer} and {@link PluginDescription}: the plugin itself;
 *   <li>{@link PluginManager}: every plugin;
 *   <li>{@link Path} annotated with {@link DataDirectory}: the plugin's data directory.
 * </ul>
 *
 * <p>A constructor asking for anything else fails the plugin with a message naming the parameter
 * and the types available.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface PluginContext {

  /**
   * Returns the plugin this context belongs to.
   *
   * @return the plugin
   */
  PluginContainer plugin();

  /**
   * Returns the proxy.
   *
   * @return the proxy
   */
  Warp proxy();

  /**
   * Returns the plugin's logger, named after its identifier.
   *
   * @return the logger
   */
  Logger logger();

  /**
   * Returns the plugin's data directory, created on first access.
   *
   * @return the data directory
   */
  Path dataDirectory();
}
