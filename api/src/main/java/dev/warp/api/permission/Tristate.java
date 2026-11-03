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
package dev.warp.api.permission;

/** A permission check's answer: granted, refused, or no opinion. */
public enum Tristate {
  /** Granted. */
  TRUE,
  /** Refused. */
  FALSE,
  /** No opinion: the next provider decides, and without one, refused. */
  UNDEFINED;

  /**
   * Returns the answer for a boolean.
   *
   * @param value the boolean
   * @return {@link #TRUE} or {@link #FALSE}
   */
  public static Tristate of(boolean value) {
    return value ? TRUE : FALSE;
  }

  /**
   * Returns this answer as a boolean.
   *
   * @param fallback the value of {@link #UNDEFINED}
   * @return {@code true} for {@link #TRUE}, {@code fallback} for {@link #UNDEFINED}
   */
  public boolean asBoolean(boolean fallback) {
    return switch (this) {
      case TRUE -> true;
      case FALSE -> false;
      case UNDEFINED -> fallback;
    };
  }
}
