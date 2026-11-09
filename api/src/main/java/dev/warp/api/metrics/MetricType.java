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

/** The kinds of metric a registry exposes, as Prometheus names them. */
public enum MetricType {
  /** A value that only goes up, reset when the proxy restarts: packets forwarded, logins. */
  COUNTER,

  /** A value that goes up and down: players online, memory in use. */
  GAUGE,

  /**
   * A distribution of observations in fixed buckets, with their sum and count: how long logins
   * take. Percentiles are computed by the monitoring system, across proxies if asked.
   */
  HISTOGRAM,

  /**
   * A sum and a count of observations, without buckets: kept for metrics that predate histograms.
   */
  SUMMARY
}
