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

/**
 * The few symbols the console draws, in Unicode, or in ASCII for outputs whose encoding cannot show
 * them (a Windows console on a legacy code page, a {@code LANG=C} container).
 *
 * @param up a reachable server, a working thing
 * @param down an unreachable server
 * @param rule one unit of a horizontal rule
 * @param ellipsis text left out
 * @param dot the separator between the facts of one line
 * @param arrow the prompt
 * @param logoTop the top row of the banner's logo, a W
 * @param logoBottom its bottom row
 */
record Glyphs(
    String up,
    String down,
    String rule,
    String ellipsis,
    String dot,
    String arrow,
    String logoTop,
    String logoBottom) {

  /** Unicode symbols. */
  static final Glyphs UNICODE = new Glyphs("●", "○", "─", "…", "·", "›", "█ █ █", "▀▄▀▄▀");

  /** Their ASCII stand-ins. */
  static final Glyphs ASCII =
      new Glyphs("+", "-", "-", "...", "|", ">", "\\  /\\  /", " \\/  \\/ ");

  /**
   * Returns the symbols for an output.
   *
   * @param unicode whether the output can show Unicode
   * @return the symbols
   */
  static Glyphs of(boolean unicode) {
    return unicode ? UNICODE : ASCII;
  }
}
