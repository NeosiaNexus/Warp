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

/**
 * Produces samples when scraped, for values that live elsewhere: a bridge from another metrics
 * library, or a snapshot of a subsystem. Registered with {@link Metrics#collector(String,
 * Collector)}.
 *
 * <p>A collector that throws is skipped for that scrape, counted in {@code
 * warp_metrics_collector_errors_total} and logged once: it never fails the whole scrape. It runs on
 * the scraping thread, so it must be safe to call from any thread.
 */
@FunctionalInterface
public interface Collector {

  /**
   * Writes the collector's families and samples. Names follow the same rules as registered metrics
   * ({@link MetricNames#validate}), and a plugin's names must start with its prefix.
   *
   * @param out where the samples go
   */
  void collect(SampleVisitor out);
}
