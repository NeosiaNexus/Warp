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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ConsoleMode")
class ConsoleModeTest {

  private static ConsoleMode detect(boolean terminal, String... variables) {
    Map<String, String> env = new HashMap<>();
    for (int i = 0; i < variables.length; i += 2) {
      env.put(variables[i], variables[i + 1]);
    }
    return ConsoleMode.detect(env::get, terminal, StandardCharsets.UTF_8);
  }

  @Nested
  @DisplayName("colours, automatically")
  class Auto {

    @Test
    @DisplayName("are on for a terminal")
    void terminal() {
      assertEquals(ColorDepth.ANSI_16, detect(true, "TERM", "xterm").colors());
    }

    @Test
    @DisplayName("are off for a pipe, a file or docker logs without a TTY")
    void pipe() {
      assertEquals(ColorDepth.NONE, detect(false, "TERM", "xterm-256color").colors());
    }

    @Test
    @DisplayName("are off for a dumb terminal")
    void dumb() {
      assertEquals(ColorDepth.NONE, detect(true, "TERM", "dumb").colors());
    }

    @Test
    @DisplayName("are off with NO_COLOR, even on a terminal")
    void noColor() {
      assertEquals(ColorDepth.NONE, detect(true, "TERM", "xterm", "NO_COLOR", "1").colors());
    }

    @Test
    @DisplayName("ignore an empty NO_COLOR, as no-color.org says")
    void emptyNoColor() {
      assertEquals(ColorDepth.ANSI_16, detect(true, "TERM", "xterm", "NO_COLOR", "").colors());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"P_SERVER_UUID", "P_SERVER_LOCATION"})
    @DisplayName("are 24-bit on a Pterodactyl or Pelican panel, which has no TTY to detect")
    void panel(String variable) {
      ConsoleMode mode = detect(false, variable, "d3adb33f");

      assertEquals(ColorDepth.TRUE_COLOR, mode.colors());
      assertEquals("Pterodactyl/Pelican", mode.panel());
    }
  }

  @Nested
  @DisplayName("WARP_COLOR")
  class Override {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"always", "on", "ALWAYS"})
    @DisplayName("turns colours on for a pipe")
    void always(String value) {
      assertEquals(ColorDepth.ANSI_16, detect(false, "WARP_COLOR", value).colors());
    }

    @Test
    @DisplayName("wins over NO_COLOR")
    void winsOverNoColor() {
      assertEquals(
          ColorDepth.ANSI_16, detect(false, "WARP_COLOR", "always", "NO_COLOR", "1").colors());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"never", "off"})
    @DisplayName("turns colours off on a terminal and a panel")
    void never(String value) {
      assertEquals(
          ColorDepth.NONE,
          detect(true, "WARP_COLOR", value, "COLORTERM", "truecolor", "P_SERVER_UUID", "x")
              .colors());
    }

    @Test
    @DisplayName("forces a depth")
    void depths() {
      assertEquals(ColorDepth.ANSI_16, detect(false, "WARP_COLOR", "16").colors());
      assertEquals(ColorDepth.ANSI_256, detect(false, "WARP_COLOR", "256").colors());
      assertEquals(ColorDepth.TRUE_COLOR, detect(false, "WARP_COLOR", "truecolor").colors());
    }

    @Test
    @DisplayName("falls back to auto with a warning when it reads nothing it knows")
    void unknown() {
      ConsoleMode mode = detect(true, "WARP_COLOR", "rainbow", "TERM", "xterm");

      assertEquals(ColorDepth.ANSI_16, mode.colors());
      assertNotNull(mode.warning());
      assertTrue(mode.warning().contains("rainbow"));
    }
  }

  @Nested
  @DisplayName("colour depth")
  class Depth {

    @Test
    @DisplayName("is 24-bit when COLORTERM says so, and in Windows Terminal")
    void trueColor() {
      assertEquals(ColorDepth.TRUE_COLOR, detect(true, "COLORTERM", "truecolor").colors());
      assertEquals(ColorDepth.TRUE_COLOR, detect(true, "COLORTERM", "24bit").colors());
      assertEquals(ColorDepth.TRUE_COLOR, detect(true, "WT_SESSION", "abc").colors());
    }

    @Test
    @DisplayName("is never 24-bit inside screen, whatever COLORTERM it inherited")
    void screen() {
      assertEquals(
          ColorDepth.ANSI_256,
          detect(true, "TERM", "screen-256color", "COLORTERM", "truecolor").colors());
      assertEquals(
          ColorDepth.TRUE_COLOR,
          detect(true, "TERM", "screen-256color", "COLORTERM", "truecolor", "TMUX", "/tmp/t")
              .colors());
    }

    @Test
    @DisplayName("is 256 colours for a 256-colour TERM")
    void indexed() {
      assertEquals(ColorDepth.ANSI_256, detect(true, "TERM", "xterm-256color").colors());
    }

    @Test
    @DisplayName("is the 16 standard colours otherwise")
    void basic() {
      assertEquals(ColorDepth.ANSI_16, detect(true, "TERM", "vt100").colors());
    }
  }

  @Nested
  @DisplayName("the prompt and the status line")
  class Interactive {

    @Test
    @DisplayName("need a real terminal")
    void terminal() {
      assertTrue(detect(true, "TERM", "xterm").interactive());
      assertFalse(detect(false, "TERM", "xterm").interactive());
    }

    @Test
    @DisplayName("are off on a panel, though it gives the server a TTY")
    void panel() {
      ConsoleMode mode = detect(true, "TERM", "xterm", "P_SERVER_UUID", "x");

      assertFalse(mode.interactive());
      assertTrue(mode.tty());
    }

    @Test
    @DisplayName("are off for a dumb terminal")
    void dumb() {
      assertFalse(detect(true, "TERM", "dumb").interactive());
    }

    @Test
    @DisplayName("are off with WARP_CONSOLE=plain, and kept on a panel with interactive")
    void override() {
      assertFalse(detect(true, "WARP_CONSOLE", "plain").interactive());
      assertTrue(detect(true, "WARP_CONSOLE", "interactive", "P_SERVER_UUID", "x").interactive());
      assertFalse(detect(false, "WARP_CONSOLE", "interactive").interactive());
    }

    @Test
    @DisplayName("are separate from colours: a panel gets colours without cursor tricks")
    void separate() {
      ConsoleMode mode = detect(true, "P_SERVER_UUID", "x");

      assertTrue(mode.colors().colored());
      assertFalse(mode.interactive());
    }
  }

  @Test
  @DisplayName("uses Unicode symbols only for a UTF-8 output")
  void unicode() {
    assertTrue(ConsoleMode.detect(_ -> null, true, StandardCharsets.UTF_8).unicode());
    assertFalse(ConsoleMode.detect(_ -> null, true, StandardCharsets.US_ASCII).unicode());
  }

  @Test
  @DisplayName("has no warning when every setting is understood, the new ones included")
  void noWarningForNewSettings() {
    assertNull(detect(true, "WARP_THEME", "light", "WARP_HYPERLINKS", "never").warning());
  }

  @Nested
  @DisplayName("the theme")
  class Themes {

    @Test
    @DisplayName("is dark by default, and asks the terminal when it is interactive")
    void darkByDefault() {
      // given an interactive terminal that says nothing of its background
      ConsoleMode mode = detect(true, "TERM", "xterm-256color");

      // when
      Theme theme = mode.theme();

      // then
      assertEquals(Theme.DARK, theme);
      assertTrue(mode.probeBackground());
    }

    @Test
    @DisplayName("follows COLORFGBG, without asking the terminal")
    void followsColorFgBg() {
      // given
      ConsoleMode light = detect(true, "TERM", "xterm", "COLORFGBG", "0;15");
      ConsoleMode dark = detect(true, "TERM", "xterm", "COLORFGBG", "15;default;0");

      // then
      assertEquals(Theme.LIGHT, light.theme());
      assertFalse(light.probeBackground());
      assertEquals(Theme.DARK, dark.theme());
    }

    @Test
    @DisplayName("is what WARP_THEME says, colour-blind on the background found")
    void followsWarpTheme() {
      assertEquals(Theme.LIGHT, detect(true, "TERM", "xterm", "WARP_THEME", "light").theme());
      assertFalse(detect(true, "TERM", "xterm", "WARP_THEME", "dark").probeBackground());
      assertEquals(
          Theme.COLORBLIND_LIGHT,
          detect(true, "TERM", "xterm", "WARP_THEME", "colorblind", "COLORFGBG", "0;7").theme());
    }

    @Test
    @DisplayName("takes the terminal's answer, keeping colour blindness")
    void takesTheAnswer() {
      // given
      ConsoleMode mode = detect(true, "TERM", "xterm", "WARP_THEME", "colorblind");

      // when
      ConsoleMode settled = mode.withBackground(true);

      // then
      assertEquals(Theme.COLORBLIND_LIGHT, settled.theme());
      assertFalse(settled.probeBackground());
    }

    @Test
    @DisplayName("warns about an unknown theme and uses auto")
    void warnsAboutUnknownThemes() {
      // given
      ConsoleMode mode = detect(true, "TERM", "xterm", "WARP_THEME", "solarized");

      // then
      assertEquals(Theme.DARK, mode.theme());
      assertNotNull(mode.warning());
      assertTrue(mode.warning().contains("WARP_THEME=solarized"));
    }

    @Test
    @DisplayName("never asks a pipe, which cannot answer")
    void neverAsksAPipe() {
      assertFalse(detect(false, "TERM", "xterm", "WARP_COLOR", "always").probeBackground());
    }
  }

  @Nested
  @DisplayName("links")
  class Links {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "WT_SESSION=1",
          "TERM_PROGRAM=iTerm.app",
          "TERM_PROGRAM=WezTerm",
          "TERM_PROGRAM=vscode",
          "VTE_VERSION=7200",
          "KITTY_WINDOW_ID=1",
          "KONSOLE_VERSION=230804"
        })
    @DisplayName("are on in terminals known to show them")
    void onInKnownTerminals(String variable) {
      // given
      String[] pair = variable.split("=", 2);

      // when
      ConsoleMode mode = detect(true, "TERM", "xterm-256color", pair[0], pair[1]);

      // then
      assertTrue(mode.hyperlinks(), variable);
    }

    @Test
    @DisplayName("are off in an unknown terminal, an old VTE, tmux, screen, a panel and a pipe")
    void offElsewhere() {
      assertFalse(detect(true, "TERM", "xterm").hyperlinks());
      assertFalse(detect(true, "TERM", "xterm", "VTE_VERSION", "4600").hyperlinks());
      assertFalse(detect(true, "TERM", "tmux-256color", "TERM_PROGRAM", "iTerm.app").hyperlinks());
      assertFalse(detect(true, "TERM", "xterm", "WT_SESSION", "1", "TMUX", "/tmp/t").hyperlinks());
      assertFalse(detect(true, "TERM", "screen", "WT_SESSION", "1").hyperlinks());
      assertFalse(detect(true, "P_SERVER_UUID", "x", "WT_SESSION", "1").hyperlinks());
      assertFalse(detect(false, "WT_SESSION", "1", "WARP_COLOR", "always").hyperlinks());
    }

    @Test
    @DisplayName("follow WARP_HYPERLINKS, but never without colours")
    void followTheVariable() {
      assertTrue(detect(true, "TERM", "xterm", "WARP_HYPERLINKS", "always").hyperlinks());
      assertFalse(detect(true, "WT_SESSION", "1", "WARP_HYPERLINKS", "never").hyperlinks());
      assertFalse(detect(true, "WARP_HYPERLINKS", "always", "WARP_COLOR", "never").hyperlinks());
    }
  }

  @Test
  @DisplayName("has no warning when every setting is understood")
  void noWarning() {
    assertNull(detect(true, "WARP_COLOR", "auto", "WARP_CONSOLE", "auto").warning());
  }
}
