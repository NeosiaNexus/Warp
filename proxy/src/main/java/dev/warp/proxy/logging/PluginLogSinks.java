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
package dev.warp.proxy.logging;

import dev.warp.api.event.Subscription;
import dev.warp.api.logging.LogLevel;
import dev.warp.api.logging.LogSink;
import dev.warp.api.logging.LogSinkSubscription;
import dev.warp.api.logging.LogSinks;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * One plugin's log sinks: registered under its name, run with its class loader, removed with it.
 */
public final class PluginLogSinks implements LogSinks {

  private final LogSinkRegistry registry;
  private final String owner;
  private final ClassLoader contextLoader;
  private final Consumer<Subscription> tracker;

  /**
   * Creates a plugin's registrar.
   *
   * @param registry the proxy's sinks
   * @param owner the plugin's identifier
   * @param contextLoader the plugin's class loader, the context class loader of its sinks' threads;
   *     {@code null} for the proxy's
   * @param tracker receives every registration, to remove it when the plugin goes away
   */
  public PluginLogSinks(
      LogSinkRegistry registry,
      String owner,
      @Nullable ClassLoader contextLoader,
      Consumer<Subscription> tracker) {
    this.registry = registry;
    this.owner = owner;
    this.contextLoader =
        contextLoader == null ? PluginLogSinks.class.getClassLoader() : contextLoader;
    this.tracker = tracker;
  }

  @Override
  public LogSinkSubscription register(LogLevel threshold, int capacity, LogSink sink) {
    LogSinkSubscription subscription =
        registry.register(owner, threshold, capacity, sink, contextLoader);
    tracker.accept(subscription);
    return subscription;
  }
}
