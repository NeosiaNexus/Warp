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
package dev.warp.protocol.fuzz;

import java.lang.management.ManagementFactory;

import com.sun.management.ThreadMXBean;

/**
 * Measures the heap memory code allocates, so that a fuzz test catches an allocation sized by a
 * length field rather than by the bytes that back it.
 */
public final class HeapAllocations {

  private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

  private HeapAllocations() {}

  /**
   * Runs {@code action} and asserts that it allocated at most {@code maxBytes} of heap on the
   * current thread. An action over budget runs a second time and only that run counts, so that
   * one-time costs, such as class initialization, never fail a test: the action must do the same
   * work every time it runs.
   *
   * @param maxBytes the budget, in bytes
   * @param action the code to measure
   * @throws AssertionError if the action allocated more than {@code maxBytes}, twice
   */
  public static void assertAtMost(long maxBytes, Runnable action) {
    long allocated = allocatedBy(action);
    if (allocated > maxBytes) {
      allocated = allocatedBy(action);
      if (allocated > maxBytes) {
        throw new AssertionError(
            "Allocated " + allocated + " bytes of heap, more than the " + maxBytes + " expected");
      }
    }
  }

  private static long allocatedBy(Runnable action) {
    long before = THREADS.getCurrentThreadAllocatedBytes();
    action.run();
    return THREADS.getCurrentThreadAllocatedBytes() - before;
  }
}
