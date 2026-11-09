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

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.FastThreadLocal;

/**
 * The slot each event loop thread owns in the striped counters: assigned once when the worker loops
 * start, read from a {@link FastThreadLocal} on every increment.
 *
 * <p>Slots are {@value #STRIDE} longs apart (128 bytes, a cache line on every platform Warp runs
 * on) so that two loops never write the same line. A thread without a slot, such as a virtual
 * thread running plugin code, adds through a shared adder instead: slower, never wrong.
 */
public final class LoopIndex {

  /** Longs between two slots: one cache line of 128 bytes. */
  public static final int STRIDE = 16;

  /** The value of {@link #current()} on a thread without a slot. */
  public static final int NONE = -1;

  private static final FastThreadLocal<int[]> SLOT = new FastThreadLocal<>();

  private LoopIndex() {}

  /**
   * Returns the current thread's slot: the offset of its first long in every striped array.
   *
   * @return the offset, or {@link #NONE}
   */
  public static int current() {
    int[] slot = SLOT.get();
    return slot == null ? NONE : slot[0];
  }

  /**
   * Gives the current thread a slot. Tests and the loop assignment call it; a thread keeps its slot
   * for good.
   *
   * @param index the thread's index, 0 to the registry's stripe count - 1
   */
  public static void assign(int index) {
    if (index < 0) {
      throw new IllegalArgumentException("index must be 0 or more, found " + index);
    }
    SLOT.set(new int[] {index * STRIDE});
  }

  /** Takes the current thread's slot away (tests). */
  public static void clear() {
    SLOT.remove();
  }

  /**
   * Gives every executor of a group a slot, 0 to its size - 1, on the executor's own thread. Each
   * assignment is a task on its loop: until it ran, that loop adds through the shared adder.
   *
   * @param group the worker loops
   * @return how many slots were assigned
   */
  public static int assignAll(EventExecutorGroup group) {
    int index = 0;
    for (EventExecutor executor : group) {
      int slot = index++;
      executor.execute(() -> assign(slot));
    }
    return index;
  }
}
