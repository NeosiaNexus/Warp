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
package dev.warp.proxy.logging;

import dev.warp.proxy.console.SourceTags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.async.AsyncLoggerConfig;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.jspecify.annotations.Nullable;

/**
 * The levels of log sources, changed while the proxy runs: those of {@code logging.levels} at
 * startup, which stay, and those of {@code /log level}, which last until the proxy stops or until
 * {@code /log level <source> reset} puts back what was there.
 *
 * <p>A source is a console tag ({@code antibot}, {@code conn}, {@code plugin:showcase}, {@code
 * netty}...), which stands for its loggers, or a logger name. A logger without a configuration of
 * its own gets an asynchronous one, as every logger of {@code log4j2.xml} is: a level never makes a
 * thread of the proxy write the log itself.
 */
public final class LogLevels {

  /** What a logger's configuration was before {@code /log level} changed it. */
  private record Saved(@Nullable Level level, boolean existed) {}

  private final Map<String, Saved> saved = new LinkedHashMap<>();
  private final Map<String, String> changed = new LinkedHashMap<>();

  /**
   * Applies the levels of {@code logging.levels}: they are the starting point that {@code reset}
   * goes back to.
   *
   * @param levels the level of each source, as {@code warp.conf} has them
   * @return the sources that stand for no logger, to warn about
   */
  public synchronized List<String> configure(Map<String, String> levels) {
    List<String> unknown = new ArrayList<>();
    LoggerContext context = context();
    if (context == null) {
      return unknown;
    }
    for (Map.Entry<String, String> entry : levels.entrySet()) {
      List<String> names = SourceTags.loggersOf(entry.getKey(), existing(context));
      if (names.isEmpty()) {
        names = List.of(entry.getKey());
        unknown.add(entry.getKey());
      }
      Level level = Level.toLevel(entry.getValue(), Level.INFO);
      for (String name : names) {
        setLevel(context, name, level);
      }
    }
    context.updateLoggers();
    return unknown;
  }

  /**
   * Sets the level of a source until the proxy stops.
   *
   * @param source a tag or a logger name
   * @param level the level
   * @return the loggers changed, empty when the source stands for none
   */
  public synchronized List<String> set(String source, Level level) {
    LoggerContext context = context();
    if (context == null) {
      return List.of();
    }
    List<String> names = SourceTags.loggersOf(source, existing(context));
    Configuration config = context.getConfiguration();
    for (String name : names) {
      if (!saved.containsKey(name)) {
        LoggerConfig current = config.getLoggerConfig(name);
        boolean existed = current.getName().equals(name);
        saved.put(name, new Saved(existed ? current.getLevel() : null, existed));
      }
      setLevel(context, name, level);
    }
    if (!names.isEmpty()) {
      changed.put(source.strip(), level.name().toLowerCase(Locale.ROOT));
      context.updateLoggers();
    }
    return names;
  }

  /**
   * Puts back the level a source had before {@code /log level} changed it.
   *
   * @param source a tag or a logger name
   * @return the loggers put back, empty when the source was not changed
   */
  public synchronized List<String> reset(String source) {
    LoggerContext context = context();
    if (context == null) {
      return List.of();
    }
    List<String> names = SourceTags.loggersOf(source, existing(context));
    Configuration config = context.getConfiguration();
    List<String> reset = new ArrayList<>();
    for (String name : names) {
      Saved before = saved.remove(name);
      if (before == null) {
        continue;
      }
      if (before.existed()) {
        config.getLoggerConfig(name).setLevel(before.level());
      } else {
        config.removeLogger(name);
      }
      reset.add(name);
    }
    changed.remove(source.strip());
    if (!reset.isEmpty()) {
      context.updateLoggers();
    }
    return reset;
  }

  /**
   * Returns the sources {@code /log level} changed, with their level.
   *
   * @return the levels, in the order they were changed
   */
  public synchronized Map<String, String> changed() {
    return Map.copyOf(changed);
  }

  /**
   * Returns the sources {@code /log} suggests: the tags of the loggers that exist, of the known
   * libraries and of the plugins.
   *
   * @return the tags, sorted
   */
  public static SortedSet<String> sources() {
    LoggerContext context = context();
    return context == null ? new TreeSet<>() : SourceTags.known(existing(context));
  }

  private static void setLevel(LoggerContext context, String name, Level level) {
    Configuration config = context.getConfiguration();
    LoggerConfig current = config.getLoggerConfig(name);
    if (current.getName().equals(name)) {
      current.setLevel(level);
      return;
    }
    LoggerConfig created =
        AsyncLoggerConfig.newAsyncBuilder()
            .setLoggerName(name)
            .setLevel(level)
            .setAdditivity(true)
            .setConfig(config)
            .build();
    config.addLogger(name, created);
  }

  private static Collection<String> existing(LoggerContext context) {
    List<String> names = new ArrayList<>();
    for (var logger : context.getLoggers()) {
      names.add(logger.getName());
    }
    return names;
  }

  private static @Nullable LoggerContext context() {
    return LogManager.getContext(false) instanceof LoggerContext context ? context : null;
  }
}
