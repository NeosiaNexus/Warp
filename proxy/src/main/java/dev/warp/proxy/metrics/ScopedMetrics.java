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
package dev.warp.proxy.metrics;

import dev.warp.api.metrics.Buckets;
import dev.warp.api.metrics.Collector;
import dev.warp.api.metrics.Counter;
import dev.warp.api.metrics.Gauge;
import dev.warp.api.metrics.Histogram;
import dev.warp.api.metrics.MetricNames;
import dev.warp.api.metrics.Metrics;
import dev.warp.api.metrics.SampleVisitor;

import java.util.function.DoubleSupplier;

/**
 * One plugin's view of the registry: every name prefixed with the plugin's id, the plugin's caps,
 * and {@link #close()} removing everything it registered when the plugin goes away.
 */
public final class ScopedMetrics implements Metrics, AutoCloseable {

  private final MetricRegistry registry;
  private final MetricRegistry.Owner owner;
  private volatile boolean closed;

  ScopedMetrics(MetricRegistry registry, MetricRegistry.Owner owner) {
    this.registry = registry;
    this.owner = owner;
  }

  /**
   * Returns the prefix of the plugin's names.
   *
   * @return the prefix, ending with an underscore
   */
  public String prefix() {
    return owner.prefix;
  }

  /**
   * Returns whether the plugin's metrics are still registered.
   *
   * @return {@code false} once closed
   */
  public boolean isActive() {
    return !closed;
  }

  @Override
  public Counter counter(String name, String help, String... labelNames) {
    return registry.registerCounter(owner, prefixed(name), help, labelNames);
  }

  @Override
  public Gauge gauge(String name, String help, String... labelNames) {
    return registry.registerGauge(owner, prefixed(name), help, labelNames);
  }

  @Override
  public void gauge(String name, String help, DoubleSupplier value) {
    registry.registerPullGauge(owner, prefixed(name), help, value);
  }

  @Override
  public Histogram histogram(String name, String help, Buckets buckets, String... labelNames) {
    return registry.registerHistogram(owner, prefixed(name), help, buckets, labelNames);
  }

  @Override
  public void collector(String id, Collector collector) {
    registry.registerCollector(owner, prefixed(id), collector);
  }

  @Override
  public void collect(SampleVisitor visitor) {
    registry.collect(visitor);
  }

  /** Removes every metric and collector the plugin registered. */
  @Override
  public void close() {
    closed = true;
    registry.remove(owner);
  }

  private String prefixed(String name) {
    if (closed) {
      throw new IllegalStateException("plugin " + owner.id + " is disabled: no metrics");
    }
    if (MetricNames.hasReservedPrefix(name)) {
      throw new IllegalArgumentException(
          "metric name '" + name + "' starts with a prefix reserved for the proxy");
    }
    String full = owner.prefix + name;
    if (MetricNames.hasReservedPrefix(full)) {
      throw new IllegalArgumentException(
          "plugin id " + owner.id + " gives the reserved prefix " + owner.prefix);
    }
    return full;
  }
}
