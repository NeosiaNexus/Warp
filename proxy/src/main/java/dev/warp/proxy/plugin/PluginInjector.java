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

import dev.warp.api.plugin.DataDirectory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * Constructs a plugin's main class, passing each constructor parameter the object of its type.
 *
 * <p>There is no injection framework: a plugin's constructor asks for a few proxy services, which
 * are known in advance. Unlike Guice, a mistake names the parameter and lists what is available.
 */
final class PluginInjector {

  private PluginInjector() {}

  /**
   * Constructs a plugin.
   *
   * @param mainClass the plugin's main class
   * @param injectables the objects that can be injected, by type
   * @param dataDirectory the plugin's data directory, for a {@link DataDirectory} {@link Path}
   * @return the instance
   * @throws PluginLoadException if the class cannot be constructed
   * @throws Exception whatever the constructor throws (errors are rethrown as they are)
   */
  static Object construct(Class<?> mainClass, Map<Class<?>, Object> injectables, Path dataDirectory)
      throws Exception {
    Constructor<?>[] constructors = mainClass.getConstructors();
    if (constructors.length != 1) {
      throw new PluginLoadException(
          mainClass.getName()
              + " needs exactly one public constructor, it has "
              + constructors.length);
    }
    Constructor<?> constructor = constructors[0];
    Parameter[] parameters = constructor.getParameters();
    Object[] arguments = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      arguments[i] = argument(mainClass, parameters[i], i, injectables, dataDirectory);
    }
    try {
      return constructor.newInstance(arguments);
    } catch (InvocationTargetException e) {
      // Surface what the constructor threw, errors included (a missing class is a
      // NoClassDefFoundError), so the failure reported is the plugin's, not reflection's.
      switch (e.getCause()) {
        case Exception exception -> throw exception;
        case Error error -> throw error;
        case null, default -> throw e;
      }
    }
  }

  private static Object argument(
      Class<?> mainClass,
      Parameter parameter,
      int index,
      Map<Class<?>, Object> injectables,
      Path dataDirectory)
      throws PluginLoadException {
    Class<?> type = parameter.getType();
    if (type == Path.class && parameter.isAnnotationPresent(DataDirectory.class)) {
      return dataDirectory;
    }
    @Nullable Object value = injectables.get(type);
    if (value != null) {
      return value;
    }
    String available =
        injectables.keySet().stream()
            .map(Class::getSimpleName)
            .sorted()
            .collect(Collectors.joining(", "));
    throw new PluginLoadException(
        "parameter "
            + (index + 1)
            + " of the constructor of "
            + mainClass.getName()
            + " has type "
            + type.getName()
            + ", which the proxy cannot inject. Injectable types: "
            + available
            + ", and Path annotated with @DataDirectory");
  }
}
