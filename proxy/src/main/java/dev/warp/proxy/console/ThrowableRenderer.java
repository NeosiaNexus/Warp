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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Renders stack traces for the console: compact and coloured. Warp's own frames stand out, library
 * frames are dimmed, and frames a cause shares with the exception it caused are counted rather than
 * repeated, as the JDK does. Compact traces, for people watching a coloured console, also collapse
 * runs of library frames into one line that names their packages; plain outputs (pipes, {@code
 * docker logs}, CI) keep every frame, as the log file always does.
 */
final class ThrowableRenderer {

  /** Runs of at least this many library frames, after a trace's first frame, are collapsed. */
  static final int COLLAPSE_RUN = 3;

  /** Causes and suppressed exceptions nested deeper than this are left out. */
  private static final int MAX_DEPTH = 16;

  private static final String WARP_PACKAGE = "dev.warp.";

  private ThrowableRenderer() {}

  /**
   * Appends a stack trace, each line starting on a new line.
   *
   * @param thrown the exception
   * @param indent the indentation of the trace
   * @param compact whether runs of library frames are collapsed
   * @param out where to append
   */
  static void append(Throwable thrown, String indent, boolean compact, Styled out) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    new ThrowableRenderer.Run(compact, out, seen).append(thrown, null, indent, "", 0);
  }

  /** One rendering: its options and the exceptions already shown. */
  private record Run(boolean compact, Styled out, Set<Throwable> seen) {

    private void append(
        Throwable thrown,
        StackTraceElement @Nullable [] enclosing,
        String indent,
        String caption,
        int depth) {
      out.append('\n').append(indent);
      if (!seen.add(thrown)) {
        out.append(
            Style.MUTED, caption + "[circular reference: " + thrown.getClass().getName() + "]");
        return;
      }
      if (depth > MAX_DEPTH) {
        out.append(Style.MUTED, caption + out.glyphs().ellipsis() + " deeper causes left out");
        return;
      }
      if (!caption.isEmpty()) {
        out.append(Style.MUTED, caption);
      }
      header(thrown, out);

      StackTraceElement[] frames = thrown.getStackTrace();
      int shared = enclosing == null ? 0 : shared(frames, enclosing);
      int shown = frames.length - shared;
      String frameIndent = indent + "    ";
      int i = 0;
      while (i < shown) {
        if (compact && i > 0 && !isWarp(frames[i])) {
          int run = i;
          while (run < shown && !isWarp(frames[run])) {
            run++;
          }
          if (run - i >= COLLAPSE_RUN) {
            collapsed(frames, i, run, frameIndent, out);
            i = run;
            continue;
          }
        }
        frame(frames[i], frameIndent, out);
        i++;
      }
      if (shared > 0) {
        out.append('\n')
            .append(frameIndent)
            .append(Style.MUTED, out.glyphs().ellipsis() + " " + shared + " more");
      }
      for (Throwable suppressed : thrown.getSuppressed()) {
        append(suppressed, frames, indent + "  ", "Suppressed: ", depth + 1);
      }
      Throwable cause = thrown.getCause();
      if (cause != null) {
        append(cause, frames, indent, "Caused by: ", depth + 1);
      }
    }
  }

  /** {@code java.lang.IllegalStateException: message}: the package dimmed, the class in red. */
  private static void header(Throwable thrown, Styled out) {
    String name = thrown.getClass().getName();
    int dot = name.lastIndexOf('.');
    if (dot >= 0) {
      out.append(Style.MUTED, name.substring(0, dot + 1));
    }
    out.append(Style.ERROR, name.substring(dot + 1));
    String message = thrown.getLocalizedMessage();
    if (message != null) {
      out.append(": ").append(message.replace("\n", "\n    "));
    }
  }

  private static void frame(StackTraceElement frame, String indent, Styled out) {
    out.append('\n').append(indent);
    String location = location(frame);
    if (isWarp(frame)) {
      out.append(Style.MUTED, "at ")
          .append(frame.getClassName() + ".")
          .append(Style.STRONG, frame.getMethodName())
          .append(Style.MUTED, location);
    } else {
      out.append(
          Style.MUTED, "at " + frame.getClassName() + "." + frame.getMethodName() + location);
    }
  }

  /** {@code … 14 frames in io.netty, java.base}. */
  private static void collapsed(
      StackTraceElement[] frames, int from, int to, String indent, Styled out) {
    Set<String> origins = new LinkedHashSet<>();
    for (int i = from; i < to; i++) {
      origins.add(origin(frames[i]));
    }
    StringBuilder text =
        new StringBuilder(out.glyphs().ellipsis())
            .append(' ')
            .append(to - from)
            .append(" frames in ");
    int listed = 0;
    for (String origin : origins) {
      if (listed == 3) {
        text.append(", ").append(out.glyphs().ellipsis());
        break;
      }
      text.append(listed == 0 ? "" : ", ").append(origin);
      listed++;
    }
    out.append('\n').append(indent).append(Style.MUTED, text);
  }

  /** The module of a JDK frame, else the first two segments of its package. */
  private static String origin(StackTraceElement frame) {
    String module = frame.getModuleName();
    if (module != null) {
      return module;
    }
    String name = frame.getClassName();
    int first = name.indexOf('.');
    int second = first < 0 ? -1 : name.indexOf('.', first + 1);
    return second < 0 ? name : name.substring(0, second);
  }

  private static String location(StackTraceElement frame) {
    if (frame.isNativeMethod()) {
      return "(native)";
    }
    String file = frame.getFileName();
    if (file == null) {
      return "(unknown source)";
    }
    int line = frame.getLineNumber();
    return line >= 0 ? "(" + file + ":" + line + ")" : "(" + file + ")";
  }

  /** Warp's frames, and those of plugins: the code whoever reads the trace can change. */
  private static boolean isWarp(StackTraceElement frame) {
    String name = frame.getClassName();
    return name.startsWith(WARP_PACKAGE) || SourceTags.isPluginClass(name);
  }

  /** How many frames at the end of {@code frames} are those of the enclosing trace. */
  private static int shared(StackTraceElement[] frames, StackTraceElement[] enclosing) {
    int m = frames.length - 1;
    int n = enclosing.length - 1;
    while (m >= 0 && n >= 0 && frames[m].equals(enclosing[n])) {
      m--;
      n--;
    }
    return frames.length - 1 - m;
  }
}
