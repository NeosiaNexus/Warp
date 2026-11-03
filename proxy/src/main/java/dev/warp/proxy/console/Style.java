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

import org.jline.utils.AttributedStyle;

/**
 * The console's palette: each role, its 24-bit colour and the closest of the 16 standard colours
 * (chosen by hand, so that the 16-colour rendering stays readable on dark and light themes alike).
 * Greys are colours rather than the "faint" attribute, which some Windows consoles ignore.
 */
enum Style {
  /** Times, sources, addresses, library stack frames: present, but out of the way. */
  MUTED(0x7F849C, 90, false),
  /** Labels of the summaries. */
  LABEL(0xA6ADC8, 37, false),
  /** Text that must stand out without meaning anything: headings, Warp's own stack frames. */
  STRONG(-1, 1, true),
  /** The ERROR badge, exception class names. */
  ERROR(0xF38BA8, 91, true),
  /** The WARN badge. */
  WARN(0xF9E2AF, 93, true),
  /** The INFO badge: calm, it is most lines. */
  INFO(0x94E2D5, 36, false),
  /** The DEBUG and TRACE badges. */
  DEBUG(0x7F849C, 90, false),
  /** Player names. */
  PLAYER(0x89DCEB, 96, false),
  /** Backend server names. */
  SERVER(0xCBA6F7, 95, false),
  /** Numbers, durations, sizes. */
  NUMBER(0xFAB387, 33, false),
  /** Warp's brand colour: the banner, the prompt, known commands. */
  ACCENT(0x94E2D5, 96, true),
  /** The second brand colour, for the banner's gradient. */
  ACCENT_2(0xCBA6F7, 95, true),
  /** Something that works: a reachable server. */
  GOOD(0xA6E3A1, 92, false),
  /** Something that does not: an unreachable server, an unknown command, an attack. */
  BAD(0xF38BA8, 91, false),
  /** Something to watch: lockdown, a drain in progress. */
  CAUTION(0xF9E2AF, 93, false),
  /** Arguments of a command being typed. */
  ARGUMENT(0x89DCEB, 96, false);

  private final int rgb;
  private final int basic;
  private final boolean bold;

  /** The opening sequences at each depth, computed once. */
  private final String open16;

  private final String open256;
  private final String openTrueColor;

  Style(int rgb, int basic, boolean bold) {
    this.rgb = rgb;
    this.basic = basic;
    this.bold = bold;
    this.open16 = sequence(ColorDepth.ANSI_16);
    this.open256 = sequence(ColorDepth.ANSI_256);
    this.openTrueColor = sequence(ColorDepth.TRUE_COLOR);
  }

  /**
   * Returns the escape sequence that starts this style.
   *
   * @param depth the depth of the output
   * @return the sequence, empty for {@link ColorDepth#NONE}
   */
  String open(ColorDepth depth) {
    return switch (depth) {
      case NONE -> "";
      case ANSI_16 -> open16;
      case ANSI_256 -> open256;
      case TRUE_COLOR -> openTrueColor;
    };
  }

  /**
   * Returns this style for JLine, which renders the input line and the status line.
   *
   * @param depth the depth of the output
   * @return the style
   */
  AttributedStyle jline(ColorDepth depth) {
    AttributedStyle style = bold ? AttributedStyle.BOLD : AttributedStyle.DEFAULT;
    if (rgb < 0) {
      return style;
    }
    return switch (depth) {
      case NONE -> AttributedStyle.DEFAULT;
      case ANSI_16 -> style.foreground(basic >= 90 ? basic - 90 + 8 : basic - 30);
      case ANSI_256 -> style.foreground(Ansi.xterm256(rgb));
      case TRUE_COLOR -> style.foreground((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    };
  }

  private String sequence(ColorDepth depth) {
    if (!depth.colored()) {
      return "";
    }
    String color = rgb < 0 ? "" : Ansi.foreground(rgb, basic, depth);
    String attributes = bold ? (color.isEmpty() ? "1" : "1;" + color) : color;
    return "\u001B[" + attributes + "m";
  }
}
