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
 *   <li>The theme: {@code WARP_THEME}, else {@code console.theme} of {@code warp.conf} ({@code
 *       auto}, {@code dark}, {@code light} or {@code colorblind}). {@code auto} and {@code
 *       colorblind} follow the background: light or dark as {@code COLORFGBG} says, else as the
 *       terminal answers an OSC 11 query (interactive terminals only, with a short time-out), else
 *       dark, the safe default of terminals and panels.
 *   <li>Clickable links (OSC 8): on terminals known to show them (Windows Terminal, iTerm2,
 *       WezTerm, kitty, Ghostty, foot, Alacritty, VS Code, VTE terminals such as GNOME Terminal,
 *       Konsole), never in a pipe, a panel, {@code screen} or {@code tmux}, which may print or eat
 *       the sequence. {@code WARP_HYPERLINKS=always} or {@code never} decides instead; links never
 *       show without colours.
 * </ol>
 *
 * @param colors the colours of the output
 * @param interactive whether the prompt and the status line may move the cursor
 * @param tty whether standard input and output are a terminal, which echoes the commands typed
 * @param panel the panel detected, or {@code null}
 * @param unicode whether the output encoding can show symbols such as {@code ●} and {@code ─}
 * @param theme the colours of each style
 * @param probeBackground whether the background is a guess that an OSC 11 query may settle
 * @param hyperlinks whether the output shows clickable links
 * @param warning a setting that could not be read and was ignored, to log once logging works
 */
public record ConsoleMode(
    ColorDepth colors,
    boolean interactive,
    boolean tty,
    @Nullable String panel,
    boolean unicode,
    Theme theme,
    boolean probeBackground,
    boolean hyperlinks,
    @Nullable String warning) {

  /** The variable that overrides colour detection: always, never, auto, 16, 256 or truecolor. */
  public static final String ENV_COLOR = "WARP_COLOR";

  /** The variable that overrides prompt and status line detection: interactive, plain or auto. */
  public static final String ENV_CONSOLE = "WARP_CONSOLE";

  /** The informal standard that turns colours off when set to anything but the empty string. */
  public static final String ENV_NO_COLOR = "NO_COLOR";

  /** The variable that picks the theme: auto, dark, light or colorblind. */
  public static final String ENV_THEME = "WARP_THEME";

  /** The variable that overrides link detection: always, never or auto. */
  public static final String ENV_HYPERLINKS = "WARP_HYPERLINKS";

  /**
   * The system property that carries {@code console.theme} of {@code warp.conf}, read before
   * logging starts.
   */
  public static final String THEME_PROPERTY = "warp.console.theme";

  /**
   * Creates a mode in the dark theme, without links.
   *
   * @param colors the colours of the output
   * @param interactive whether the prompt and the status line may move the cursor
   * @param tty whether standard input and output are a terminal
   * @param panel the panel detected, or {@code null}
   * @param unicode whether the output encoding can show symbols
   * @param warning a setting that could not be read, or {@code null}
   */
  public ConsoleMode(
      ColorDepth colors,
      boolean interactive,
      boolean tty,
      @Nullable String panel,
      boolean unicode,
      @Nullable String warning) {
    this(colors, interactive, tty, panel, unicode, Theme.DARK, false, false, warning);
  }

  /**
   * Returns this mode without the prompt and the status line: for a terminal JLine cannot open.
   *
   * @return the mode, not interactive
   */
  public ConsoleMode withoutPrompt() {
    return new ConsoleMode(colors, false, tty, panel, unicode, theme, false, hyperlinks, warning);
  }

  /**
   * Returns this mode in the theme of a background the terminal told: the light or dark variant of
   * the same kind (colour-blind or not).
   *
   * @param light whether the background is light
   * @return the mode, its background settled
   */
  public ConsoleMode withBackground(boolean light) {
    Theme settled = Theme.of(theme.colorblind() ? "colorblind" : "auto", light);
    return new ConsoleMode(
        colors,
        interactive,
        tty,
        panel,
        unicode,
        settled == null ? theme : settled,
        false,
        hyperlinks,
        warning);
  }

  /**
   * Returns this mode as plain text, without colour, link or cursor trick: for a Windows console
   * that reads escape codes only once JLine turned their processing on.
   *
   * @return the mode, plain
   */
  public ConsoleMode plain() {
    return new ConsoleMode(
        ColorDepth.NONE, false, tty, panel, unicode, theme, false, false, warning);
  }

  /**
   * Returns what styled text may use on this output.
   *
   * @return the palette
   */
  Palette palette() {
    return Palette.of(colors, theme, hyperlinks);
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
    UnaryOperator<@Nullable String> env =
        name -> {
          String value = System.getenv(name);
          return value == null && name.equals(ENV_THEME)
              ? System.getProperty(THEME_PROPERTY)
              : value;
        };
    return detect(env, console != null && console.isTerminal(), charset);
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

    String themeSetting = lower(env.apply(ENV_THEME));
    Boolean light = lightBackground(env.apply("COLORFGBG"));
    Theme theme = Theme.of(themeSetting, Boolean.TRUE.equals(light));
    if (theme == null) {
      if (warning == null) {
        warning =
            ENV_THEME
                + "="
                + env.apply(ENV_THEME)
                + " is not auto, dark, light or colorblind: auto";
      }
      themeSetting = "auto";
      theme = Boolean.TRUE.equals(light) ? Theme.LIGHT : Theme.DARK;
    }
    boolean probe =
        light == null
            && interactive
            && colors.colored()
            && !themeSetting.equals("dark")
            && !themeSetting.equals("light");

    String linkSetting = lower(env.apply(ENV_HYPERLINKS));
    boolean hyperlinks =
        switch (linkSetting) {
          case "always", "on", "true", "1" -> true;
          case "never", "off", "false", "0" -> false;
          default -> {
            if (!linkSetting.isEmpty() && !linkSetting.equals("auto") && warning == null) {
              warning =
                  ENV_HYPERLINKS
                      + "="
                      + env.apply(ENV_HYPERLINKS)
                      + " is not always, never or auto: auto";
            }
            yield terminal && !dumb && panel == null && showsLinks(env, term);
          }
        };
    return new ConsoleMode(
        colors,
        interactive,
        terminal,
        panel,
        unicode,
        theme,
        probe,
        hyperlinks && colors.colored(),
        warning);
  }

  /**
   * Returns whether {@code COLORFGBG} ({@code fg;bg}, set by rxvt, Konsole, iTerm2 and others) says
   * the background is light: colours 7 and 9 to 15 are light, 0 to 6 and 8 dark.
   *
   * @param colorFgBg the variable, or {@code null}
   * @return whether the background is light, or {@code null} when the variable does not say
   */
  static @Nullable Boolean lightBackground(@Nullable String colorFgBg) {
    if (colorFgBg == null || colorFgBg.isBlank()) {
      return null;
    }
    String background = colorFgBg.substring(colorFgBg.lastIndexOf(';') + 1).strip();
    try {
      int color = Integer.parseInt(background);
      if (color < 0 || color > 15) {
        return null;
      }
      return color == 7 || color >= 9;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Returns whether the terminal is one known to show OSC 8 links. Multiplexers come first: inside
   * {@code screen} or {@code tmux} the outer terminal's variables still show, but the multiplexer
   * decides what reaches it.
   */
  private static boolean showsLinks(UnaryOperator<@Nullable String> env, String term) {
    if (term.startsWith("screen") || term.startsWith("tmux") || env.apply("TMUX") != null) {
      return false;
    }
    String program = orEmpty(env.apply("TERM_PROGRAM"));
    return switch (program) {
      case "iTerm.app", "WezTerm", "vscode", "ghostty", "Hyper", "Tabby", "rio", "WarpTerminal" ->
          true;
      default ->
          env.apply("WT_SESSION") != null
              || env.apply("KITTY_WINDOW_ID") != null
              || env.apply("ALACRITTY_WINDOW_ID") != null
              || version(env.apply("VTE_VERSION")) >= 5000
              || version(env.apply("KONSOLE_VERSION")) >= 201200
              || term.equals("xterm-kitty")
              || term.equals("xterm-ghostty")
              || term.equals("wezterm")
              || term.equals("alacritty")
              || term.startsWith("foot");
    };
  }

  private static int version(@Nullable String value) {
    try {
      return value == null ? -1 : Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      return -1;
    }
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
