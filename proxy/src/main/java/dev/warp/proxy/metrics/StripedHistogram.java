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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * A histogram's storage: per event loop, one count per bucket and a sum, in slots of the loop's
 * own, as {@link StripedLong} does; threads without a slot use atomics. Bucket counts are not
 * cumulative here; the exposition sums them.
 */
final class StripedHistogram {

  private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);
  private static final VarHandle DOUBLES = MethodHandles.arrayElementVarHandle(double[].class);

  private final Buckets buckets;
  private final int bucketCount;

  /** Each loop's counts: {@code stride} longs apart, a multiple of {@link LoopIndex#STRIDE}. */
  private final long[] counts;

  private final int stride;
  private final double[] sums;
  private final AtomicLongArray sharedCounts;
  private final DoubleAdder sharedSum = new DoubleAdder();

  StripedHistogram(Buckets buckets, int stripes) {
    this.buckets = buckets;
    this.bucketCount = buckets.size() + 1; // +Inf
    int lines = (bucketCount + LoopIndex.STRIDE - 1) / LoopIndex.STRIDE;
    this.stride = lines * LoopIndex.STRIDE;
    this.counts = new long[stripes * stride];
    this.sums = new double[stripes * LoopIndex.STRIDE];
    this.sharedCounts = new AtomicLongArray(bucketCount);
  }

  /**
   * Records an observation.
   *
   * @param value the observation; NaN is ignored
   */
  void observe(double value) {
    if (Double.isNaN(value)) {
      return;
    }
    int bucket = buckets.indexOf(value);
    int slot = LoopIndex.current();
    if (slot >= 0 && slot < sums.length) {
      int loop = slot / LoopIndex.STRIDE;
      int at = loop * stride + bucket;
      long[] mine = counts;
      LONGS.setOpaque(mine, at, mine[at] + 1);
      double[] total = sums;
      DOUBLES.setOpaque(total, slot, total[slot] + value);
    } else {
      sharedCounts.incrementAndGet(bucket);
      sharedSum.add(value);
    }
  }

  /**
   * Returns how many observations fell in one bucket, this bucket alone.
   *
   * @param bucket the bucket, {@link Buckets#size()} for {@code +Inf}
   * @return the count
   */
  long bucketCount(int bucket) {
    long sum = sharedCounts.get(bucket);
    for (int loop = 0; loop < sums.length / LoopIndex.STRIDE; loop++) {
      sum += (long) LONGS.getOpaque(counts, loop * stride + bucket);
    }
    return sum;
  }

  long count() {
    long total = 0;
    for (int bucket = 0; bucket < bucketCount; bucket++) {
      total += bucketCount(bucket);
    }
    return total;
  }

  double sum() {
    double total = sharedSum.sum();
    for (int slot = 0; slot < sums.length; slot += LoopIndex.STRIDE) {
      total += (double) DOUBLES.getOpaque(sums, slot);
    }
    return total;
  }

  Buckets buckets() {
    return buckets;
  }
}
