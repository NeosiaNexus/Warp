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

import org.jspecify.annotations.Nullable;

/**
 * Builds a line of console text in a palette {@link Style}. Every styled piece resets the
 * attributes after itself, so that no colour ever leaks into the next piece, line or program; at
 * {@link ColorDepth#NONE} nothing but the text is written.
 */
final class Styled {

  private final StringBuilder out;
  private final ColorDepth depth;
  private final Glyphs glyphs;

  /**
   * Creates a builder.
   *
   * @param depth the colours of the output
   * @param glyphs the symbols the output can show
   */
  Styled(ColorDepth depth, Glyphs glyphs) {
    this(new StringBuilder(128), depth, glyphs);
  }

  private Styled(StringBuilder out, ColorDepth depth, Glyphs glyphs) {
    this.out = out;
    this.depth = depth;
    this.glyphs = glyphs;
  }

  /**
   * Appends text in a style.
   *
   * @param style the style, or {@code null} for the terminal's default
   * @param text the text
   * @return this builder
   */
  Styled append(@Nullable Style style, CharSequence text) {
    if (style == null || !depth.colored() || text.isEmpty()) {
      out.append(text);
    } else {
      out.append(style.open(depth)).append(text).append(Ansi.RESET);
    }
    return this;
  }

  /**
   * Appends text in the terminal's default style.
   *
   * @param text the text
   * @return this builder
   */
  Styled append(CharSequence text) {
    out.append(text);
    return this;
  }

  /**
   * Appends a character in the terminal's default style.
   *
   * @param c the character
   * @return this builder
   */
  Styled append(char c) {
    out.append(c);
    return this;
  }

  /**
   * Appends spaces.
   *
   * @param count how many, nothing when zero or negative
   * @return this builder
   */
  Styled pad(int count) {
    out.repeat(' ', Math.max(0, count));
    return this;
  }

  /** Returns the colours of the output. */
  ColorDepth depth() {
    return depth;
  }

  /** Returns the symbols the output can show. */
  Glyphs glyphs() {
    return glyphs;
  }

  /** Returns how many characters were written, escape sequences included. */
  int length() {
    return out.length();
  }

  @Override
  public String toString() {
    return out.toString();
  }
}
