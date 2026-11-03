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

import java.util.regex.Pattern;

/** ANSI escape codes: colours at each depth, and their removal. */
public final class Ansi {

  /** Resets every attribute. */
  public static final String RESET = "\u001B[0m";

  /**
   * Control sequences (colours, cursor moves, erasures) and operating system commands (titles,
   * links): everything a terminal interprets rather than prints.
   */
  private static final Pattern ESCAPES =
      Pattern.compile(
          "\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-Z\\\\-_])");

  /** The six levels of each channel in the xterm 256-colour cube. */
  private static final int[] CUBE = {0, 95, 135, 175, 215, 255};

  private Ansi() {}

  /**
   * Removes every escape sequence, so that the text reads the same without a terminal.
   *
   * @param text the text, possibly coloured
   * @return the text without escape sequences; {@code text} itself when it has none
   */
  public static String strip(String text) {
    return text.indexOf('\u001B') < 0 ? text : ESCAPES.matcher(text).replaceAll("");
  }

  /**
   * Removes escape sequences and every control character but tab and line feed: what a player's
   * name, a kick reason or a plugin's message could use to forge log lines, move the cursor or hide
   * text from whoever reads the console or the file.
   *
   * @param text the text
   * @return the text without them; {@code text} itself when it has none
   */
  public static String clean(String text) {
    int length = text.length();
    int i = 0;
    while (i < length && !isControl(text.charAt(i))) {
      i++;
    }
    if (i == length) {
      return text;
    }
    String stripped = strip(text);
    StringBuilder out = new StringBuilder(stripped.length());
    for (int j = 0; j < stripped.length(); j++) {
      char c = stripped.charAt(j);
      if (!isControl(c)) {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static boolean isControl(char c) {
    return (c < 0x20 && c != '\t' && c != '\n') || (c >= 0x7F && c <= 0x9F);
  }

  /**
   * Returns the escape sequence that sets the foreground to a colour at a depth.
   *
   * @param rgb the colour, {@code 0xRRGGBB}
   * @param basic the SGR code of the closest of the 16 standard colours, such as 31 (red) or 96
   *     (bright cyan), for {@link ColorDepth#ANSI_16}
   * @param depth the depth
   * @return the parameters of the sequence, without {@code ESC [} and {@code m}; empty for {@link
   *     ColorDepth#NONE}
   */
  static String foreground(int rgb, int basic, ColorDepth depth) {
    return switch (depth) {
      case NONE -> "";
      case ANSI_16 -> Integer.toString(basic);
      case ANSI_256 -> "38;5;" + xterm256(rgb);
      case TRUE_COLOR ->
          "38;2;" + ((rgb >> 16) & 0xFF) + ";" + ((rgb >> 8) & 0xFF) + ";" + (rgb & 0xFF);
    };
  }

  /**
   * Returns the xterm 256-colour index closest to a colour: the nearest of the 6x6x6 cube and of
   * the 24 greys.
   *
   * @param rgb the colour, {@code 0xRRGGBB}
   * @return an index from 16 to 255
   */
  static int xterm256(int rgb) {
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;
    int ri = nearestCube(r);
    int gi = nearestCube(g);
    int bi = nearestCube(b);
    int cube = 16 + 36 * ri + 6 * gi + bi;
    int cubeDistance = distance(r, g, b, CUBE[ri], CUBE[gi], CUBE[bi]);
    int average = (r + g + b) / 3;
    int grey = Math.clamp(Math.round((average - 8) / 10f), 0, 23);
    int level = 8 + 10 * grey;
    int greyDistance = distance(r, g, b, level, level, level);
    return greyDistance < cubeDistance ? 232 + grey : cube;
  }

  private static int nearestCube(int channel) {
    int best = 0;
    for (int i = 1; i < CUBE.length; i++) {
      if (Math.abs(CUBE[i] - channel) < Math.abs(CUBE[best] - channel)) {
        best = i;
      }
    }
    return best;
  }

  private static int distance(int r1, int g1, int b1, int r2, int g2, int b2) {
    int dr = r1 - r2;
    int dg = g1 - g2;
    int db = b1 - b2;
    return dr * dr + dg * dg + db * db;
  }
}
