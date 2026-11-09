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
 * A metric that only goes up: packets forwarded, logins by outcome. One counter has one child per
 * combination of label values, and the child is what the code increments.
 *
 * <p>Resolve a child once, when the code that increments it is set up, and keep it: {@link
 * #labels(String...)} looks the child up in a map, while {@link Child#inc()} is a plain add on the
 * current event loop's own slot, without allocation, lock or atomic instruction. A child is safe to
 * increment from any thread; off the event loops the add goes through a shared adder.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface Counter {

  /**
   * Returns the exposed name, such as {@code warp_logins_total} or {@code myplugin_lookups_total}.
   *
   * @return the name
   */
  String name();

  /**
   * Returns the label names, in the order {@link #labels(String...)} takes their values.
   *
   * @return the names, empty for a counter without labels
   */
  List<String> labelNames();

  /**
   * Returns the child for these label values, creating it the first time. The 33rd distinct value
   * of one label, and every later one, is recorded as {@code other}: see the cardinality rules in
   * {@link Metrics}.
   *
   * @param values one value per label name, in order
   * @return the child, the same object for the same values
   * @throws IllegalArgumentException if the number of values differs from the number of labels, or
   *     a value is empty
   */
  Child labels(String... values);

  /** One series of a counter: the value for one combination of label values. */
  interface Child {

    /** Adds one. */
    void inc();

    /**
     * Adds an amount.
     *
     * @param amount how much to add, 0 or more
     * @throws IllegalArgumentException if {@code amount} is negative
     */
    void add(long amount);

    /**
     * Returns the value, summed over every thread that added to it.
     *
     * @return the value
     */
    long value();
  }
}
