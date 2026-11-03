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
package dev.warp.proxy.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when the proxy configuration is invalid or cannot be loaded: Warp refuses to start rather
 * than run with settings the operator did not mean.
 *
 * <p>It carries one or more {@linkplain Problem problems}, each naming the file, the line and the
 * key it is about when they are known, and saying what was expected and what was found. Its message
 * lists them, one per line.
 */
public final class ConfigurationException extends Exception {

  /** A dotted key at the start of a message, such as {@code compression.level must be...}. */
  private static final Pattern LEADING_KEY =
      Pattern.compile("^([a-z][a-z0-9-]*(?:\\.(?:[a-z0-9*][a-z0-9_*-]*|\"[^\"]*\"))*)[ :']");

  /**
   * One thing wrong with the configuration.
   *
   * @param file the file it is in, or {@code null} if not known yet
   * @param line the line it is on, from 1, or 0 if not known
   * @param key the dotted key it is about, or {@code null} for the file as a whole
   * @param description what is wrong: what was expected and what was found, with the key
   */
  public record Problem(@Nullable Path file, int line, @Nullable String key, String description) {

    /**
     * Returns the problem as the operator reads it: {@code warp.conf, line 14: compression.level:
     * expected...}. A file other than {@code warp.conf} is named with its directory.
     *
     * @return the problem on one line
     */
    public String format() {
      StringBuilder text = new StringBuilder();
      Path where = file;
      if (where != null) {
        Path name = where.getFileName();
        Path parent = where.getParent();
        Path parentName = parent == null ? null : parent.getFileName();
        Path shown = where;
        if (name != null && WarpConfig.CONFIG_FILE.equals(name.toString())) {
          shown = name;
        } else if (name != null && parentName != null) {
          shown = parentName.resolve(name);
        }
        text.append(shown);
        if (line > 0) {
          text.append(", line ").append(line);
        }
        text.append(": ");
      }
      return text.append(description).toString();
    }
  }

  private final List<Problem> problems;

  ConfigurationException(String message) {
    this(List.of(new Problem(null, 0, leadingKey(message), message)), null);
  }

  ConfigurationException(String message, Throwable cause) {
    this(List.of(new Problem(null, 0, leadingKey(message), message)), cause);
  }

  private ConfigurationException(List<Problem> problems, @Nullable Throwable cause) {
    super(describe(problems), cause);
    this.problems = List.copyOf(problems);
  }

  /**
   * Returns an exception about one key's value.
   *
   * @param key the dotted key, such as {@code compression.level}
   * @param expected what the key takes, such as {@code a whole number from -1 to 9}
   * @param found what it holds, as written
   * @return the exception
   */
  public static ConfigurationException invalid(
      String key, String expected, @Nullable Object found) {
    return new ConfigurationException(
        List.of(
            new Problem(
                null,
                0,
                key,
                key + ": expected " + expected + ", found " + (found == null ? "nothing" : found))),
        null);
  }

  /**
   * Returns an exception about one key, described freely.
   *
   * @param key the dotted key
   * @param description what is wrong, after the key
   * @return the exception
   */
  public static ConfigurationException at(String key, String description) {
    return new ConfigurationException(
        List.of(new Problem(null, 0, key, key + ": " + description)), null);
  }

  /**
   * Returns an exception listing several problems.
   *
   * @param problems the problems, at least one
   * @return the exception
   * @throws IllegalArgumentException if there is none
   */
  public static ConfigurationException of(List<Problem> problems) {
    if (problems.isEmpty()) {
      throw new IllegalArgumentException("no problem to report");
    }
    return new ConfigurationException(problems, null);
  }

  /**
   * Returns the problems, in the order they were found.
   *
   * @return the problems, at least one
   */
  public List<Problem> problems() {
    return problems;
  }

  /**
   * Returns this exception with its problems placed in {@code file}: each one about a key gets the
   * line of that key, or of the nearest block around it that is in the file.
   *
   * @param file the file the problems are in
   * @param lineOf the line of a dotted key in the file, 0 if it is not there
   * @return the located exception
   */
  public ConfigurationException locatedIn(Path file, ToIntFunction<String> lineOf) {
    List<Problem> located = new ArrayList<>(problems.size());
    for (Problem problem : problems) {
      if (problem.file() != null) {
        located.add(problem);
        continue;
      }
      int line = problem.line();
      String key = problem.key();
      for (String k = key; line == 0 && k != null; k = parent(k)) {
        line = lineOf.applyAsInt(k);
      }
      located.add(new Problem(file, line, key, problem.description()));
    }
    ConfigurationException exception = new ConfigurationException(located, getCause());
    exception.setStackTrace(getStackTrace());
    return exception;
  }

  private static @Nullable String parent(String key) {
    int dot = key.lastIndexOf('.');
    return dot < 0 ? null : key.substring(0, dot);
  }

  private static @Nullable String leadingKey(String message) {
    Matcher matcher = LEADING_KEY.matcher(message);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static String describe(List<Problem> problems) {
    if (problems.size() == 1) {
      return problems.getFirst().format();
    }
    StringBuilder text = new StringBuilder().append(problems.size()).append(" problems:");
    for (Problem problem : problems) {
      text.append(System.lineSeparator()).append("  - ").append(problem.format());
    }
    return text.toString();
  }
}
