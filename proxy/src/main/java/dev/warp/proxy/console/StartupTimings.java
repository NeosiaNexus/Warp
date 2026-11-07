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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Where the startup's time went, from the JVM's start to {@code Ready in X s}: the JVM itself,
 * logging, the configuration, natives, the anti-bot's store, each plugin, the bind, and the wait
 * for the servers' answers. The console shows it as one dimmed line under {@code Ready in}, and
 * {@code /warp startup} in full.
 *
 * <p>Phases are recorded on the startup thread, in order; the total and the line are read once the
 * proxy is ready, from any thread.
 */
public final class StartupTimings {

  /** Phases shorter than this are left out of the one-line summary, not of /warp startup. */
  private static final long SUMMARY_MIN_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

  /**
   * A phase of the startup.
   *
   * @param name its name, such as {@code config}
   * @param nanos how long it took
   * @param parts what it is made of (each plugin of {@code plugins}), possibly empty
   */
  public record Phase(String name, long nanos, List<Phase> parts) {
    /**
     * Creates a phase, copying its parts.
     *
     * @param name its name
     * @param nanos how long it took
     * @param parts what it is made of
     */
    public Phase {
      parts = List.copyOf(parts);
    }
  }

  private final List<Phase> phases = new ArrayList<>();
  private volatile long totalNanos = -1;

  /**
   * Records a phase.
   *
   * @param name its name
   * @param nanos how long it took
   */
  public synchronized void record(String name, long nanos) {
    phases.add(new Phase(name, nanos, List.of()));
  }

  /**
   * Records a phase made of named parts, such as the plugins.
   *
   * @param name its name
   * @param nanos how long it took in all
   * @param parts how long each part took, in nanoseconds, in order
   */
  public synchronized void record(String name, long nanos, Map<String, Long> parts) {
    List<Phase> list = new ArrayList<>(parts.size());
    parts.forEach((part, time) -> list.add(new Phase(part, time, List.of())));
    phases.add(new Phase(name, nanos, list));
  }

  /**
   * Runs a phase and records how long it took.
   *
   * @param name its name
   * @param work the phase
   */
  public void run(String name, Runnable work) {
    long start = System.nanoTime();
    try {
      work.run();
    } finally {
      record(name, System.nanoTime() - start);
    }
  }

  /**
   * Ends the startup: what the phases do not account for becomes {@code other}.
   *
   * @param totalNanos the whole startup, from the JVM's start
   */
  public synchronized void finish(long totalNanos) {
    long accounted = phases.stream().mapToLong(Phase::nanos).sum();
    long rest = totalNanos - accounted;
    if (rest > 0) {
      phases.add(new Phase("other", rest, List.of()));
    }
    this.totalNanos = totalNanos;
  }

  /**
   * Returns the whole startup.
   *
   * @return nanoseconds from the JVM's start to ready, or {@code -1} before the proxy is ready
   */
  public long totalNanos() {
    return totalNanos;
  }

  /**
   * Returns the phases, in order.
   *
   * @return the phases
   */
  public synchronized List<Phase> phases() {
    return List.copyOf(phases);
  }

  /**
   * Returns the one-line summary: {@code jvm 0.41 s · config 42 ms · plugins 1.2 s (showcase 1.1 s)
   * · bind 3 ms}, the longest plugin named, phases under 5 ms left out.
   *
   * @param glyphs the separator the output can show
   * @return the summary
   */
  String summary(Glyphs glyphs) {
    StringBuilder text = new StringBuilder();
    for (Phase phase : phases()) {
      if (phase.nanos() < SUMMARY_MIN_NANOS) {
        continue;
      }
      if (!text.isEmpty()) {
        text.append(' ').append(glyphs.dot()).append(' ');
      }
      text.append(phase.name()).append(' ').append(format(phase.nanos()));
      Phase longest = null;
      for (Phase part : phase.parts()) {
        if (longest == null || part.nanos() > longest.nanos()) {
          longest = part;
        }
      }
      if (longest != null && longest.nanos() >= SUMMARY_MIN_NANOS) {
        text.append(" (")
            .append(longest.name())
            .append(' ')
            .append(format(longest.nanos()))
            .append(phase.parts().size() > 1 ? ", " + (phase.parts().size() - 1) + " more" : "")
            .append(')');
      }
    }
    return text.toString();
  }

  /**
   * Renders the summary as the dimmed line the console prints under {@code Ready in}, lined up with
   * messages.
   *
   * @return the block, whose title the log file gets
   */
  public ConsoleBlock block() {

    return ConsoleBlock.builder("Startup: " + summary(Glyphs.ASCII))
        .text(" ".repeat(LineFormatter.MESSAGE_COLUMN))
        .glyph(Style.MUTED, this::summary)
        .titleOnly()
        .end()
        .build();
  }

  /**
   * Formats a duration for people: {@code 820 µs}, {@code 42 ms}, {@code 1.24 s}.
   *
   * @param nanos the duration
   * @return the text
   */
  public static String format(long nanos) {
    if (nanos < 1_000_000) {
      return Math.max(0, nanos / 1000) + " µs";
    }
    if (nanos < 1_000_000_000) {
      return nanos / 1_000_000 + " ms";
    }
    return String.format(Locale.ROOT, "%.2f s", nanos / 1e9);
  }
}
