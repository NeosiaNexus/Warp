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
package dev.warp.proxy.console;

import net.kyori.ansi.ColorLevel;

/** How many colours the console uses, from none at all to 24-bit. */
public enum ColorDepth {
  /** No escape code of any kind: the output is plain text. */
  NONE,
  /** The 16 standard ANSI colours, which every colour terminal, panel and log viewer renders. */
  ANSI_16,
  /** The xterm 256-colour palette. */
  ANSI_256,
  /** 24-bit colour. */
  TRUE_COLOR;

  /**
   * Returns whether this depth writes escape codes at all.
   *
   * @return {@code false} for {@link #NONE} only
   */
  public boolean colored() {
    return this != NONE;
  }

  /**
   * Returns the matching level of Adventure's ANSI serializer, which renders components.
   *
   * @return the level
   */
  ColorLevel adventureLevel() {
    return switch (this) {
      case NONE -> ColorLevel.NONE;
      case ANSI_16 -> ColorLevel.INDEXED_16;
      case ANSI_256 -> ColorLevel.INDEXED_256;
      case TRUE_COLOR -> ColorLevel.TRUE_COLOR;
    };
  }
}
