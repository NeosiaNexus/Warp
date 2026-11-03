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

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOError;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.apache.logging.log4j.core.LogEvent;
import org.jline.reader.LineReader;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp;
import org.jline.utils.Status;
import org.jspecify.annotations.Nullable;

/**
 * The console's output: formats records on the logging thread, queues them in a {@link
 * ConsoleBuffer}, and writes them from a thread of its own, so that nothing a terminal does (a
 * paused Windows console, a slow SSH link, a panel reading late) can hold up the proxy.
 *
 * <p>On a real interactive terminal it also owns the JLine terminal: log lines print above the line
 * being typed (once a {@link WarpConsole} reads input) and a status line sits at the bottom. All
 * writes to the terminal happen on the writer thread or under the line reader's lock, so that they
 * never interleave. When the proxy stops, or at the first error of the terminal, the output falls
 * back to plain lines written straight to standard output: the last lines of a shutdown are never
 * lost to a terminal closing early.
 *
 * <p>One instance per process, shared by every {@link WarpConsoleAppender} (Log4j starts the new
 * appenders of a reconfiguration before stopping the old ones).
 */
public final class ConsoleOutput {

  /** How many lines may wait for the console before INFO lines are dropped. */
  static final int CAPACITY = 8192;

  /** How many more WARN and ERROR lines may wait before the logging thread does. */
  static final int RESERVE = 65_536;

  /** How many lines one write takes at most. */
  private static final int BATCH = 512;

  /** How often the status line is redrawn. */
  private static final long STATUS_INTERVAL_MILLIS = 1000;

  /** How long repeats of a line are counted before the console says so. */
  private static final long REPEAT_REPORT_MILLIS = 2000;

  /** How long stopping waits for the lines still queued. */
  private static final long DRAIN_TIMEOUT_MILLIS = 3000;

  private static final String STATUS_WIDGET = "warp-status";

  /** Turns off the bracketed paste mode a line reader turns on. */
  private static final String BRACKETED_PASTE_OFF = "\u001B[?2004l";

  private static final String DETACH_WIDGET = "warp-detach";

  private static @Nullable ConsoleOutput instance;
  private static int users;

  private final ConsoleMode mode;
  private final LineFormatter formatter;
  private final ConsoleBuffer buffer;
  private final PrintStream direct;
  private final @Nullable Terminal terminal;
  private final Thread writer;

  private volatile NameDirectory names = NameDirectory.NONE;
  private volatile @Nullable Supplier<String> statusText;
  private volatile @Nullable LineReader reader;
  private volatile boolean plain;
  private volatile boolean running = true;

  // Writer thread only.
  private @Nullable String lastKey;
  private int repeats;
  private long repeatsSince;
  private long droppedPending;
  private long nextStatus;
  private boolean statusShown;

  /**
   * Creates an output.
   *
   * @param mode what the console may write
   * @param terminal the JLine terminal of an interactive console, or {@code null}
   * @param direct standard output, for plain lines
   * @param buffer the queue
   */
  ConsoleOutput(
      ConsoleMode mode, @Nullable Terminal terminal, PrintStream direct, ConsoleBuffer buffer) {
    this.mode = terminal == null && mode.interactive() ? mode.withoutPrompt() : mode;
    this.formatter = new LineFormatter(this.mode, ZoneId.systemDefault());
    this.buffer = buffer;
    this.direct = direct;
    this.terminal = terminal;
    this.plain = terminal == null;
    this.writer = Thread.ofPlatform().name("warp-console").daemon().unstarted(this::writeLoop);
  }

  // ---------------------------------------------------------------------------
  // The process's instance
  // ---------------------------------------------------------------------------

  /**
   * Returns the process's output, creating it on first use from the environment: the mode {@link
   * ConsoleMode#detect() detected}, and a JLine terminal if it is interactive.
   *
   * @return the output, started
   */
  static synchronized ConsoleOutput acquire() {
    users++;
    ConsoleOutput output = instance;
    if (output == null) {
      ConsoleMode mode = ConsoleMode.detect();
      Terminal terminal = mode.interactive() ? openTerminal() : null;
      if (terminal == null
          && mode.colors().colored()
          && !ConsoleMode.writesEscapes(System::getenv)) {
        // A Windows console reads escape codes only once JLine turned their processing on.
        mode = ConsoleMode.PLAIN;
      }
      output =
          new ConsoleOutput(mode, terminal, standardOutput(), new ConsoleBuffer(CAPACITY, RESERVE));
      output.start();
      instance = output;
      ConsoleOutput started = output;
      // Before JLine's own hook closes the terminal: from the first moment of a shutdown, lines are
      // written as plain text, so that none is lost to a closed terminal.
      Runtime.getRuntime()
          .addShutdownHook(
              Thread.ofPlatform().name("warp-console-shutdown").unstarted(started::endInteractive));
    }
    return output;
  }

  /** Releases the output; the last user stops it. */
  static synchronized void release() {
    users--;
    ConsoleOutput output = instance;
    if (users <= 0 && output != null) {
      instance = null;
      users = 0;
      output.close();
    }
  }

  /**
   * Returns the process's output, or {@code null} when the console format is not text (JSON) or
   * logging is not Log4j's.
   *
   * @return the output, or {@code null}
   */
  public static synchronized @Nullable ConsoleOutput get() {
    return instance;
  }

  /**
   * Opens the JLine terminal of an interactive console: through Java's foreign function API (no
   * JNA, no JNI library), or {@code stty} where that is denied. Never a "dumb" terminal: without a
   * system terminal the console is plain.
   */
  private static @Nullable Terminal openTerminal() {
    try {
      return TerminalBuilder.builder()
          .name("Warp")
          .system(true)
          .providers("ffm,exec")
          .dumb(false)
          .build();
    } catch (Exception | LinkageError e) {
      return null;
    }
  }

  /** Standard output itself, not {@link System#out}, which the proxy sends to the log. */
  private static PrintStream standardOutput() {
    String encoding = System.getProperty("stdout.encoding");
    Charset charset;
    try {
      charset = encoding == null ? Charset.defaultCharset() : Charset.forName(encoding);
    } catch (RuntimeException e) {
      charset = Charset.defaultCharset();
    }
    return new PrintStream(
        new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16),
        false,
        charset);
  }

  // ---------------------------------------------------------------------------
  // Configuration by the proxy
  // ---------------------------------------------------------------------------

  /**
   * Returns what this console may write.
   *
   * @return the mode
   */
  public ConsoleMode mode() {
    return mode;
  }

  /**
   * Returns the JLine terminal, for the line reader.
   *
   * @return the terminal, or {@code null} when the console is not interactive
   */
  public @Nullable Terminal terminal() {
    return plain ? null : terminal;
  }

  /**
   * Sets who tells player and server names apart in messages.
   *
   * @param names the directory
   */
  public void names(NameDirectory names) {
    this.names = names;
  }

  /**
   * Sets the text of the status line, asked once a second while it shows.
   *
   * @param text renders the status line, or {@code null} for none
   */
  public void status(@Nullable Supplier<String> text) {
    this.statusText = text;
  }

  /**
   * Prints from now on above the line {@code reader} is reading.
   *
   * @param lineReader the line reader of the console's terminal
   */
  void attach(LineReader lineReader) {
    Terminal own = terminal;
    if (own == null || plain) {
      return;
    }
    lineReader.getWidgets().put(STATUS_WIDGET, this::drawStatus);
    lineReader.getWidgets().put(DETACH_WIDGET, () -> clearInput(lineReader));
    this.reader = lineReader;
  }

  /** Stops printing above the line being read: it is no longer read. */
  void detach() {
    this.reader = null;
  }

  /**
   * Switches to plain lines for good, the status line and the prompt erased: the proxy is stopping,
   * and the terminal may close at any time (JLine closes it from a shutdown hook of its own).
   */
  public void endInteractive() {
    if (!plain) {
      plain = true;
      writer.interrupt();
    }
  }

  // ---------------------------------------------------------------------------
  // Records
  // ---------------------------------------------------------------------------

  /**
   * Formats and queues a record; called by the appender on the logging thread.
   *
   * @param event the record
   */
  void submit(LogEvent event) {
    ConsoleLine line;
    try {
      line = formatter.format(event, names);
    } catch (RuntimeException e) {
      String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
      line = new ConsoleLine(event.getLevel(), Ansi.clean(String.valueOf(message)), null);
    }
    if (line != null) {
      var _ = buffer.offer(line);
    }
  }

  /** Starts the writer thread. */
  void start() {
    writer.start();
  }

  /** Writes what is queued, then stops the writer and restores the terminal. */
  void close() {
    running = false;
    endInteractive();
    writer.interrupt();
    try {
      writer.join(DRAIN_TIMEOUT_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    direct.flush();
    Terminal own = terminal;
    if (own != null) {
      try {
        own.close();
      } catch (Exception | IOError e) {
        // The terminal is going away with the process.
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Writer thread
  // ---------------------------------------------------------------------------

  private void writeLoop() {
    // The status line first, before any prompt: JLine sets its scroll region up best on a terminal
    // it has not drawn a prompt on yet.
    refreshStatus();
    List<ConsoleLine> batch = new ArrayList<>(BATCH);
    StringBuilder text = new StringBuilder(8192);
    while (running || buffer.size() > 0) {
      ConsoleLine first;
      try {
        first = running ? buffer.poll(250) : buffer.poll();
      } catch (InterruptedException e) {
        first = buffer.poll(); // woken up to switch to plain output, or to stop
      }
      if (plain && reader != null) {
        leaveTerminal();
      }
      long now = System.currentTimeMillis();
      if (first != null) {
        batch.add(first);
        var _ = buffer.drainTo(batch, BATCH - 1);
        for (ConsoleLine line : batch) {
          collapse(line, now, text);
        }
        batch.clear();
      }
      if (repeats > 0 && now - repeatsSince >= REPEAT_REPORT_MILLIS) {
        reportRepeats(text);
      }
      droppedPending += buffer.takeDropped();
      if (droppedPending > 0 && buffer.size() < CAPACITY / 4) {
        appendLine(text, droppedNotice(droppedPending));
        droppedPending = 0;
      }
      if (!text.isEmpty()) {
        write(text);
        text.setLength(0);
      }
      if (now >= nextStatus) {
        nextStatus = now + STATUS_INTERVAL_MILLIS;
        refreshStatus();
      }
      if (!running && first == null) {
        break;
      }
    }
    if (repeats > 0) {
      reportRepeats(text);
      write(text);
    }
    direct.flush();
  }

  /** Appends a line, or counts it when it repeats the previous one word for word. */
  private void collapse(ConsoleLine line, long now, StringBuilder text) {
    String key = line.repeatKey();
    if (key != null && key.equals(lastKey)) {
      if (repeats == 0) {
        repeatsSince = now;
      }
      repeats++;
      return;
    }
    if (repeats > 0) {
      reportRepeats(text);
    }
    lastKey = key;
    appendLine(text, line.text());
  }

  private void reportRepeats(StringBuilder text) {
    Styled notice = new Styled(mode.colors(), Glyphs.of(mode.unicode()));
    notice
        .pad(LineFormatter.MESSAGE_COLUMN)
        .append(
            Style.MUTED,
            notice.glyphs().ellipsis()
                + " repeated "
                + String.format(Locale.ROOT, "%,d", repeats)
                + (repeats == 1 ? " time" : " times"));
    appendLine(text, notice.toString());
    repeats = 0;
  }

  private String droppedNotice(long dropped) {
    Styled notice = new Styled(mode.colors(), Glyphs.of(mode.unicode()));
    return notice
        .pad(LineFormatter.MESSAGE_COLUMN)
        .append(
            Style.CAUTION,
            notice.glyphs().ellipsis()
                + " "
                + String.format(Locale.ROOT, "%,d", dropped)
                + (dropped == 1 ? " line" : " lines")
                + " not shown: the console could not keep up (the log file has every line)")
        .toString();
  }

  private static void appendLine(StringBuilder text, String line) {
    if (!text.isEmpty()) {
      text.append('\n');
    }
    text.append(line);
  }

  private void write(CharSequence text) {
    LineReader current = reader;
    if (!plain && current != null) {
      try {
        current.printAbove(text.toString());
        return;
      } catch (RuntimeException | IOError e) {
        fail(e);
      }
    } else if (!plain && terminal != null) {
      try {
        terminal.writer().append(text).append(System.lineSeparator());
        terminal.writer().flush();
        return;
      } catch (RuntimeException | IOError e) {
        fail(e);
      }
    }
    direct.append(text).append(System.lineSeparator());
    direct.flush();
  }

  private void fail(Throwable e) {
    plain = true;
    reader = null;
    direct
        .append("[console] The terminal failed (")
        .append(String.valueOf(e))
        .append("): plain output from now on")
        .append(System.lineSeparator());
  }

  // ---------------------------------------------------------------------------
  // Status line
  // ---------------------------------------------------------------------------

  private void refreshStatus() {
    LineReader current = reader;
    if (plain || terminal == null) {
      return;
    }
    if (current == null) {
      // No line reader yet: this thread is the only one writing to the terminal.
      try {
        var _ = drawStatus();
      } catch (RuntimeException | IOError e) {
        fail(e);
      }
      return;
    }
    try {
      current.callWidget(STATUS_WIDGET);
    } catch (IllegalStateException e) {
      // Between two lines read: the next second draws it.
    } catch (RuntimeException | IOError e) {
      fail(e);
    }
  }

  /** The status widget: runs under the line reader's lock, so never during a redraw. */
  private boolean drawStatus() {
    Terminal own = terminal;
    Supplier<String> text = statusText;
    if (own == null || plain) {
      return true;
    }
    String line;
    try {
      line = text == null ? starting() : text.get();
    } catch (RuntimeException e) {
      return true;
    }
    Status status = Status.getStatus(own);
    if (status != null) {
      status.update(List.of(AttributedString.fromAnsi(line)));
      statusShown = true;
    }
    return true;
  }

  /** The status line until the proxy is ready. */
  private String starting() {
    Glyphs glyphs = Glyphs.of(mode.unicode());
    return new Styled(mode.colors(), glyphs)
        .append(' ')
        .append(Style.CAUTION, glyphs.up())
        .append(' ')
        .append(Style.MUTED, "starting" + glyphs.ellipsis())
        .toString();
  }

  /**
   * The detach widget: erases the status line and the line being typed, under the reader's lock.
   */
  private boolean clearInput(LineReader current) {
    Terminal own = terminal;
    if (own == null) {
      return true;
    }
    if (statusShown) {
      Status.getExistingStatus(own)
          .ifPresent(
              status -> {
                status.update(List.of());
                status.close();
              });
    }
    current.getBuffer().clear();
    if (current instanceof LineReaderImpl impl) {
      impl.setPrompt("");
    }
    current.callWidget(LineReader.REDRAW_LINE);
    current.callWidget(LineReader.REDISPLAY);
    // The line reader never returns from this read: undo the modes it set, or the shell after Warp
    // gets "^[OA" for the arrow keys and bracketed pastes.
    own.puts(InfoCmp.Capability.keypad_local);
    own.writer().write(BRACKETED_PASTE_OFF);
    own.flush();
    return true;
  }

  /** Leaves the terminal to plain lines: the prompt and status line erased, the reader detached. */
  private void leaveTerminal() {
    LineReader current = reader;
    reader = null;
    if (current != null) {
      try {
        current.callWidget(DETACH_WIDGET);
      } catch (RuntimeException | IOError e) {
        // Not reading, or the terminal is gone: nothing left to erase.
      }
    }
  }
}
