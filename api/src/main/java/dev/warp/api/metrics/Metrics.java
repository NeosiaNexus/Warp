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
package dev.warp.api.metrics;

import java.util.function.DoubleSupplier;

/**
 * Registers metrics and reads them. Each plugin has its own, from {@link
 * dev.warp.api.plugin.PluginContext#metrics()}: names are prefixed with the plugin's id ({@link
 * MetricNames#pluginPrefix}), and every metric and collector it registered is removed when it is
 * disabled or fails to load.
 *
 * <p>Everything registered here is exposed on the management server's {@code GET /metrics}, in the
 * Prometheus text format or OpenMetrics, with the proxy's own metrics.
 *
 * <h2>Cardinality</h2>
 *
 * <p>Label values must come from a closed set (an outcome, a state), a configuration-bounded name
 * (a backend server), or a small index. The registry enforces it:
 *
 * <ul>
 *   <li>a label named after a player or an address is refused ({@link MetricNames#RESERVED_LABELS})
 *   <li>one label has at most 32 distinct values: the 33rd and every later one is recorded as
 *       {@code other}, counted in {@code warp_metrics_label_overflow_total}, and logged once
 *   <li>a plugin has at most 2 000 series in all; beyond that, new label combinations share one
 *       series whose values are all {@code other}
 * </ul>
 *
 * <p>Registering the same name twice returns the existing metric when its type and labels match,
 * and throws otherwise.
 *
 * <h2>Cost</h2>
 *
 * <p>Resolve children once, when the code that updates them is set up, and keep them: {@code
 * labels(...)} is a map lookup, the update is not. A counter or histogram child updated on an event
 * loop writes that loop's own slot: no allocation, no lock, no atomic instruction, no false
 * sharing. From any other thread it goes through a shared adder.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface Metrics {

  /**
   * Registers a counter.
   *
   * @param name the name, ending with {@code _total}; a plugin's is prefixed with its id
   * @param help one sentence on what it counts
   * @param labelNames its labels, in the order {@link Counter#labels(String...)} takes values
   * @return the counter
   * @throws IllegalArgumentException if the name or a label breaks {@link MetricNames}, or the name
   *     is registered as something else
   */
  Counter counter(String name, String help, String... labelNames);

  /**
   * Registers a gauge whose children the code sets.
   *
   * @param name the name; a plugin's is prefixed with its id
   * @param help one sentence on what it measures
   * @param labelNames its labels, in the order {@link Gauge#labels(String...)} takes values
   * @return the gauge
   * @throws IllegalArgumentException if the name or a label breaks {@link MetricNames}, or the name
   *     is registered as something else
   */
  Gauge gauge(String name, String help, String... labelNames);

  /**
   * Registers a gauge without labels that is read when scraped, for a value that exists already: a
   * collection's size, a queue's depth. The supplier runs on the scraping thread and must be cheap
   * and safe to call from any thread.
   *
   * @param name the name; a plugin's is prefixed with its id
   * @param help one sentence on what it measures
   * @param value reads the value
   * @throws IllegalArgumentException if the name breaks {@link MetricNames} or is registered
   *     already
   */
  void gauge(String name, String help, DoubleSupplier value);

  /**
   * Registers a histogram.
   *
   * @param name the name, with its unit ({@code _seconds}, {@code _bytes}); a plugin's is prefixed
   *     with its id
   * @param help one sentence on what it observes
   * @param buckets its buckets, fixed for good
   * @param labelNames its labels, in the order {@link Histogram#labels(String...)} takes values
   * @return the histogram
   * @throws IllegalArgumentException if the name or a label breaks {@link MetricNames}, or the name
   *     is registered as something else
   */
  Histogram histogram(String name, String help, Buckets buckets, String... labelNames);

  /**
   * Registers a collector, for samples produced at scrape time: a bridge from another metrics
   * library, or a snapshot of a subsystem.
   *
   * @param id a name for the collector, in logs and in {@code warp_metrics_collector_errors_total};
   *     a plugin's is prefixed with its id
   * @param collector the collector
   * @throws IllegalArgumentException if the id breaks the name rules or is registered already
   */
  void collector(String id, Collector collector);

  /**
   * Visits every family and sample: the collectors first, in the order of their ids, then the
   * registered metrics sorted by name. Reads are consistent per series, not across series.
   *
   * @param visitor receives the samples
   */
  void collect(SampleVisitor visitor);
}
