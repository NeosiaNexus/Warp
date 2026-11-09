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

import java.util.List;

/**
 * Receives every sample of a registry, family by family: what the Prometheus exposition and bridges
 * to other metric libraries are built on. Called from {@link Metrics#collect}, on the thread that
 * scrapes, never on an event loop.
 */
public interface SampleVisitor {

  /**
   * Starts a family: a metric and the samples that follow, until the next family.
   *
   * @param name the metric's name, without a sample suffix ({@code _bucket}, {@code _sum}, {@code
   *     _count}); a counter's name keeps its {@code _total}
   * @param type the metric's type
   * @param help one sentence on what the metric means
   */
  void family(String name, MetricType type, String help);

  /**
   * One sample of the current family.
   *
   * @param name the sample's name: the family's, or with a suffix for a histogram's {@code
   *     _bucket}, {@code _sum} and {@code _count} and a summary's {@code _sum} and {@code _count}
   * @param labelNames the label names, {@code le} included for a bucket
   * @param labelValues the values, one per name
   * @param value the value; {@link Double#POSITIVE_INFINITY} is never a value, only a {@code le}
   */
  void sample(String name, List<String> labelNames, List<String> labelValues, double value);
}
