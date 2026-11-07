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
package dev.warp.proxy.command;

import dev.warp.api.command.CommandSource;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.console.ConsoleCommandSource;
import dev.warp.proxy.console.ConsoleOutput;
import dev.warp.proxy.i18n.Message;
import dev.warp.proxy.logging.LogLevels;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;

/**
 * {@code /log}: the operator's control of logging while the proxy runs, never a player's.
 *
 * <ul>
 *   <li>{@code /log level <source> <level>} changes the level of a source (a tag such as {@code
 *       antibot} or {@code plugin:showcase}, or a logger name) until the proxy stops; {@code reset}
 *       puts back the level it had, and {@code /log level} lists what changed. {@code
 *       logging.levels} of {@code warp.conf} keeps levels across restarts: the reply says the line
 *       to add.
 *   <li>{@code /log filter <source>} shows only that source at the console (and errors of any),
 *       {@code /log filter off} every source again; the log file keeps every line.
 *   <li>{@code /log tail [lines]} prints the last lines the console received again.
 * </ul>
 *
 * <p>Each subcommand has its node ({@value #LEVEL_PERMISSION}, {@value #FILTER_PERMISSION}, {@value
 * #TAIL_PERMISSION}), and the command does not exist for players, whatever a permission plugin
 * grants them. {@code /log level} is the console's and that of the remote console's sessions whose
 * token may run commands ({@link OperatorSource}); {@code filter} and {@code tail} act on what a
 * console shows, so they stay the console's, and the remote console's client has its own ({@link
 * #view}). Sources are completed from the loggers that exist.
 */
final class LogCommand {

  /** The node of {@code /log level}. */
  static final String LEVEL_PERMISSION = "warp.console.log.level";

  /** The node of {@code /log filter}. */
  static final String FILTER_PERMISSION = "warp.console.log.filter";

  /** The node of {@code /log tail}. */
  static final String TAIL_PERMISSION = "warp.console.log.tail";

  /** How many lines {@code /log tail} prints without a count. */
  static final int DEFAULT_TAIL = 20;

  private LogCommand() {}

  /**
   * Builds {@code /log}.
   *
   * @param levels the levels of log sources
   * @param output the console's output, or {@code null} when it writes JSON
   * @return the command
   */
  static LiteralArgumentBuilder<CommandSource> build(
      LogLevels levels, Supplier<@Nullable ConsoleOutput> output) {
    return LiteralArgumentBuilder.<CommandSource>literal("log")
        .requires(
            operator(LEVEL_PERMISSION).or(console(FILTER_PERMISSION)).or(console(TAIL_PERMISSION)))
        .then(levelNode(levels))
        .then(filterNode(output, LogLevels::sources, tag -> LogLevels.sources().contains(tag)))
        .then(tailNode(output));
  }

  /**
   * Builds the {@code /log} of the remote console's client: {@code filter} and {@code tail}, over
   * what this console shows of the remote proxies. Their loggers are not this process's, so any
   * source is accepted and the sources of the lines received are completed. {@code /log level}
   * comes from the proxy, whose tree has it for a token that may run commands there.
   *
   * @param output this console's output, or {@code null} when it writes JSON
   * @return the command
   */
  static LiteralArgumentBuilder<CommandSource> view(Supplier<@Nullable ConsoleOutput> output) {
    return LiteralArgumentBuilder.<CommandSource>literal("log")
        .requires(console(FILTER_PERMISSION).or(console(TAIL_PERMISSION)))
        .then(
            filterNode(
                output,
                () -> {
                  ConsoleOutput current = output.get();
                  return current == null ? List.of() : current.recentTags();
                },
                _ -> true))
        .then(tailNode(output));
  }

  private static LiteralArgumentBuilder<CommandSource> levelNode(LogLevels levels) {
    return LiteralArgumentBuilder.<CommandSource>literal("level")
        .requires(operator(LEVEL_PERMISSION))
        .executes(context -> list(levels, context.getSource()))
        .then(
            RequiredArgumentBuilder.<CommandSource, String>argument(
                    "source", SourceArgument.INSTANCE)
                .suggests((context, builder) -> suggest(LogLevels.sources(), builder))
                .then(
                    RequiredArgumentBuilder.<CommandSource, String>argument(
                            "level", StringArgumentType.word())
                        .suggests(LogCommand::suggestLevels)
                        .executes(
                            context ->
                                level(
                                    levels,
                                    context.getSource(),
                                    context.getArgument("source", String.class),
                                    StringArgumentType.getString(context, "level")))));
  }

  private static LiteralArgumentBuilder<CommandSource> filterNode(
      Supplier<@Nullable ConsoleOutput> output,
      Supplier<? extends Collection<String>> sources,
      Predicate<String> known) {
    return LiteralArgumentBuilder.<CommandSource>literal("filter")
        .requires(console(FILTER_PERMISSION))
        .then(
            LiteralArgumentBuilder.<CommandSource>literal("off")
                .executes(context -> filter(output.get(), context.getSource(), null, known)))
        .then(
            RequiredArgumentBuilder.<CommandSource, String>argument(
                    "source", SourceArgument.INSTANCE)
                .suggests((context, builder) -> suggest(sources.get(), builder))
                .executes(
                    context ->
                        filter(
                            output.get(),
                            context.getSource(),
                            context.getArgument("source", String.class),
                            known)));
  }

  private static LiteralArgumentBuilder<CommandSource> tailNode(
      Supplier<@Nullable ConsoleOutput> output) {
    return LiteralArgumentBuilder.<CommandSource>literal("tail")
        .requires(console(TAIL_PERMISSION))
        .executes(context -> tail(output.get(), context.getSource(), DEFAULT_TAIL))
        .then(
            RequiredArgumentBuilder.<CommandSource, Integer>argument(
                    "lines", IntegerArgumentType.integer(1, ConsoleOutput.TAIL_CAPACITY))
                .executes(
                    context ->
                        tail(
                            output.get(),
                            context.getSource(),
                            IntegerArgumentType.getInteger(context, "lines"))));
  }

  /** The console, holding a node: never a player, whatever its permissions. */
  private static Predicate<CommandSource> console(String permission) {
    return source -> source instanceof ConsoleCommandSource && source.hasPermission(permission);
  }

  /**
   * The console or a remote console session that may run commands, holding a node: never a player.
   * {@code /log filter} and {@code /log tail} stay the console's, as they act on what it shows (the
   * remote console's client has its own).
   */
  private static Predicate<CommandSource> operator(String permission) {
    return source -> source instanceof OperatorSource && source.hasPermission(permission);
  }

  // ---------------------------------------------------------------------------
  // /log level
  // ---------------------------------------------------------------------------

  private static int list(LogLevels levels, CommandSource source) {
    Map<String, String> changed = levels.changed();
    if (changed.isEmpty()) {
      source.sendMessage(Message.LOG_LEVEL_NONE.of());
      return 0;
    }
    StringBuilder text = new StringBuilder();
    changed.forEach(
        (name, level) ->
            text.append(text.isEmpty() ? "" : ", ").append(name).append(' ').append(level));
    source.sendMessage(Message.LOG_LEVEL_LIST.with(Message.text("levels", text.toString())));
    return changed.size();
  }

  private static int level(LogLevels levels, CommandSource source, String target, String typed) {
    String value = typed.toLowerCase(Locale.ROOT);
    if (value.equals("reset")) {
      List<String> reset = levels.reset(target);
      source.sendMessage(
          (reset.isEmpty() ? Message.LOG_LEVEL_UNCHANGED : Message.LOG_LEVEL_RESET)
              .with(Message.text("source", target)));
      return reset.isEmpty() ? 0 : Command.SINGLE_SUCCESS;
    }
    if (!LoggingConfig.LEVELS.contains(value)) {
      source.sendMessage(Message.LOG_LEVEL_INVALID.with(Message.text("level", typed)));
      return 0;
    }
    List<String> loggers = levels.set(target, Level.toLevel(value));
    if (loggers.isEmpty()) {
      return unknown(source, target);
    }
    // A key with a colon or a dot needs quotes in HOCON.
    String key = target.matches("[A-Za-z0-9_-]+") ? target : "\"" + target + "\"";
    source.sendMessage(
        Message.LOG_LEVEL_SET.with(
            Message.text("source", target),
            Message.text("level", value),
            Message.text("loggers", String.join(", ", loggers)),
            Message.text("keep", key + " = " + value)));
    return Command.SINGLE_SUCCESS;
  }

  private static int unknown(CommandSource source, String target) {
    source.sendMessage(
        Message.LOG_SOURCE_UNKNOWN.with(
            Message.text("source", target),
            Message.text("sources", String.join(", ", LogLevels.sources()))));
    return 0;
  }

  // ---------------------------------------------------------------------------
  // /log filter and /log tail
  // ---------------------------------------------------------------------------

  private static int filter(
      @Nullable ConsoleOutput output,
      CommandSource source,
      @Nullable String tag,
      Predicate<String> known) {
    if (output == null) {
      source.sendMessage(Message.LOG_UNAVAILABLE.of());
      return 0;
    }
    if (tag == null) {
      output.filter(null);
      source.sendMessage(Message.LOG_FILTER_OFF.of());
      return Command.SINGLE_SUCCESS;
    }
    if (!known.test(tag)) {
      return unknown(source, tag);
    }
    // The reply first: once the filter is on, the console still shows replies, but not echoes.
    source.sendMessage(Message.LOG_FILTER_ON.with(Message.text("source", tag)));
    output.filter(tag);
    return Command.SINGLE_SUCCESS;
  }

  private static int tail(@Nullable ConsoleOutput output, CommandSource source, int lines) {
    if (output == null) {
      source.sendMessage(Message.LOG_UNAVAILABLE.of());
      return 0;
    }
    return output.replay(lines);
  }

  // ---------------------------------------------------------------------------
  // Completion
  // ---------------------------------------------------------------------------

  private static CompletableFuture<Suggestions> suggestLevels(
      CommandContext<CommandSource> context, SuggestionsBuilder builder) {
    List<String> values = new java.util.ArrayList<>(LoggingConfig.LEVELS);
    values.add("reset");
    return suggest(values, builder);
  }

  private static CompletableFuture<Suggestions> suggest(
      Collection<String> values, SuggestionsBuilder builder) {
    String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
    for (String value : values) {
      if (value.toLowerCase(Locale.ROOT).startsWith(typed)) {
        builder.suggest(value);
      }
    }
    return builder.buildFuture();
  }

  /**
   * A source: a run of characters up to the next space, so that {@code plugin:showcase} and {@code
   * dev.warp.proxy.drain} need no quotes. Console only, so never sent to a client.
   */
  static final class SourceArgument implements ArgumentType<String> {

    static final SourceArgument INSTANCE = new SourceArgument();

    private SourceArgument() {}

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
      int start = reader.getCursor();
      while (reader.canRead() && reader.peek() != ' ') {
        reader.skip();
      }
      if (reader.getCursor() == start) {
        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS
            .dispatcherUnknownArgument()
            .createWithContext(reader);
      }
      return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public Collection<String> getExamples() {
      return List.of("antibot", "plugin:showcase", "dev.warp.proxy.drain");
    }
  }
}
