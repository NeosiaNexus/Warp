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

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jline.utils.AttributedStyle;

/**
 * What styled text may use on an output: its colours (a {@link Theme} at a {@link ColorDepth}) and
 * whether it may hold clickable links. Each style's escape sequence is computed once per palette,
 * and each palette once per process.
 */
final class Palette {
  private static final Map<Key, Palette> CACHE = new ConcurrentHashMap<>();

  /** Plain text: no colour, no link. */
  static final Palette PLAIN = of(ColorDepth.NONE, Theme.DARK, false);

  private final ColorDepth depth;
  private final Theme theme;
  private final boolean hyperlinks;
  private final Map<Style, String> open = new EnumMap<>(Style.class);

  /** What tells palettes apart. */
  private record Key(ColorDepth depth, Theme theme, boolean hyperlinks) {}

  private Palette(ColorDepth depth, Theme theme, boolean hyperlinks) {
    this.depth = depth;
    this.theme = theme;
    this.hyperlinks = hyperlinks && depth.colored();
    for (Style style : Style.values()) {
      open.put(style, sequence(style));
    }
  }

  /**
   * Returns the palette of an output.
   *
   * @param depth its colours
   * @param theme the colours of each style
   * @param hyperlinks whether it shows clickable links; never without colours, which say the output
   *     takes escape sequences at all
   * @return the palette
   */
  static Palette of(ColorDepth depth, Theme theme, boolean hyperlinks) {
    return CACHE.computeIfAbsent(
        new Key(depth, theme, hyperlinks), key -> new Palette(depth, theme, hyperlinks));
  }

  /** Returns the colours of the output. */
  ColorDepth depth() {
    return depth;
  }

  /** Returns the colours of each style. */
  Theme theme() {
    return theme;
  }

  /** Returns whether the output writes escape codes at all. */
  boolean colored() {
    return depth.colored();
  }

  /** Returns whether the output shows OSC 8 links. */
  boolean hyperlinks() {
    return hyperlinks;
  }

  /**
   * Returns the escape sequence that starts a style.
   *
   * @param style the style
   * @return the sequence, empty for {@link ColorDepth#NONE}
   */
  String open(Style style) {
    return open.getOrDefault(style, "");
  }

  /**
   * Returns a style for JLine, which renders the input line and the status line.
   *
   * @param style the style
   * @return the JLine style
   */
  AttributedStyle jline(Style style) {
    if (!depth.colored()) {
      return AttributedStyle.DEFAULT;
    }
    AttributedStyle result = style.bold() ? AttributedStyle.BOLD : AttributedStyle.DEFAULT;
    Theme.Color color = theme.color(style);
    int rgb = color.rgb();
    if (rgb < 0) {
      return result;
    }
    int basic = color.basic();
    return switch (depth) {
      case NONE -> AttributedStyle.DEFAULT;
      case ANSI_16 -> result.foreground(basic >= 90 ? basic - 90 + 8 : basic - 30);
      case ANSI_256 -> result.foreground(Ansi.xterm256(rgb));
      case TRUE_COLOR -> result.foreground((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    };
  }

  private String sequence(Style style) {
    if (!depth.colored()) {
      return "";
    }
    Theme.Color color = theme.color(style);
    String foreground = color.rgb() < 0 ? "" : Ansi.foreground(color.rgb(), color.basic(), depth);
    String attributes =
        style.bold() ? (foreground.isEmpty() ? "1" : "1;" + foreground) : foreground;
    return "\u001B[" + attributes + "m";
  }
}
