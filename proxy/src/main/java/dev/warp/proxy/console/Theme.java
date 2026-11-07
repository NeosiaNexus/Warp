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

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * The colours of each {@link Style}, for a dark or a light background, with a colour-blind-safe
 * variant of each. Every colour keeps a contrast of at least 4.5:1 (WCAG AA for text) against the
 * common backgrounds of its kind; the colour-blind palettes build on Okabe and Ito's (blue against
 * orange rather than green against red, yellow kept apart from both), so that what must be told
 * apart stays apart under protanopia, deuteranopia and tritanopia. {@code ThemeTest} checks both.
 *
 * <p>Each colour also names the closest of the 16 standard colours, for terminals without 24-bit or
 * 256 colours; the light palettes avoid the bright ones, which most light themes render pale.
 */
public enum Theme {
  /** For a dark background: the default, since terminals, panels and log viewers mostly are. */
  DARK(false, false) {
    @Override
    Color color(Style style) {
      return switch (style) {
        case MUTED, DEBUG -> new Color(0x9CA0B0, 90);
        case LABEL -> new Color(0xA6ADC8, 37);
        case STRONG -> Color.NONE;
        case ERROR, BAD -> new Color(0xF38BA8, 91);
        case WARN, CAUTION -> new Color(0xF9E2AF, 93);
        case INFO, ACCENT -> new Color(0x94E2D5, 36);
        case PLAYER, ARGUMENT -> new Color(0x89DCEB, 96);
        case SERVER, ACCENT_2 -> new Color(0xCBA6F7, 95);
        case NUMBER -> new Color(0xFAB387, 33);
        case GOOD -> new Color(0xA6E3A1, 92);
      };
    }
  },
  /** For a light background: darker colours of the same hues. */
  LIGHT(true, false) {
    @Override
    Color color(Style style) {
      return switch (style) {
        case MUTED, DEBUG -> new Color(0x5F6377, 90);
        case LABEL -> new Color(0x4C4F69, 30);
        case STRONG -> Color.NONE;
        case ERROR, BAD -> new Color(0xC4122F, 31);
        case WARN, CAUTION -> new Color(0x8F5E00, 33);
        case INFO, ACCENT -> new Color(0x0B6E75, 36);
        case PLAYER, ARGUMENT -> new Color(0x0B5F94, 34);
        case SERVER, ACCENT_2 -> new Color(0x7A2FD8, 35);
        case NUMBER -> new Color(0xA34400, 33);
        case GOOD -> new Color(0x2B6F1E, 32);
      };
    }
  },
  /** Colour-blind safe, for a dark background. */
  COLORBLIND_DARK(false, true) {
    @Override
    Color color(Style style) {
      return switch (style) {
        case MUTED, DEBUG -> new Color(0xA3A3A3, 90);
        case LABEL -> new Color(0xBDBDBD, 37);
        case STRONG -> Color.NONE;
        case ERROR, BAD -> new Color(0xFF8A3D, 91);
        case WARN, CAUTION -> new Color(0xF0E442, 93);
        case INFO, ACCENT, PLAYER, ARGUMENT, GOOD -> new Color(0x56B4E9, 94);
        case SERVER, ACCENT_2 -> new Color(0xE69FC8, 95);
        case NUMBER -> new Color(0xE69F00, 33);
      };
    }
  },
  /** Colour-blind safe, for a light background. */
  COLORBLIND_LIGHT(true, true) {
    @Override
    Color color(Style style) {
      return switch (style) {
        case MUTED, DEBUG -> new Color(0x616161, 90);
        case LABEL -> new Color(0x424242, 30);
        case STRONG -> Color.NONE;
        case ERROR, BAD -> new Color(0x8F2400, 31);
        case WARN, CAUTION -> new Color(0x846600, 33);
        case INFO, ACCENT, PLAYER, ARGUMENT, GOOD -> new Color(0x005A9C, 34);
        case SERVER, ACCENT_2 -> new Color(0x9C3D78, 35);
        case NUMBER -> new Color(0x8F5B00, 33);
      };
    }
  };

  /** The minimum contrast of a text colour against its background: WCAG AA for normal text. */
  static final double MIN_CONTRAST = 4.5;

  private final boolean light;
  private final boolean colorblind;

  Theme(boolean light, boolean colorblind) {
    this.light = light;
    this.colorblind = colorblind;
  }

  /**
   * Returns the colour of a style in this theme.
   *
   * @param style the style
   * @return its colour, {@link Color#NONE} for the terminal's own
   */
  abstract Color color(Style style);

  /**
   * Returns whether this theme is for a light background.
   *
   * @return {@code true} for a light background
   */
  public boolean light() {
    return light;
  }

  /**
   * Returns whether this theme is colour-blind safe.
   *
   * @return {@code true} for the colour-blind palettes
   */
  public boolean colorblind() {
    return colorblind;
  }

  /**
   * Returns the background this theme is drawn on, as an RGB value: the one its colours are checked
   * against first, and that {@link #readable(int)} keeps colours legible on.
   *
   * @return black or white
   */
  int background() {
    return light ? 0xFFFFFF : 0x000000;
  }

  /**
   * Returns a colour that reads on this theme's background: the colour itself when it already does
   * (a contrast of {@value #MIN_CONTRAST}:1), else the same hue darkened (light background) or
   * lightened (dark background) until it does. For colours this palette does not choose: those of
   * command replies and of plugins' messages, written for Minecraft's dark chat.
   *
   * @param rgb the colour
   * @return a readable colour
   */
  int readable(int rgb) {
    int color = rgb;
    int target = light ? 0x000000 : 0xFFFFFF;
    for (int step = 1; contrast(color, light ? 0xF5F5F5 : 0x1E1E1E) < MIN_CONTRAST; step++) {
      color = mix(rgb, target, step / 20.0);
    }
    return color;
  }

  /**
   * Returns the theme of a setting and a background.
   *
   * @param setting the setting: {@code dark}, {@code light}, {@code colorblind} or {@code auto}
   * @param lightBackground whether the background is light, as detected
   * @return the theme, or {@code null} if the setting is none of these
   */
  static @Nullable Theme of(String setting, boolean lightBackground) {
    return switch (setting.strip().toLowerCase(Locale.ROOT)) {
      case "", "auto" -> lightBackground ? LIGHT : DARK;
      case "dark" -> DARK;
      case "light" -> LIGHT;
      case "colorblind", "colourblind" -> lightBackground ? COLORBLIND_LIGHT : COLORBLIND_DARK;
      default -> null;
    };
  }

  // ---------------------------------------------------------------------------
  // Contrast (WCAG 2)
  // ---------------------------------------------------------------------------

  /**
   * Returns the contrast ratio of two colours, from 1 (the same) to 21 (black and white), as WCAG 2
   * defines it.
   *
   * @param a an RGB colour
   * @param b another
   * @return the ratio
   */
  static double contrast(int a, int b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  /**
   * Returns the relative luminance of a colour, from 0 (black) to 1 (white).
   *
   * @param rgb the colour
   * @return its luminance
   */
  static double luminance(int rgb) {
    return 0.2126 * linear(rgb >> 16) + 0.7152 * linear(rgb >> 8) + 0.0722 * linear(rgb);
  }

  /**
   * Returns a component of a colour in linear light, from 0 to 1.
   *
   * @param component the component, in its low 8 bits
   * @return the linear value
   */
  static double linear(int component) {
    double c = (component & 0xFF) / 255.0;
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private static int mix(int from, int to, double amount) {
    double t = Math.min(1, amount);
    int r = (int) Math.round(((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
    int g = (int) Math.round(((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
    int b = (int) Math.round((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
    return (r << 16) | (g << 8) | b;
  }

  /**
   * A colour of the palette.
   *
   * @param rgb the 24-bit colour, or {@code -1} for the terminal's own
   * @param basic its closest standard colour, an SGR foreground code (30 to 37, 90 to 97)
   */
  record Color(int rgb, int basic) {
    /** The terminal's own foreground. */
    static final Color NONE = new Color(-1, 39);
  }
}
