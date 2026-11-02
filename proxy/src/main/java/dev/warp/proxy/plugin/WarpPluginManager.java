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
import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginContext;
import dev.warp.api.plugin.PluginDependency;
import dev.warp.api.plugin.PluginDescription;
import dev.warp.api.plugin.PluginManager;
import dev.warp.api.plugin.PluginState;
import dev.warp.api.plugin.WarpPlugin;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
  private final Path directory;
  private final ApiClassLoader apiLoader;

  /** Every plugin found, in load order (plugins that cannot load at the end). */
  private volatile List<WarpPluginContainer> plugins = List.of();

  /**
   * Creates a manager for a plugins directory.
   *
   * @param proxy the proxy given to plugins
   * @param directory the plugins directory, created if missing
   */
  public WarpPluginManager(Warp proxy, Path directory) {
    this.proxy = proxy;
    this.directory = directory;
    this.apiLoader = new ApiClassLoader(WarpPluginManager.class.getClassLoader());
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

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
        logger.error("Plugin {} cannot load: {}", container, reason);
        containers.add(container);
      }
    }
    this.plugins = List.copyOf(containers);

    for (WarpPluginContainer container : loadOrder) {
      load(container, byId);
    }

    long enabled = containers.stream().filter(c -> c.state() == PluginState.ENABLED).count();
    if (!containers.isEmpty()) {
      logger.info("{} of {} plugins enabled", enabled, containers.size());
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
          logger.error("Plugin {} failed to disable cleanly", container, t);
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
      loader =
          new PluginClassLoader(
              description.id(), jar.toUri().toURL(), apiLoader, dependencyLoaders);
    } catch (MalformedURLException e) {
      fail(container, e);
      return;
    }
    container.setClassLoader(loader);
    WarpPluginContext context = new WarpPluginContext(container, proxy);
    container.setContext(context);

    try {
      withContextLoader(
          loader,
          () -> {
            Class<?> mainClass = Class.forName(description.mainClass(), true, loader);
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
        "Enabled plugin {}{}",
        container,
        description.authors().isEmpty() ? "" : " by " + String.join(", ", description.authors()));
  }

  private Map<Class<?>, Object> injectables(WarpPluginContext context) {
    Map<Class<?>, Object> injectables = new HashMap<>();
    injectables.put(PluginContext.class, context);
    injectables.put(Warp.class, proxy);
    injectables.put(Logger.class, context.logger());
    injectables.put(PluginContainer.class, context.plugin());
    injectables.put(PluginDescription.class, context.plugin().description());
    injectables.put(PluginManager.class, this);
    return injectables;
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
          logger.error("Plugin {} cannot load: {}", container, e.getMessage());
      case ClassNotFoundException e ->
          logger.error(
              "Plugin {} cannot load: its main class {} is not in its jar",
              container,
              e.getMessage());
      case NoClassDefFoundError e ->
          logger.error(
              "Plugin {} cannot load: it uses class {}, which it cannot see. A plugin sees the Warp"
                  + " API, Adventure, SLF4J, its own jar and the plugins it depends on: shade the"
                  + " libraries it needs into its jar, or declare the plugin that provides them as"
                  + " a dependency",
              container,
              String.valueOf(e.getMessage()).replace('/', '.'),
              e);
      default -> logger.error("Plugin {} failed to load", container, cause);
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
