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
 * A metric that goes up and down: connections in a state, a backend's round trip. One gauge has one
 * child per combination of label values, and the child is what the code sets or moves.
 *
 * <p>A child is a single value shared by every thread, updated atomically: fit for events that
 * happen per connection or per second, not per packet. Values that already exist elsewhere, such as
 * a collection's size, are better exposed with {@link Metrics#gauge(String, String,
 * java.util.function.DoubleSupplier)}, which reads them when scraped.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface Gauge {

  /**
   * Returns the exposed name, such as {@code warp_players_online}.
   *
   * @return the name
   */
  String name();

  /**
   * Returns the label names, in the order {@link #labels(String...)} takes their values.
   *
   * @return the names, empty for a gauge without labels
   */
  List<String> labelNames();

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

  /** One series of a gauge: the value for one combination of label values. */
  interface Child {

    /**
     * Sets the value.
     *
     * @param value the new value
     */
    void set(double value);

    /** Adds one. */
    void inc();

    /** Subtracts one. */
    void dec();

    /**
     * Adds an amount, negative to subtract.
     *
     * @param amount how much to add
     */
    void add(double amount);

    /**
     * Returns the value.
     *
     * @return the value
     */
    double value();
  }
}
