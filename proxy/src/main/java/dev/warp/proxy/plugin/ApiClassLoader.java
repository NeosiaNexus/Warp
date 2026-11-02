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

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The parent of every plugin class loader: it shows plugins the JDK, the Warp API and the libraries
 * the API exposes, and nothing else of the proxy.
 *
 * <p>The proxy's own classes and the libraries it uses internally (Netty, Gson, Caffeine,
 * Configurate, Log4j) stay invisible, so a plugin can ship any version of them, and the proxy can
 * change its internals without breaking plugins. Velocity shares one class path between the proxy
 * and every plugin, which is why plugins there break on a Guava or Gson upgrade.
 */
final class ApiClassLoader extends ClassLoader {

  static {
    registerAsParallelCapable();
  }

  /** Packages the API exposes to plugins, as class name prefixes. */
  static final List<String> EXPOSED_PACKAGES =
      List.of("dev.warp.api.", "net.kyori.", "org.slf4j.", "org.jspecify.");

  private final ClassLoader proxyLoader;

  /**
   * Creates the loader.
   *
   * @param proxyLoader the loader of the proxy, which holds the API classes
   */
  ApiClassLoader(ClassLoader proxyLoader) {
    super("warp-api", ClassLoader.getPlatformClassLoader());
    this.proxyLoader = proxyLoader;
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (isExposed(name)) {
      return proxyLoader.loadClass(name);
    }
    // The JDK only: the platform loader, then findClass, which finds nothing.
    return super.loadClass(name, resolve);
  }

  @Override
  public @Nullable URL getResource(String name) {
    if (isExposedResource(name)) {
      return proxyLoader.getResource(name);
    }
    return super.getResource(name);
  }

  @Override
  public Enumeration<URL> getResources(String name) throws IOException {
    if (isExposedResource(name)) {
      return proxyLoader.getResources(name);
    }
    return super.getResources(name);
  }

  /**
   * Returns whether plugins see a class of the proxy's class path.
   *
   * @param className the binary class name
   * @return {@code true} for the API and the libraries it exposes
   */
  static boolean isExposed(String className) {
    for (String prefix : EXPOSED_PACKAGES) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isExposedResource(String name) {
    return isExposed(name.replace('/', '.'));
  }
}
