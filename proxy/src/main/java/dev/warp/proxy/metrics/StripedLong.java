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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.LongAdder;

/**
 * A long each event loop adds to in a slot of its own, summed when read: the storage of a counter
 * child and of a gauge that moves per connection.
 *
 * <p>Only the owning loop writes its slot, with a plain read and an opaque write (a store that the
 * JIT may not elide or reorder with other opaque accesses to the array, at the price of a plain
 * store on every platform). The reader sums the slots with opaque reads: each slot's value is at
 * least as fresh as the previous read, and never a torn or lost update, as one thread writes it.
 * Threads without a slot go through a {@link LongAdder}.
 */
final class StripedLong {

  private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

  private final long[] slots;
  private final LongAdder shared = new LongAdder();

  /**
   * Creates the storage.
   *
   * @param stripes how many event loops have a slot
   */
  StripedLong(int stripes) {
    this.slots = new long[stripes * LoopIndex.STRIDE];
  }

  /**
   * Adds an amount from the current thread.
   *
   * @param amount how much, negative to subtract
   */
  void add(long amount) {
    int slot = LoopIndex.current();
    long[] mine = slots;
    if (slot >= 0 && slot < mine.length) {
      LONGS.setOpaque(mine, slot, mine[slot] + amount);
    } else {
      shared.add(amount);
    }
  }

  /**
   * Returns the sum of every slot and of the shared adder.
   *
   * @return the sum
   */
  long sum() {
    long sum = shared.sum();
    long[] mine = slots;
    for (int slot = 0; slot < mine.length; slot += LoopIndex.STRIDE) {
      sum += (long) LONGS.getOpaque(mine, slot);
    }
    return sum;
  }
}
