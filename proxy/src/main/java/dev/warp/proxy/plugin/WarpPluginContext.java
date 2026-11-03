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
import dev.warp.api.event.Subscription;
import dev.warp.api.lifecycle.ReadinessHold;
import dev.warp.api.logging.LogSinks;
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
import dev.warp.proxy.logging.LogSinkRegistry;
import dev.warp.proxy.logging.PluginLogSinks;
import dev.warp.proxy.management.ReadinessHolds;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.messaging.PluginMessaging;
import dev.warp.proxy.permission.PermissionRegistry;
import dev.warp.proxy.permission.PluginPermissions;
import dev.warp.proxy.pipeline.PipelineRegistry;
import dev.warp.proxy.pipeline.PluginNetworkPipelines;

import java.nio.file.Path;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plugin's view of the proxy. Everything the plugin registers through it is tracked, so that it
 * can be removed when the plugin fails or is disabled, whether or not the plugin cleans up: event
 * subscriptions (drain policies included: they are {@code PlayerDrainEvent} handlers), plugin
 * message channels, commands, permission providers, log sinks and readiness holds.
 *
 * <p>Once the plugin is gone, what it registers late (from a task still running) is undone at once
 * and refused with an {@link IllegalStateException}. Registrations the plugin undid itself are
 * forgotten as new ones come, so that a plugin taking and releasing holds all day does not grow the
 * list.
 */
final class WarpPluginContext implements PluginContext {

  private static final Logger logger = LoggerFactory.getLogger(WarpPluginContext.class);

  private final WarpPluginContainer plugin;
  private final Warp proxy;
  private final Logger pluginLogger;
  private final ReadinessHolds readinessHolds;
  private final Deque<Registration> registrations = new ConcurrentLinkedDeque<>();
  private final PluginEventBus events;
  private final PluginMessaging messaging;
  private final PluginCommandManager commands;
  private final PluginPermissions permissions;
  private final @Nullable PluginNetworkPipelines network;
  private final PluginLogSinks logSinks;

  /** Set once the plugin is gone: nothing more may be registered. */
  private volatile boolean closed;

  WarpPluginContext(
      WarpPluginContainer plugin,
      Warp proxy,
      WarpEventBus bus,
      ChannelRegistry channels,
      WarpCommandManager commandManager,
      PermissionRegistry permissionRegistry,
      ReadinessHolds readinessHolds,
      PipelineRegistry pipelines,
      LogSinkRegistry logSinkRegistry) {
    this.plugin = plugin;
    this.proxy = proxy;
    this.pluginLogger = LoggerFactory.getLogger(plugin.description().id());
    this.readinessHolds = readinessHolds;
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
    this.logSinks =
        new PluginLogSinks(
            logSinkRegistry, plugin.description().id(), plugin.classLoader(), this::track);
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

  @Override
  public LogSinks logSinks() {
    return logSinks;
  }

  @Override
  public ReadinessHold holdReadiness(String reason) {
    refuseIfClosed();
    ReadinessHold hold = readinessHolds.hold(reason);
    track(hold, hold::isHeld);
    return hold;
  }

  /**
   * Tracks a subscription the plugin made, to undo it when the plugin goes away.
   *
   * @param subscription the subscription
   * @throws IllegalStateException if the plugin is gone: the subscription is undone first
   */
  void track(Subscription subscription) {
    track(subscription, subscription::isActive);
  }

  /**
   * Tracks something the plugin registered, to undo it when the plugin goes away.
   *
   * @param registration undoes the registration when closed
   * @param active whether the registration is still in force, so that one the plugin undid itself
   *     can be forgotten
   * @throws IllegalStateException if the plugin is gone: the registration is undone first
   */
  void track(AutoCloseable registration, BooleanSupplier active) {
    registrations.removeIf(tracked -> !tracked.active().getAsBoolean());
    registrations.addLast(new Registration(registration, active));
    if (closed) {
      // closeRegistrations may have run between the check and the add: undo it here either way.
      closeRegistrations();
      throw refusal();
    }
  }

  /**
   * Returns how many registrations are tracked, undone ones not forgotten yet included (tests).
   *
   * @return the count
   */
  int trackedRegistrations() {
    return registrations.size();
  }

  /**
   * Undoes everything the plugin registered, most recent first, and refuses what it registers from
   * now on. Never throws.
   */
  void closeRegistrations() {
    closed = true;
    for (Registration registration = registrations.pollLast();
        registration != null;
        registration = registrations.pollLast()) {
      try {
        registration.closer().close();
      } catch (Exception e) {
        logger.warn("Could not undo a registration of plugin {}", plugin, e);
      }
    }
  }

  private void refuseIfClosed() {
    if (closed) {
      throw refusal();
    }
  }

  private IllegalStateException refusal() {
    return new IllegalStateException(
        "Plugin "
            + plugin.description().id()
            + " is no longer enabled: it cannot register anything (a task of it still running?)");
  }

  /** A registration and how to tell whether it is still in force. */
  private record Registration(AutoCloseable closer, BooleanSupplier active) {}
}
