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
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;

/**
 * The children of a {@link CommandNode}: indices into the nodes of its {@link CommandTree}, in the
 * order the commands packet lists them.
 *
 * <p>An immutable list of {@code int}s rather than a {@code List<Integer>}: a vanilla tree has well
 * over a thousand edges, and none of them should cost a boxed integer. Two lists are equal when
 * they hold the same indices in the same order.
 */
public final class Children {

  private static final Children EMPTY = new Children(new int[0]);

  /** Owned: never handed out, never changed. */
  private final int[] indices;

  private Children(int[] indices) {
    this.indices = indices;
  }

  /**
   * Returns the empty list: a leaf node's children.
   *
   * @return the empty list
   */
  public static Children of() {
    return EMPTY;
  }

  /**
   * Returns a list of the given node indices, in order. The array is copied.
   *
   * @param indices node indices, none negative
   * @return the list
   * @throws IllegalArgumentException if an index is negative
   */
  public static Children of(int... indices) {
    if (indices.length == 0) {
      return EMPTY;
    }
    int[] copy = indices.clone();
    for (int index : copy) {
      if (index < 0) {
        throw new IllegalArgumentException("Negative node index: " + index);
      }
    }
    return new Children(copy);
  }

  /** Wraps an array the caller gives up, already checked: the decoder's and the editors' path. */
  static Children wrap(int[] indices) {
    return indices.length == 0 ? EMPTY : new Children(indices);
  }

  /**
   * Returns how many children the node has.
   *
   * @return the number of indices
   */
  public int size() {
    return indices.length;
  }

  /**
   * Returns whether the node is a leaf.
   *
   * @return {@code true} if there are no children
   */
  public boolean isEmpty() {
    return indices.length == 0;
  }

  /**
   * Returns the node index at a position of the list.
   *
   * @param position from 0 to {@link #size()} excluded
   * @return the node index there
   * @throws IndexOutOfBoundsException if the position is out of range
   */
  public int get(int position) {
    return indices[position];
  }

  /**
   * Returns whether the list holds a node index.
   *
   * @param index a node index
   * @return {@code true} if one of the children is that node
   */
  public boolean contains(int index) {
    for (int child : indices) {
      if (child == index) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns this list with one more node index at its end.
   *
   * @param index the node index to append, not negative
   * @return a new list
   * @throws IllegalArgumentException if the index is negative
   */
  public Children with(int index) {
    if (index < 0) {
      throw new IllegalArgumentException("Negative node index: " + index);
    }
    int[] longer = Arrays.copyOf(indices, indices.length + 1);
    longer[indices.length] = index;
    return new Children(longer);
  }

  /**
   * Returns the node indices as a new array, in order.
   *
   * @return a copy of the indices
   */
  public int[] toArray() {
    return indices.clone();
  }

  /**
   * Returns the node indices as a stream, in order.
   *
   * @return the indices
   */
  public IntStream stream() {
    return Arrays.stream(indices);
  }

  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof Children children && Arrays.equals(indices, children.indices);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(indices);
  }

  @Override
  public String toString() {
    return Arrays.toString(indices);
  }
}
