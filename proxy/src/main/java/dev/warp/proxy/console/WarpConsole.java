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

import dev.warp.api.command.CommandSource;
import dev.warp.proxy.command.WarpCommandManager;

import java.io.BufferedReader;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.tree.CommandNode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads commands at the console and runs them with the proxy's dispatcher, whatever commands it
 * holds.
 *
 * <p>On an interactive terminal, through JLine: tab completion with tooltips, highlighting, a hint
 * of the arguments still to type, history kept across restarts, and log lines printed above the
 * line being typed. Ctrl+C, or Ctrl+D on an empty line, stops the proxy gracefully.
 *
 * <p>Elsewhere (a panel, {@code docker attach}, a pipe), standard input is read line by line,
 * without any terminal handling. When it ends (closed, or {@code /dev/null} as under systemd or
 * {@code docker run} without {@code -i}), the console stops reading, once and quietly: the proxy
 * keeps running, and nothing spins. No error of the console ever stops the proxy.
 */
public final class WarpConsole implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(WarpConsole.class);

  /** Logs the commands typed: the log file keeps them, the console shows them when no TTY does. */
  private static final org.apache.logging.log4j.Logger TYPED =
      LogManager.getLogger("dev.warp.proxy.console.Console");

  private final WarpCommandManager commands;
  private final CommandSource source;
  private final Path historyFile;
  private final Runnable shutdown;
  private final InputStream input;

  private volatile boolean running;
  private volatile @Nullable LineReader reader;

  /**
   * Creates a console.
   *
   * @param commands the commands it runs
   * @param source who runs them: the console, with every permission
   * @param historyFile where the history of an interactive console is kept
   * @param shutdown stops the proxy gracefully, from any thread, without waiting
   */
  public WarpConsole(
      WarpCommandManager commands, CommandSource source, Path historyFile, Runnable shutdown) {
    this(commands, source, historyFile, shutdown, System.in);
  }

  /**
   * Creates a console that reads {@code input} when it has no terminal.
   *
   * @param commands the commands it runs
   * @param source who runs them
   * @param historyFile where the history of an interactive console is kept
   * @param shutdown stops the proxy gracefully, from any thread, without waiting
   * @param input standard input
   */
  WarpConsole(
      WarpCommandManager commands,
      CommandSource source,
      Path historyFile,
      Runnable shutdown,
      InputStream input) {
    this.commands = commands;
    this.source = source;
    this.historyFile = historyFile;
    this.shutdown = shutdown;
    this.input = input;
  }

  /** Starts reading commands, on a thread of its own. */
  public void start() {
    ConsoleOutput output = ConsoleOutput.get();
    Terminal terminal = output == null ? null : output.terminal();
    running = true;
    Runnable loop =
        output != null && terminal != null ? () -> interactive(output, terminal) : this::plain;
    var _ = Thread.ofPlatform().name("warp-console-input").daemon().start(loop);
  }

  /** Stops reading commands; a command already running finishes. */
  @Override
  public void close() {
    running = false;
    LineReader current = reader;
    if (current != null) {
      saveHistory(current);
    }
  }

  // ---------------------------------------------------------------------------
  // Interactive terminal
  // ---------------------------------------------------------------------------

  private void interactive(ConsoleOutput output, Terminal terminal) {
    LineReader lineReader;
    try {
      lineReader = lineReader(terminal, output.mode());
    } catch (RuntimeException | IOError e) {
      logger.warn(
          "The interactive console could not start ({}): reading plain lines", e.toString());
      plain();
      return;
    }
    reader = lineReader;
    output.attach(lineReader);
    String prompt =
        new Styled(output.mode().colors(), Glyphs.of(output.mode().unicode()))
            .append(Style.ACCENT, Glyphs.of(output.mode().unicode()).arrow())
            .append(' ')
            .toString();
    try {
      while (running) {
        String line;
        try {
          line = lineReader.readLine(prompt);
        } catch (UserInterruptException e) {
          stopProxy("Ctrl+C");
          return;
        } catch (EndOfFileException e) {
          stopProxy("Ctrl+D");
          return;
        }
        // A paste of several lines runs each of them.
        for (String command : line.lines().toList()) {
          run(command);
        }
      }
    } catch (RuntimeException | IOError e) {
      // Velocity#882: a terminal error once shut the proxy down. Here it only ends the prompt.
      if (running) {
        logger.warn("The console stopped reading commands: {}", e.toString());
      }
    } finally {
      output.detach();
      saveHistory(lineReader);
      reader = null;
    }
  }

  private LineReader lineReader(Terminal terminal, ConsoleMode mode) {
    CommandLineSupport support = new CommandLineSupport(commands, source, mode.colors());
    DefaultParser parser = new DefaultParser();
    // A quote or a backslash is text: no line ever waits for its closing quote.
    parser.setEscapeChars(null);
    parser.setQuoteChars(new char[0]);
    parser.setEofOnUnclosedQuote(false);
    parser.setEofOnEscapedNewLine(false);
    LineReader lineReader =
        LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("Warp")
            .parser(parser)
            .completer(support)
            .highlighter(support)
            .variable(LineReader.HISTORY_FILE, historyFile)
            .variable(LineReader.HISTORY_SIZE, 1000)
            .variable(LineReader.HISTORY_FILE_SIZE, 2000)
            .variable(LineReader.SECONDARY_PROMPT_PATTERN, "")
            // "!" is text, not a history expansion.
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .option(LineReader.Option.INSERT_TAB, false)
            .option(LineReader.Option.BRACKETED_PASTE, true)
            .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
            .build();
    lineReader.setAutosuggestion(LineReader.SuggestionType.TAIL_TIP);
    return lineReader;
  }

  private void stopProxy(String key) {
    if (!running) {
      return;
    }
    running = false;
    logger.info("{} at the console: stopping Warp", key);
    shutdown.run();
  }

  private void saveHistory(LineReader lineReader) {
    try {
      lineReader.getHistory().save();
    } catch (IOException | RuntimeException e) {
      logger.debug("Cannot save the console history to {}", historyFile, e);
    }
  }

  // ---------------------------------------------------------------------------
  // Plain input
  // ---------------------------------------------------------------------------

  private void plain() {
    String encoding = System.getProperty("stdin.encoding");
    Charset charset;
    try {
      charset = encoding == null ? Charset.defaultCharset() : Charset.forName(encoding);
    } catch (RuntimeException e) {
      charset = Charset.defaultCharset();
    }
    try (BufferedReader lines = new BufferedReader(new InputStreamReader(input, charset))) {
      String line;
      while (running && (line = lines.readLine()) != null) {
        run(line);
      }
      if (running) {
        logger.debug("Standard input is closed: the console reads no more commands");
      }
    } catch (IOException | UncheckedIOException e) {
      logger.debug("The console stopped reading standard input", e);
    }
  }

  // ---------------------------------------------------------------------------
  // Commands
  // ---------------------------------------------------------------------------

  /**
   * Runs a command line: logs it, then hands it to the dispatcher, or says it is unknown.
   *
   * @param typed the line as typed, possibly with a leading {@code /}
   */
  void run(String typed) {
    String line = Ansi.clean(typed).replace('\t', ' ').strip();
    if (line.startsWith("/")) {
      line = line.substring(1).strip();
    }
    if (line.isEmpty() || !running) {
      return;
    }
    TYPED.info(LineFormatter.ECHO, "> {}", line);
    String command = line;
    String usage = commands.hasCommand(firstWord(line)) ? incompleteUsage(line) : null;
    if (usage != null) {
      source.sendMessage(
          Component.text("Incomplete command. Usage: ", NamedTextColor.RED)
              .append(Component.text(usage, NamedTextColor.GRAY)));
      return;
    }
    var _ =
        commands
            .execute(source, command)
            .whenComplete(
                (found, error) -> {
                  if (error != null) {
                    logger.error("Command {} failed", command, error);
                  } else if (!found) {
                    source.sendMessage(unknown(command));
                  }
                });
  }

  private @Nullable String incompleteUsage(String line) {
    try {
      return CommandInput.incompleteUsage(commands, source, line);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String firstWord(String line) {
    int space = line.indexOf(' ');
    return space < 0 ? line : line.substring(0, space);
  }

  /** {@code Unknown command "glsit". Did you mean glist? Commands: glist, send, server}. */
  private Component unknown(String line) {
    int space = line.indexOf(' ');
    String name = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
    List<String> names = new ArrayList<>();
    for (CommandNode<CommandSource> child : commands.root().getChildren()) {
      if (child.canUse(source)) {
        names.add(child.getName());
      }
    }
    names.sort(null);
    Component message = Component.text("Unknown command \"" + name + "\".", NamedTextColor.RED);
    String closest = CommandInput.closest(names, name);
    if (closest != null) {
      message =
          message
              .append(Component.text(" Did you mean ", NamedTextColor.GRAY))
              .append(Component.text(closest, NamedTextColor.AQUA))
              .append(Component.text("?", NamedTextColor.GRAY));
    }
    if (!names.isEmpty()) {
      message =
          message.append(
              Component.text(" Commands: " + String.join(", ", names), NamedTextColor.GRAY));
    }
    return message;
  }
}
