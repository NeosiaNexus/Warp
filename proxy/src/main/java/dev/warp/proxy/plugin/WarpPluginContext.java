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

import dev.warp.api.Warp;
import dev.warp.api.event.EventBus;
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginContext;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plugin's view of the proxy. Everything the plugin registers through it is tracked, so that it
 * can be removed when the plugin fails or is disabled, whether or not the plugin cleans up.
 */
final class WarpPluginContext implements PluginContext {

  private static final Logger logger = LoggerFactory.getLogger(WarpPluginContext.class);

  private final WarpPluginContainer plugin;
  private final Warp proxy;
  private final Logger pluginLogger;
  private final List<AutoCloseable> registrations = new CopyOnWriteArrayList<>();
  private final PluginEventBus events;

  WarpPluginContext(WarpPluginContainer plugin, Warp proxy, WarpEventBus bus) {
    this.plugin = plugin;
    this.proxy = proxy;
    this.pluginLogger = LoggerFactory.getLogger(plugin.description().id());
    this.events =
        new PluginEventBus(bus, plugin.description().id(), plugin.classLoader(), this::track);
  }

  @Override
  public PluginContainer plugin() {
    return plugin;
  }

  @Override
  public Warp proxy() {
    return proxy;
  }

  @Override
  public Logger logger() {
    return pluginLogger;
  }

  @Override
  public Path dataDirectory() {
    return plugin.dataDirectory();
  }

  @Override
  public EventBus events() {
    return events;
  }

  /**
   * Tracks something the plugin registered, to undo it when the plugin goes away.
   *
   * @param registration undoes the registration when closed
   */
  void track(AutoCloseable registration) {
    registrations.add(registration);
  }

  /** Undoes everything the plugin registered, most recent first. Never throws. */
  void closeRegistrations() {
    List<AutoCloseable> reversed = new ArrayList<>(registrations);
    registrations.clear();
    for (int i = reversed.size() - 1; i >= 0; i--) {
      try {
        reversed.get(i).close();
      } catch (Exception e) {
        logger.warn("Could not undo a registration of plugin {}", plugin, e);
      }
    }
  }
}
