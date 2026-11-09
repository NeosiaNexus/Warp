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
 * A distribution of observations in fixed {@link Buckets}: how long logins take, how long a handler
 * runs. One histogram has one child per combination of label values, and the child is what the code
 * observes with.
 *
 * <p>Like a {@link Counter}, a child records on the current event loop's own slots without
 * allocation, lock or atomic instruction, and through a shared adder from other threads. Resolve it
 * once and keep it.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface Histogram {

  /**
   * Returns the exposed name, such as {@code warp_login_duration_seconds}.
   *
   * @return the name
   */
  String name();

  /**
   * Returns the label names, in the order {@link #labels(String...)} takes their values.
   *
   * @return the names, empty for a histogram without labels
   */
  List<String> labelNames();

  /**
   * Returns the buckets every child of this histogram uses.
   *
   * @return the buckets
   */
  Buckets buckets();

  /**
   * Returns the child for these label values, creating it the first time: see {@link
   * Counter#labels(String...)} for the rules on values.
   *
   * @param values one value per label name, in order
   * @return the child, the same object for the same values
   * @throws IllegalArgumentException if the number of values differs from the number of labels, or
   *     a value is empty
   */
  Child labels(String... values);

  /** One series of a histogram: the distribution for one combination of label values. */
  interface Child {

    /**
     * Records an observation, in the unit of the histogram's buckets (seconds for durations).
     *
     * @param value the observation; NaN is ignored
     */
    void observe(double value);

    /**
     * Records a duration measured with {@link System#nanoTime()}, as seconds.
     *
     * @param nanos the duration in nanoseconds
     */
    void observeNanos(long nanos);

    /**
     * Returns how many observations were recorded.
     *
     * @return the count
     */
    long count();

    /**
     * Returns the sum of the observations.
     *
     * @return the sum
     */
    double sum();
  }
}
