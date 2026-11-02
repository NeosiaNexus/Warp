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
package dev.warp.protocol.command;

import java.util.Arrays;
import java.util.Objects;

/** A growable list of {@code int}s, for building children lists and work queues without boxing. */
final class IntList {

  private int[] values;
  private int size;

  IntList(int capacity) {
    this.values = new int[Math.max(capacity, 4)];
  }

  /** A list holding a copy of {@code children}. */
  static IntList of(Children children) {
    IntList list = new IntList(children.size());
    for (int i = 0; i < children.size(); i++) {
      list.add(children.get(i));
    }
    return list;
  }

  void add(int value) {
    if (size == values.length) {
      values = Arrays.copyOf(values, size * 2);
    }
    values[size++] = value;
  }

  int get(int position) {
    return values[Objects.checkIndex(position, size)];
  }

  int size() {
    return size;
  }

  /** Removes the value at {@code position}, shifting the following ones down. */
  void removeAt(int position) {
    System.arraycopy(values, position + 1, values, position, size - position - 1);
    size--;
  }

  /** The values as children, in order. */
  Children toChildren() {
    return Children.wrap(Arrays.copyOf(values, size));
  }
}
