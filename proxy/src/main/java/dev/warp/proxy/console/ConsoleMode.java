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

import java.io.Console;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

/**
 * What the console may write: colours (and how many), cursor tricks (the prompt that log lines
 * print above, the status line), and Unicode symbols. Decided once, at startup, from the
 * environment.
 *
 * <p>The rules, in order:
 *
 * <ol>
 *   <li>{@code WARP_COLOR=never} (or {@code off}) turns colours off and {@code always} (or {@code
 *       on}) turns them on, whatever the output. {@code 16}, {@code 256} and {@code truecolor}
 *       force that depth.
 *   <li>Otherwise a non-empty {@code NO_COLOR} turns them off (<a
 *       href="https://no-color.org">no-color.org</a>).
 *   <li>Otherwise ({@code auto}) colours are on when standard input and output are a terminal whose
 *       {@code TERM} is not {@code dumb}, and on a known panel (Pterodactyl, Pelican), whose web
 *       console renders them. Pipes, files, {@code docker logs} without a TTY and systemd get plain
 *       text.
 *   <li>The depth: 24-bit when {@code COLORTERM} says so, in Windows Terminal and on panels
 *       (xterm.js), 256 colours when {@code TERM} has {@code 256}, else the 16 standard colours.
 *   <li>The prompt and the status line need a real interactive terminal: standard input and output
 *       both a TTY, {@code TERM} not {@code dumb}, and no panel. Panels give the server a TTY, but
 *       their web console sends whole lines and cannot redraw a prompt. {@code WARP_CONSOLE=plain}
 *       turns them off on any other host of that kind; {@code interactive} keeps them on a panel.
 * </ol>
 *
 * @param colors the colours of the output
 * @param interactive whether the prompt and the status line may move the cursor
 * @param tty whether standard input and output are a terminal, which echoes the commands typed
 * @param panel the panel detected, or {@code null}
 * @param unicode whether the output encoding can show symbols such as {@code ●} and {@code ─}
 * @param warning a setting that could not be read and was ignored, to log once logging works
 */
public record ConsoleMode(
    ColorDepth colors,
    boolean interactive,
    boolean tty,
    @Nullable String panel,
    boolean unicode,
    @Nullable String warning) {

  /** The variable that overrides colour detection: always, never, auto, 16, 256 or truecolor. */
  public static final String ENV_COLOR = "WARP_COLOR";

  /** The variable that overrides prompt and status line detection: interactive, plain or auto. */
  public static final String ENV_CONSOLE = "WARP_CONSOLE";

  /** The informal standard that turns colours off when set to anything but the empty string. */
  public static final String ENV_NO_COLOR = "NO_COLOR";

  /**
   * Returns this mode without the prompt and the status line: for a terminal JLine cannot open.
   *
   * @return the mode, not interactive
   */
  public ConsoleMode withoutPrompt() {
    return new ConsoleMode(colors, false, tty, panel, unicode, warning);
  }

  /** Plain text without cursor tricks: for tests, and whenever nothing better is known. */
  public static final ConsoleMode PLAIN =
      new ConsoleMode(ColorDepth.NONE, false, false, null, false, null);

  /** Pterodactyl's panel sets both of these in the server's environment; Pelican the first. */
  private static final String[] PANEL_VARIABLES = {"P_SERVER_UUID", "P_SERVER_LOCATION"};

  /**
   * Decides the mode of this process: its environment, whether {@link System#console()} is a
   * terminal (standard input and output both a TTY), and the encoding of standard output.
   *
   * @return the mode
   */
  // System.console() is null when the JDK runs without a console implementation (-Djdk.console).
  @SuppressWarnings("SystemConsoleNull")
  public static ConsoleMode detect() {
    Console console = System.console();
    String encoding = System.getProperty("stdout.encoding");
    Charset charset;
    try {
      charset = encoding == null ? Charset.defaultCharset() : Charset.forName(encoding);
    } catch (RuntimeException e) {
      charset = Charset.defaultCharset();
    }
    return detect(System::getenv, console != null && console.isTerminal(), charset);
  }

  /**
   * Decides the mode from explicit inputs; see the class description for the rules.
   *
   * @param env reads an environment variable, {@code null} when unset
   * @param terminal whether standard input and output are both a terminal
   * @param charset the encoding of standard output
   * @return the mode
   */
  public static ConsoleMode detect(
      UnaryOperator<@Nullable String> env, boolean terminal, Charset charset) {
    String term = lower(env.apply("TERM"));
    boolean dumb = term.equals("dumb");
    String panel = panel(env);
    String warning = null;

    String colorSetting = lower(env.apply(ENV_COLOR));
    ColorDepth detected = depth(env, term, panel);
    ColorDepth colors =
        switch (colorSetting) {
          case "never", "off", "false", "0" -> ColorDepth.NONE;
          case "always", "on", "true", "1" -> detected;
          case "16" -> ColorDepth.ANSI_16;
          case "256" -> ColorDepth.ANSI_256;
          case "truecolor", "24bit" -> ColorDepth.TRUE_COLOR;
          default -> {
            if (!colorSetting.isEmpty() && !colorSetting.equals("auto")) {
              warning =
                  ENV_COLOR + "=" + env.apply(ENV_COLOR) + " is not always, never or auto: auto";
            }
            boolean noColor = !orEmpty(env.apply(ENV_NO_COLOR)).isEmpty();
            boolean shows = (terminal && !dumb) || panel != null;
            yield noColor || !shows ? ColorDepth.NONE : detected;
          }
        };

    String consoleSetting = lower(env.apply(ENV_CONSOLE));
    boolean interactive =
        switch (consoleSetting) {
          case "plain" -> false;
          case "interactive" -> terminal && !dumb;
          default -> {
            if (!consoleSetting.isEmpty() && !consoleSetting.equals("auto") && warning == null) {
              warning =
                  ENV_CONSOLE
                      + "="
                      + env.apply(ENV_CONSOLE)
                      + " is not interactive, plain or auto: auto";
            }
            yield terminal && !dumb && panel == null;
          }
        };
    boolean unicode = charset.equals(StandardCharsets.UTF_8);
    return new ConsoleMode(colors, interactive, terminal, panel, unicode, warning);
  }

  /**
   * Returns the panel the environment shows, or {@code null}.
   *
   * @param env reads an environment variable
   * @return {@code "Pterodactyl/Pelican"} or {@code null}
   */
  static @Nullable String panel(UnaryOperator<@Nullable String> env) {
    for (String variable : PANEL_VARIABLES) {
      if (!orEmpty(env.apply(variable)).isBlank()) {
        return "Pterodactyl/Pelican";
      }
    }
    return null;
  }

  /** The depth a colour output supports, whether or not colours end up on. */
  private static ColorDepth depth(
      UnaryOperator<@Nullable String> env, String term, @Nullable String panel) {
    String colorTerm = lower(env.apply("COLORTERM"));
    boolean screen = term.startsWith("screen") && env.apply("TMUX") == null;
    if (!screen && colorTerm.equals("truecolor")
        || colorTerm.equals("24bit")
        || term.endsWith("-direct")
        || env.apply("WT_SESSION") != null
        || panel != null) {
      return ColorDepth.TRUE_COLOR;
    }
    if (term.contains("256")) {
      return ColorDepth.ANSI_256;
    }
    return ColorDepth.ANSI_16;
  }

  /**
   * Returns whether standard output interprets escape codes without JLine's help: anywhere but a
   * Windows console, and there in Windows Terminal, ConEmu, ANSICON, or a terminal that sets {@code
   * TERM} (mintty, MSYS2, Cygwin).
   *
   * @param env reads an environment variable
   * @return {@code false} for a bare Windows console
   */
  public static boolean writesEscapes(UnaryOperator<@Nullable String> env) {
    if (!System.getProperty("os.name", "").startsWith("Windows")) {
      return true;
    }
    return env.apply("WT_SESSION") != null
        || "ON".equalsIgnoreCase(env.apply("ConEmuANSI"))
        || env.apply("ANSICON") != null
        || !orEmpty(env.apply("TERM")).isBlank();
  }

  private static String lower(@Nullable String value) {
    return orEmpty(value).strip().toLowerCase(Locale.ROOT);
  }

  private static String orEmpty(@Nullable String value) {
    return value == null ? "" : value;
  }
}
