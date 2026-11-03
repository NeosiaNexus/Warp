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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("Theme")
class ThemeTest {

  /** Common dark backgrounds: black, VS Code, Catppuccin, One Dark, Nord. */
  private static final int[] DARK_BACKGROUNDS = {0x000000, 0x1E1E1E, 0x1E1E2E, 0x282C34, 0x2E3440};

  /** Common light backgrounds: white, light grey, Solarized light, Catppuccin Latte. */
  private static final int[] LIGHT_BACKGROUNDS = {0xFFFFFF, 0xF5F5F5, 0xFDF6E3, 0xEFF1F5};

  /** The pairs of styles that must stay apart for every eye. */
  private static final List<Style[]> DISTINCT =
      List.of(
          new Style[] {Style.GOOD, Style.BAD},
          new Style[] {Style.ERROR, Style.INFO},
          new Style[] {Style.ERROR, Style.WARN},
          new Style[] {Style.WARN, Style.INFO},
          new Style[] {Style.PLAYER, Style.SERVER},
          new Style[] {Style.NUMBER, Style.SERVER});

  /** Machado, Oliveira and Fernandes (2009), severity 1, in linear RGB. */
  private static final double[][][] DEFICIENCIES = {
    {
      {0.152286, 1.052583, -0.204868},
      {0.114503, 0.786281, 0.099216},
      {-0.003882, -0.048116, 1.051998}
    },
    {
      {0.367322, 0.860646, -0.227968},
      {0.280085, 0.672501, 0.047413},
      {-0.011820, 0.042940, 0.968881}
    },
    {
      {1.255528, -0.076749, -0.178779},
      {-0.078411, 0.930809, 0.147602},
      {0.004733, 0.691367, 0.303900}
    }
  };

  private static final String[] DEFICIENCY_NAMES = {"protanopia", "deuteranopia", "tritanopia"};

  @Nested
  @DisplayName("contrast")
  class Contrast {

    @ParameterizedTest(name = "{0}")
    @EnumSource(Theme.class)
    @DisplayName("should keep every style at 4.5:1 or more on the common backgrounds of its kind")
    void shouldKeepContrast(Theme theme) {
      // given
      int[] backgrounds = theme.light() ? LIGHT_BACKGROUNDS : DARK_BACKGROUNDS;

      for (Style style : Style.values()) {
        int rgb = theme.color(style).rgb();
        if (rgb < 0) {
          continue;
        }
        for (int background : backgrounds) {
          // when
          double contrast = Theme.contrast(rgb, background);

          // then
          assertTrue(
              contrast >= Theme.MIN_CONTRAST,
              String.format(
                  Locale.ROOT,
                  "%s %s #%06X on #%06X: %.2f:1",
                  theme,
                  style,
                  rgb,
                  background,
                  contrast));
        }
      }
    }

    @Test
    @DisplayName("should compute WCAG's ratio: 21 for black on white, 1 for a colour on itself")
    void shouldComputeTheWcagRatio() {
      // given
      int black = 0x000000;
      int white = 0xFFFFFF;

      // when
      double extreme = Theme.contrast(black, white);
      double same = Theme.contrast(0x777777, 0x777777);

      // then
      assertEquals(21.0, extreme, 0.01);
      assertEquals(1.0, same, 0.0001);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Theme.class)
    @DisplayName("should make the colours of command replies readable on the theme's background")
    void shouldMakeOtherColoursReadable(Theme theme) {
      // given Minecraft's aqua, green, yellow and dark grey, written for its dark chat
      int[] colors = {0x55FFFF, 0x55FF55, 0xFFFF55, 0x555555, 0xFF5555};

      for (int color : colors) {
        // when
        int readable = theme.readable(color);

        // then
        int background = theme.light() ? 0xF5F5F5 : 0x1E1E1E;
        assertTrue(Theme.contrast(readable, background) >= Theme.MIN_CONTRAST, theme + " " + color);
      }
    }

    @Test
    @DisplayName("should leave a colour that already reads as it is")
    void shouldLeaveReadableColours() {
      // given
      int aqua = 0x55FFFF;

      // when
      int onDark = Theme.DARK.readable(aqua);
      int onLight = Theme.LIGHT.readable(aqua);

      // then
      assertEquals(aqua, onDark);
      assertNotEquals(aqua, onLight);
    }
  }

  @Nested
  @DisplayName("colour blindness")
  class ColourBlindness {

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = Theme.class,
        names = {"COLORBLIND_DARK", "COLORBLIND_LIGHT"})
    @DisplayName("should keep what must be told apart apart under every common deficiency")
    void shouldStayDistinct(Theme theme) {
      for (Style[] pair : DISTINCT) {
        for (int i = 0; i < DEFICIENCIES.length; i++) {
          // given
          double[] a = lab(simulate(theme.color(pair[0]).rgb(), DEFICIENCIES[i]));
          double[] b = lab(simulate(theme.color(pair[1]).rgb(), DEFICIENCIES[i]));

          // when
          double difference =
              Math.sqrt(square(a[0] - b[0]) + square(a[1] - b[1]) + square(a[2] - b[2]));

          // then
          assertTrue(
              difference >= 12,
              String.format(
                  Locale.ROOT,
                  "%s: %s and %s look alike under %s (delta E %.1f)",
                  theme,
                  pair[0],
                  pair[1],
                  DEFICIENCY_NAMES[i],
                  difference));
        }
      }
    }

    @Test
    @DisplayName("should never tell success from failure by red against green")
    void shouldAvoidRedAgainstGreen() {
      // given
      int good = Theme.COLORBLIND_DARK.color(Style.GOOD).rgb();

      // when
      int green = (good >> 8) & 0xFF;
      int blue = good & 0xFF;

      // then
      assertTrue(blue > green, "the colour-blind GOOD is blue, not green");
    }
  }

  @Nested
  @DisplayName("choice")
  class Choice {

    @Test
    @DisplayName("should follow the background for auto and colorblind, and not for dark and light")
    void shouldFollowTheBackground() {
      assertEquals(Theme.LIGHT, Theme.of("auto", true));
      assertEquals(Theme.DARK, Theme.of("", false));
      assertEquals(Theme.COLORBLIND_LIGHT, Theme.of("colorblind", true));
      assertEquals(Theme.COLORBLIND_DARK, Theme.of("Colorblind", false));
      assertEquals(Theme.DARK, Theme.of("dark", true));
      assertEquals(Theme.LIGHT, Theme.of("light", false));
      assertNull(Theme.of("solarized", false));
    }
  }

  // ---------------------------------------------------------------------------
  // Colour science
  // ---------------------------------------------------------------------------

  private static double[] simulate(int rgb, double[][] matrix) {
    double[] linear = {Theme.linear(rgb >> 16), Theme.linear(rgb >> 8), Theme.linear(rgb)};
    double[] out = new double[3];
    for (int i = 0; i < 3; i++) {
      out[i] =
          Math.clamp(
              matrix[i][0] * linear[0] + matrix[i][1] * linear[1] + matrix[i][2] * linear[2], 0, 1);
    }
    return out;
  }

  /** CIE L*a*b* (D65) of a colour in linear RGB. */
  private static double[] lab(double[] linear) {
    double x = 0.4124 * linear[0] + 0.3576 * linear[1] + 0.1805 * linear[2];
    double y = 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    double z = 0.0193 * linear[0] + 0.1192 * linear[1] + 0.9505 * linear[2];
    double fx = f(x / 0.95047);
    double fy = f(y);
    double fz = f(z / 1.08883);
    return new double[] {116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
  }

  private static double f(double t) {
    return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116;
  }

  private static double square(double value) {
    return value * value;
  }
}
