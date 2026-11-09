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

import java.util.Arrays;
import java.util.List;
import java.util.stream.DoubleStream;

/**
 * The upper bounds of a histogram's buckets, in seconds for durations. A histogram also has an
 * implicit last bucket, {@code +Inf}, which every observation falls in.
 *
 * <p>Bucket sets are fixed when the histogram is registered: a plugin picks one of the three
 * presets or gives its own bounds with {@link #of(double...)}. Fewer than 16 buckets keeps the
 * histogram cheap to record and to scrape.
 */
public final class Buckets {

  /** The most buckets a histogram has, the implicit {@code +Inf} one not counted. */
  public static final int MAX_BUCKETS = 16;

  /** Latencies within a process, 1 ms to 2.5 s: event loop lag, a handler's run. */
  public static final Buckets FAST =
      new Buckets(new double[] {0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5});

  /** Network round trips, 5 ms to 30 s: a connection to a backend, a login. */
  public static final Buckets NETWORK =
      new Buckets(new double[] {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30});

  /** Long operations, 0.1 s to 10 min: a drain, a plugin's data load. */
  public static final Buckets LONG =
      new Buckets(new double[] {0.1, 0.5, 1, 5, 10, 30, 60, 120, 300, 600});

  private final double[] bounds;

  private Buckets(double[] bounds) {
    this.bounds = bounds;
  }

  /**
   * Returns buckets with these upper bounds.
   *
   * @param bounds the upper bounds, finite and strictly increasing, 1 to {@value #MAX_BUCKETS} of
   *     them; {@code +Inf} is added by the histogram
   * @return the buckets
   * @throws IllegalArgumentException if the bounds are empty, too many, not increasing or not
   *     finite
   */
  public static Buckets of(double... bounds) {
    if (bounds.length == 0 || bounds.length > MAX_BUCKETS) {
      throw new IllegalArgumentException(
          "a histogram has 1 to " + MAX_BUCKETS + " buckets, not " + bounds.length);
    }
    double[] copy = bounds.clone();
    for (int i = 0; i < copy.length; i++) {
      if (!Double.isFinite(copy[i])) {
        throw new IllegalArgumentException("bucket bounds must be finite, found " + copy[i]);
      }
      if (i > 0 && copy[i] <= copy[i - 1]) {
        throw new IllegalArgumentException(
            "bucket bounds must increase, found " + copy[i - 1] + " then " + copy[i]);
      }
    }
    return new Buckets(copy);
  }

  /**
   * Returns the upper bounds, increasing, without {@code +Inf}.
   *
   * @return the bounds
   */
  public List<Double> bounds() {
    return DoubleStream.of(bounds).boxed().toList();
  }

  /**
   * Returns the upper bound of one bucket.
   *
   * @param index the bucket, 0 to {@link #size()} - 1
   * @return its upper bound
   */
  public double bound(int index) {
    return bounds[index];
  }

  /**
   * Returns how many buckets there are, {@code +Inf} not counted.
   *
   * @return the count
   */
  public int size() {
    return bounds.length;
  }

  /**
   * Returns the bucket an observation falls in: the first whose bound is at least the value, or
   * {@link #size()} for the implicit {@code +Inf} bucket. A binary search, allocation-free.
   *
   * @param value the observation
   * @return the bucket's index
   */
  public int indexOf(double value) {
    int low = 0;
    int high = bounds.length;
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (bounds[mid] < value) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }
    return low;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Buckets that && Arrays.equals(bounds, that.bounds);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bounds);
  }

  @Override
  public String toString() {
    return "Buckets" + Arrays.toString(bounds);
  }
}
