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
package dev.warp.protocol.text;

import net.kyori.adventure.nbt.BinaryTag;

/**
 * Where {@link ComponentWriter} writes a component: a tree of named fields that is JSON text
 * ({@link JsonTree}) or network NBT ({@link NbtTree}).
 *
 * <p>Fields are written in order into the object last opened. A value without a name is the root of
 * the tree or an element of the list last opened. Lists announce their size and the kind of their
 * elements up front, as NBT must.
 */
sealed interface TreeSink permits JsonTree, NbtTree {

  /** The elements of a list: all strings, or all objects. */
  enum Kind {
    STRING,
    OBJECT
  }

  /**
   * Whether the elements of a list must all be of the {@link Kind} it announced: in NBT a list has
   * one element type, in JSON an array may mix strings and objects.
   *
   * @return {@code true} for NBT
   */
  boolean homogeneousLists();

  /**
   * Writes a string field.
   *
   * @param key the field name
   * @param value the string
   */
  void string(String key, String value);

  /**
   * Writes a boolean field: a JSON boolean, an NBT byte 1 or 0.
   *
   * @param key the field name
   * @param value the boolean
   */
  void bool(String key, boolean value);

  /**
   * Writes an int field.
   *
   * @param key the field name
   * @param value the int
   */
  void integer(String key, int value);

  /**
   * Writes an int array field: a JSON array of numbers, an NBT int array.
   *
   * @param key the field name
   * @param values the ints
   */
  void intArray(String key, int[] values);

  /**
   * Writes an NBT value as a field: as it is in NBT, converted to the same JSON as vanilla's {@code
   * NbtOps} to {@code JsonOps} conversion in JSON.
   *
   * @param key the field name
   * @param tag the value
   */
  void tag(String key, BinaryTag tag);

  /**
   * Opens an object field; {@link #endObject()} closes it.
   *
   * @param key the field name
   */
  void beginObject(String key);

  /**
   * Opens a list field; {@link #endList()} closes it after exactly {@code size} elements.
   *
   * @param key the field name
   * @param kind the kind of every element when the lists are {@link #homogeneousLists()}
   * @param size the number of elements
   */
  void beginList(String key, Kind kind, int size);

  /**
   * Writes a string as the root or as the next element of the current list.
   *
   * @param value the string
   */
  void stringValue(String value);

  /** Opens an object as the root or as the next element of the current list. */
  void beginObjectValue();

  /** Closes the object last opened. */
  void endObject();

  /** Closes the list last opened. */
  void endList();
}
