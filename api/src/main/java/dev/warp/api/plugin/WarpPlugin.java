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

/**
 * Lifecycle callbacks a plugin's main class may implement.
 *
 * <p>The proxy loads plugins one at a time, dependencies first: it constructs a plugin, calls its
 * {@link #enable()}, and only then moves to the next one, so a plugin always finds the plugins it
 * depends on enabled. Once every plugin is loaded, the proxy starts accepting players. On shutdown
 * it stops accepting players, disconnects the remaining ones, then calls {@link #disable()} in
 * reverse order.
 *
 * <p>Registering listeners, commands and tasks works in the constructor as well as in {@link
 * #enable()}: everything registered through the plugin's {@link PluginContext} is tied to the
 * plugin, and removed automatically if the plugin fails or once it is disabled.
 */
public interface WarpPlugin {

  /**
   * Called right after the plugin is constructed, before the proxy accepts players. The plugins
   * this one depends on are already enabled.
   *
   * @throws Exception to fail the plugin: what it registered is removed, and plugins requiring it
   *     do not load
   */
  default void enable() throws Exception {}

  /**
   * Called on shutdown, after the players were disconnected, before the plugins this one depends on
   * are disabled. Close connections and flush data here.
   *
   * @throws Exception logged; shutdown continues
   */
  default void disable() throws Exception {}
}
