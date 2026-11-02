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
import dev.warp.api.command.CommandManager;
import dev.warp.api.event.EventBus;
import dev.warp.api.lifecycle.ReadinessHold;
import dev.warp.api.messaging.Messaging;
import dev.warp.api.permission.Permissions;

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
 *   <li>{@link EventBus}: the plugin's event bus;
 *   <li>{@link Messaging}: the plugin's plugin message channels;
 *   <li>{@link CommandManager}: the plugin's commands;
 *   <li>{@link Permissions}: the plugin's permission providers;
 *   <li>{@link Path} annotated with {@link DataDirectory}: the plugin's data directory.
 * </ul>
 *
 * <p>A constructor asking for anything else fails the plugin with a message naming the parameter
 * and the types available.
 *
 * <h2>What the plugin owns</h2>
 *
 * <p>Everything a plugin registers through its context belongs to it and is released when the
 * plugin is disabled or fails to load, whether or not the plugin cleans up: event subscriptions
 * (drain policies too: they are {@link dev.warp.api.event.player.PlayerDrainEvent} handlers),
 * plugin message channels, commands, permission providers and readiness holds. What the plugin
 * registers once it is gone, from a task still running, is undone at once and refused with an
 * {@link IllegalStateException}.
 *
 * <p>Backend servers are not owned: a server registered through {@link Warp#servers()} stays
 * registered when its plugin goes, as players may be playing on it and another plugin may have
 * registered it too. Unregister them in {@link WarpPlugin#disable()} if they must go.
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

  /**
   * Returns the plugin's event bus. Everything registered through it belongs to the plugin and is
   * removed when the plugin is disabled or fails.
   *
   * @return the event bus
   */
  EventBus events();

  /**
   * Returns the plugin's plugin message channels. Channels registered through it are released when
   * the plugin is disabled or fails.
   *
   * @return the messaging registrar
   */
  Messaging messaging();

  /**
   * Returns the plugin's commands. Commands registered through it are removed when the plugin is
   * disabled or fails.
   *
   * @return the command manager
   */
  CommandManager commands();

  /**
   * Returns the plugin's permission providers. Providers registered through it are removed when the
   * plugin is disabled or fails.
   *
   * @return the permission registrar
   */
  Permissions permissions();

  /**
   * Holds the proxy's readiness until the hold is released, or until the plugin is disabled or
   * fails: the readiness probe and the HAProxy agent check report the proxy as not ready, so that
   * no new player is sent here. Take one while the proxy cannot serve players as the plugin sees
   * it, such as until it discovered a lobby. A plugin that fails after taking one no longer leaves
   * the proxy unready until it restarts.
   *
   * @param reason why, as the readiness probe reports it
   * @return the hold, to release once the plugin is ready
   * @throws IllegalArgumentException if {@code reason} is blank
   * @throws IllegalStateException if the plugin is no longer enabled
   */
  ReadinessHold holdReadiness(String reason);
}
