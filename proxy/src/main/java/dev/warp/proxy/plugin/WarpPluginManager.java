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
import dev.warp.api.logging.LogSinks;
import dev.warp.api.messaging.Messaging;
import dev.warp.api.network.NetworkPipelines;
import dev.warp.api.permission.Permissions;
import dev.warp.api.plugin.Capability;
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginContext;
import dev.warp.api.plugin.PluginDependency;
import dev.warp.api.plugin.PluginDescription;
import dev.warp.api.plugin.PluginManager;
import dev.warp.api.plugin.PluginState;
import dev.warp.api.plugin.WarpPlugin;
import dev.warp.proxy.command.WarpCommandManager;
import dev.warp.proxy.console.SourceTags;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.logging.LogCode;
import dev.warp.proxy.logging.LogSinkRegistry;
import dev.warp.proxy.logging.Plural;
import dev.warp.proxy.management.ReadinessHolds;
import dev.warp.proxy.messaging.ChannelRegistry;
import dev.warp.proxy.metrics.MetricRegistry;
import dev.warp.proxy.permission.PermissionRegistry;
import dev.warp.proxy.pipeline.PipelineRegistry;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the plugins of the proxy's {@code plugins} directory and runs their lifecycle.
 *
 * <p>Plugins load one at a time, dependencies first: each is constructed, then enabled, before the
 * next starts, so a plugin always finds the plugins it depends on enabled. A plugin that fails is
 * reported with what to do about it; what it registered is removed, and the plugins requiring it do
 * not load, but the others do. On shutdown, enabled plugins are disabled in reverse order.
 */
public final class WarpPluginManager implements PluginManager {

  private static final Logger logger = LoggerFactory.getLogger(WarpPluginManager.class);

  private final Warp proxy;
  private final WarpEventBus events;
  private final ChannelRegistry channels;
  private final WarpCommandManager commands;
  private final PermissionRegistry permissions;
  private final ReadinessHolds readinessHolds;
  private final PipelineRegistry pipelines;
  private final LogSinkRegistry logSinks;
  private final MetricRegistry metrics;
  private final Path directory;
  private final ApiClassLoader apiLoader;

  /** The parent of plugins with the network pipeline capability: the API and the proxy's Netty. */
  private final ApiClassLoader networkApiLoader;

  /** Every plugin found, in load order (plugins that cannot load at the end). */
  private volatile List<WarpPluginContainer> plugins = List.of();

  /** How long each plugin took to load and enable, in nanoseconds, in load order. */
  private volatile Map<String, Long> loadNanos = Map.of();

  /**
   * Creates a manager for a plugins directory.
   *
   * @param proxy the proxy given to plugins
   * @param events the proxy's event bus, of which each plugin gets its own view
   * @param channels the proxy's plugin message channels
   * @param commands the proxy's commands
   * @param permissions the proxy's permission providers
   * @param readinessHolds the holds plugins keep on the proxy's readiness
   * @param pipelines the proxy's pipeline initializers
   * @param logSinks the proxy's log sinks
   * @param metrics the metric registry, scoped to each plugin
   * @param directory the plugins directory, created if missing
   */
  public WarpPluginManager(
      Warp proxy,
      WarpEventBus events,
      ChannelRegistry channels,
      WarpCommandManager commands,
      PermissionRegistry permissions,
      ReadinessHolds readinessHolds,
      PipelineRegistry pipelines,
      LogSinkRegistry logSinks,
      MetricRegistry metrics,
      Path directory) {
    this.proxy = proxy;
    this.events = events;
    this.channels = channels;
    this.commands = commands;
    this.permissions = permissions;
    this.readinessHolds = readinessHolds;
    this.pipelines = pipelines;
    this.logSinks = logSinks;
    this.metrics = metrics;
    this.directory = directory;
    this.apiLoader = new ApiClassLoader(WarpPluginManager.class.getClassLoader());
    this.networkApiLoader = new ApiClassLoader(WarpPluginManager.class.getClassLoader(), true);
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Returns how long each plugin took to load and enable, for the startup's breakdown.
   *
   * @return nanoseconds by plugin id, in load order
   */
  public Map<String, Long> loadNanos() {
    return loadNanos;
  }

  /** Finds, constructs and enables every plugin. Called once, before the proxy accepts players. */
  public void loadAll() {
    List<PluginCandidate> candidates = PluginDiscovery.discover(directory);
    PluginOrder order = PluginOrder.of(candidates);

    List<WarpPluginContainer> containers = new ArrayList<>(candidates.size());
    Map<String, WarpPluginContainer> byId = new HashMap<>();
    for (PluginCandidate candidate : order.order()) {
      WarpPluginContainer container = container(candidate);
      containers.add(container);
      byId.put(candidate.id(), container);
    }
    List<WarpPluginContainer> loadOrder = List.copyOf(containers);
    for (PluginCandidate candidate : candidates) {
      String reason = order.failures().get(candidate.id());
      if (reason != null) {
        WarpPluginContainer container = container(candidate);
        container.failed(new PluginLoadException(reason));
        logger.error(
            LogCode.PLUGIN_CANNOT_LOAD.marker(), "Plugin {} cannot load: {}", container, reason);
        containers.add(container);
      }
    }
    this.plugins = List.copyOf(containers);

    Map<String, Long> times = new LinkedHashMap<>();
    for (WarpPluginContainer container : loadOrder) {
      long start = System.nanoTime();
      load(container, byId);
      times.put(container.description().id(), System.nanoTime() - start);
    }
    this.loadNanos = Collections.unmodifiableMap(times);

    long enabled = containers.stream().filter(c -> c.state() == PluginState.ENABLED).count();
    if (!containers.isEmpty()) {
      logger.info(
          "{} of {} " + Plural.of(containers.size(), "plugin", "plugins") + " enabled",
          enabled,
          containers.size());
    }
  }

  /**
   * Disables the enabled plugins, dependents first, and closes their class loaders. Never throws.
   */
  public void disableAll() {
    List<WarpPluginContainer> loaded = plugins;
    for (int i = loaded.size() - 1; i >= 0; i--) {
      WarpPluginContainer container = loaded.get(i);
      if (container.state() != PluginState.ENABLED) {
        continue;
      }
      PluginClassLoader loader = container.classLoader();
      Object instance = container.instance().orElse(null);
      if (instance instanceof WarpPlugin plugin) {
        try {
          withContextLoader(loader, plugin::disable);
        } catch (Throwable t) {
          logger.error(
              LogCode.PLUGIN_DISABLE_FAILED.marker(),
              "Plugin {} failed to disable cleanly",
              container,
              t);
        }
      }
      WarpPluginContext context = container.context();
      if (context != null) {
        context.closeRegistrations();
      }
      container.disabled();
      close(container);
      logger.info("Disabled plugin {}", container);
    }
  }

  // ---------------------------------------------------------------------------
  // PluginManager
  // ---------------------------------------------------------------------------

  @Override
  public List<PluginContainer> plugins() {
    return List.copyOf(plugins);
  }

  @Override
  public Optional<PluginContainer> plugin(String id) {
    for (WarpPluginContainer container : plugins) {
      if (container.description().id().equals(id)) {
        return Optional.of(container);
      }
    }
    return Optional.empty();
  }

  @Override
  public boolean isEnabled(String id) {
    return plugin(id).map(container -> container.state() == PluginState.ENABLED).orElse(false);
  }

  @Override
  @SuppressWarnings("ReferenceEquality") // A plugin is its instance: identity, not equality.
  public Optional<PluginContainer> fromInstance(Object instance) {
    for (WarpPluginContainer container : plugins) {
      if (container.instance().orElse(null) == instance) {
        return Optional.of(container);
      }
    }
    return Optional.empty();
  }

  // ---------------------------------------------------------------------------
  // Loading one plugin
  // ---------------------------------------------------------------------------

  private WarpPluginContainer container(PluginCandidate candidate) {
    return new WarpPluginContainer(
        candidate.description(), candidate.jar(), directory.resolve(candidate.id()));
  }

  private void load(WarpPluginContainer container, Map<String, WarpPluginContainer> byId) {
    PluginDescription description = container.description();
    List<PluginClassLoader> dependencyLoaders = new ArrayList<>();
    for (PluginDependency dependency : description.dependencies()) {
      WarpPluginContainer required = byId.get(dependency.id());
      if (required == null || required.state() != PluginState.ENABLED) {
        if (!dependency.optional()) {
          fail(
              container,
              new PluginLoadException(
                  "it requires plugin '" + dependency.id() + "', which failed to load"));
          return;
        }
        continue;
      }
      PluginClassLoader loader = required.classLoader();
      if (loader != null) {
        dependencyLoaders.add(loader);
      }
    }

    Path jar = container.jar();
    if (jar == null) {
      fail(container, new PluginLoadException("it has no jar"));
      return;
    }
    PluginClassLoader loader;
    try {
      ApiClassLoader parent =
          description.hasCapability(Capability.NETWORK_PIPELINE) ? networkApiLoader : apiLoader;
      loader =
          new PluginClassLoader(description.id(), jar.toUri().toURL(), parent, dependencyLoaders);
    } catch (MalformedURLException e) {
      fail(container, e);
      return;
    }
    container.setClassLoader(loader);
    WarpPluginContext context =
        new WarpPluginContext(
            container,
            proxy,
            events,
            channels,
            commands,
            permissions,
            readinessHolds,
            pipelines,
            logSinks,
            metrics);
    container.setContext(context);

    try {
      withContextLoader(
          loader,
          () -> {
            Class<?> mainClass = Class.forName(description.mainClass(), true, loader);
            // The console tags the loggers of the plugin's classes with the plugin's id.
            SourceTags.registerPlugin(mainClass.getPackageName(), description.id());
            Object instance =
                PluginInjector.construct(
                    mainClass, injectables(context), directory.resolve(description.id()));
            container.setInstance(instance);
            if (instance instanceof WarpPlugin plugin) {
              plugin.enable();
            }
          });
    } catch (Throwable t) {
      fail(container, t);
      return;
    }
    container.enabled();
    logger.info(
        "Enabled plugin {}{}{}",
        container,
        description.authors().isEmpty() ? "" : " by " + String.join(", ", description.authors()),
        describeCapabilities(description));
  }

  private Map<Class<?>, Object> injectables(WarpPluginContext context) {
    Map<Class<?>, Object> injectables = new HashMap<>();
    injectables.put(PluginContext.class, context);
    injectables.put(Warp.class, proxy);
    injectables.put(Logger.class, context.logger());
    injectables.put(PluginContainer.class, context.plugin());
    injectables.put(PluginDescription.class, context.plugin().description());
    injectables.put(PluginManager.class, this);
    injectables.put(EventBus.class, context.events());
    injectables.put(Messaging.class, context.messaging());
    injectables.put(CommandManager.class, context.commands());
    injectables.put(Permissions.class, context.permissions());
    if (context.plugin().description().hasCapability(Capability.NETWORK_PIPELINE)) {
      injectables.put(NetworkPipelines.class, context.network());
    }
    injectables.put(LogSinks.class, context.logSinks());
    return injectables;
  }

  /**
   * Describes the low-level access a plugin declared, for the line that enables it: an
   * administrator sees which plugins reach below the stable API.
   *
   * @param description the plugin's description
   * @return e.g. {@code " (low-level access: network-pipeline)"}, or an empty string
   */
  static String describeCapabilities(PluginDescription description) {
    if (description.capabilities().isEmpty()) {
      return "";
    }
    return description.capabilities().stream()
        .map(Capability::id)
        .collect(Collectors.joining(", ", " (low-level access: ", ")"));
  }

  private void fail(WarpPluginContainer container, Throwable cause) {
    container.failed(cause);
    WarpPluginContext context = container.context();
    if (context != null) {
      context.closeRegistrations();
    }
    close(container);
    switch (cause) {
      case PluginLoadException e ->
          logger.error(
              LogCode.PLUGIN_CANNOT_LOAD.marker(),
              "Plugin {} cannot load: {}",
              container,
              e.getMessage());
      case ClassNotFoundException e ->
          logger.error(
              LogCode.PLUGIN_MAIN_CLASS_MISSING.marker(),
              "Plugin {} cannot load: its main class {} is not in its jar",
              container,
              e.getMessage());
      case NoClassDefFoundError e ->
          logger.error(
              LogCode.PLUGIN_CLASS_NOT_VISIBLE.marker(),
              "Plugin {} cannot load: it uses class {}, which it cannot see. A plugin sees the Warp"
                  + " API, Adventure, SLF4J, its own jar and the plugins it depends on: shade the"
                  + " libraries it needs into its jar, or declare the plugin that provides them as"
                  + " a dependency{}",
              container,
              String.valueOf(e.getMessage()).replace('/', '.'),
              String.valueOf(e.getMessage())
                      .replace('/', '.')
                      .startsWith(ApiClassLoader.NETTY_PACKAGE)
                  ? ". To add handlers to the proxy's connections, declare the NETWORK_PIPELINE"
                      + " capability in @Plugin: the plugin then sees the proxy's Netty"
                  : "",
              e);
      default ->
          logger.error(
              LogCode.PLUGIN_LOAD_FAILED.marker(), "Plugin {} failed to load", container, cause);
    }
  }

  private static void close(WarpPluginContainer container) {
    PluginClassLoader loader = container.classLoader();
    if (loader == null) {
      return;
    }
    try {
      loader.close();
    } catch (IOException e) {
      logger.debug("Could not close the class loader of {}", container, e);
    }
  }

  /** Code run with a plugin's loader as the thread's context class loader. */
  @FunctionalInterface
  private interface PluginCode {
    void run() throws Exception;
  }

  /**
   * Runs plugin code with its class loader as the context class loader, which libraries such as
   * JDBC drivers, ServiceLoader users and serialisation frameworks rely on.
   */
  private static void withContextLoader(@Nullable ClassLoader loader, PluginCode code)
      throws Exception {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    if (loader != null) {
      thread.setContextClassLoader(loader);
    }
    try {
      code.run();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }
}
