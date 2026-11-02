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
import dev.warp.api.command.CommandManager;
import dev.warp.api.event.EventBus;
import dev.warp.api.messaging.Messaging;
import dev.warp.api.network.NetworkPipelines;
import dev.warp.api.permission.Permissions;
import dev.warp.api.plugin.Capability;
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginContext;
import dev.warp.proxy.command.PluginCommandManager;
import dev.warp.proxy.command.WarpCommandManager;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.messaging.PluginMessaging;
import dev.warp.proxy.permission.PermissionRegistry;
import dev.warp.proxy.permission.PluginPermissions;
import dev.warp.proxy.pipeline.PipelineRegistry;
import dev.warp.proxy.pipeline.PluginNetworkPipelines;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;
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
  private final PluginMessaging messaging;
  private final PluginCommandManager commands;
  private final PluginPermissions permissions;
  private final @Nullable PluginNetworkPipelines network;

  WarpPluginContext(
      WarpPluginContainer plugin,
      Warp proxy,
      WarpEventBus bus,
      ChannelRegistry channels,
      WarpCommandManager commandManager,
      PermissionRegistry permissionRegistry,
      PipelineRegistry pipelines) {
    this.plugin = plugin;
    this.proxy = proxy;
    this.pluginLogger = LoggerFactory.getLogger(plugin.description().id());
    this.events =
        new PluginEventBus(bus, plugin.description().id(), plugin.classLoader(), this::track);
    this.messaging = new PluginMessaging(channels, plugin.description().id(), this::track);
    this.commands =
        new PluginCommandManager(commandManager, plugin.description().id(), this::track);
    this.permissions =
        new PluginPermissions(permissionRegistry, plugin.description().id(), this::track);
    this.network =
        plugin.description().hasCapability(Capability.NETWORK_PIPELINE)
            ? new PluginNetworkPipelines(pipelines, plugin.description().id(), this::track)
            : null;
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

  @Override
  public Messaging messaging() {
    return messaging;
  }

  @Override
  public CommandManager commands() {
    return commands;
  }

  @Override
  public Permissions permissions() {
    return permissions;
  }

  @Override
  public NetworkPipelines network() {
    PluginNetworkPipelines pipelines = network;
    if (pipelines == null) {
      throw new IllegalStateException(
          "Plugin "
              + plugin.description().id()
              + " did not declare the network pipeline capability: add capabilities ="
              + " Capability.NETWORK_PIPELINE to its @Plugin annotation");
    }
    return pipelines;
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
