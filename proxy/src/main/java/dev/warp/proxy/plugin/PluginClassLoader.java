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

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Loads one plugin's classes: the API first (see {@link ApiClassLoader}), then the plugin's own
 * jar, then the jars of the plugins it depends on, transitively.
 *
 * <p>A plugin only sees the plugins it declared as dependencies. Dependencies form a graph without
 * cycles, so the delegation between plugin loaders cannot deadlock.
 */
final class PluginClassLoader extends URLClassLoader {

  static {
    registerAsParallelCapable();
  }

  private final List<PluginClassLoader> dependencies;

  /**
   * Creates the loader of a plugin.
   *
   * @param pluginId the plugin's identifier, which names the loader
   * @param jar the plugin's jar
   * @param api the loader of the API
   * @param dependencies the loaders of the plugins it depends on that are enabled
   */
  PluginClassLoader(
      String pluginId, URL jar, ApiClassLoader api, List<PluginClassLoader> dependencies) {
    super("warp-plugin-" + pluginId, new URL[] {jar}, api);
    this.dependencies = List.copyOf(dependencies);
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      Class<?> type = findLoadedClass(name);
      if (type == null) {
        type = loadFromParentOrSelf(name);
        if (type == null) {
          type = findInDependencies(name);
        }
        if (type == null) {
          throw new ClassNotFoundException(name);
        }
      }
      if (resolve) {
        resolveClass(type);
      }
      return type;
    }
  }

  private @Nullable Class<?> loadFromParentOrSelf(String name) {
    try {
      return getParent().loadClass(name);
    } catch (ClassNotFoundException notApi) {
      try {
        return findClass(name);
      } catch (ClassNotFoundException notOwn) {
        return null;
      }
    }
  }

  /**
   * Finds a class in this plugin's jar only, defining it if needed.
   *
   * @param name the binary class name
   * @return the class, or {@code null} if the jar does not hold it
   */
  @Nullable Class<?> findOwnClass(String name) {
    synchronized (getClassLoadingLock(name)) {
      Class<?> type = findLoadedClass(name);
      if (type != null) {
        return type;
      }
      try {
        return findClass(name);
      } catch (ClassNotFoundException e) {
        return null;
      }
    }
  }

  private @Nullable Class<?> findInDependencies(String name) {
    for (PluginClassLoader dependency : dependencies) {
      Class<?> type = dependency.findOwnClass(name);
      if (type == null) {
        type = dependency.findInDependencies(name);
      }
      if (type != null) {
        return type;
      }
    }
    return null;
  }
}
