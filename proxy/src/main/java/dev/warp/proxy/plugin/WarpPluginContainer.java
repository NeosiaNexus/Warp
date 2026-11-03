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

import dev.warp.api.plugin.PluginContainer;
import dev.warp.api.plugin.PluginDescription;
import dev.warp.api.plugin.PluginState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/** The proxy's record of one plugin. Its state only changes on the thread loading the plugins. */
final class WarpPluginContainer implements PluginContainer {

  private final PluginDescription description;
  private final @Nullable Path jar;
  private final Path dataDirectory;

  private volatile PluginState state = PluginState.LOADING;
  private volatile @Nullable Object instance;
  private volatile @Nullable Throwable failure;
  private volatile @Nullable PluginClassLoader classLoader;
  private volatile @Nullable WarpPluginContext context;

  /**
   * Creates the record of a plugin found on disk.
   *
   * @param description what the plugin declares
   * @param jar the plugin's jar, or {@code null} for a plugin not loaded from a jar (tests)
   * @param dataDirectory where the plugin keeps its files, created on first access
   */
  WarpPluginContainer(PluginDescription description, @Nullable Path jar, Path dataDirectory) {
    this.description = description;
    this.jar = jar;
    this.dataDirectory = dataDirectory;
  }

  @Override
  public PluginDescription description() {
    return description;
  }

  @Override
  public PluginState state() {
    return state;
  }

  @Override
  public Optional<Object> instance() {
    return Optional.ofNullable(instance);
  }

  @Override
  public Optional<Throwable> failure() {
    return Optional.ofNullable(failure);
  }

  @Override
  public Path dataDirectory() {
    try {
      return Files.createDirectories(dataDirectory);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create the data directory of " + description.id(), e);
    }
  }

  @Override
  public String toString() {
    return description.id() + (description.version().isEmpty() ? "" : " " + description.version());
  }

  // ---------------------------------------------------------------------------
  // Proxy side
  // ---------------------------------------------------------------------------

  @Nullable Path jar() {
    return jar;
  }

  @Nullable PluginClassLoader classLoader() {
    return classLoader;
  }

  void setClassLoader(@Nullable PluginClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  @Nullable WarpPluginContext context() {
    return context;
  }

  void setContext(WarpPluginContext context) {
    this.context = context;
  }

  void setInstance(Object instance) {
    this.instance = instance;
  }

  void enabled() {
    this.state = PluginState.ENABLED;
  }

  void failed(Throwable cause) {
    this.failure = cause;
    this.state = PluginState.FAILED;
  }

  void disabled() {
    this.state = PluginState.DISABLED;
  }
}
