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
import dev.warp.api.command.SimpleCommand;
import dev.warp.api.event.Subscription;
import dev.warp.proxy.i18n.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.SuggestionContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The proxy's commands, as one Brigadier dispatcher.
 *
 * <p>Registrations are kept by name with their owner; every change builds a new dispatcher from
 * them and publishes it at once, so that commands run and suggestions are computed on an immutable
 * tree, from any thread, without locks. Brigadier cannot remove a node from a tree, which is why
 * Velocity reaches into it by reflection: rebuilding avoids that.
 *
 * <p>Commands run on virtual threads.
 */
public final class WarpCommandManager implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(WarpCommandManager.class);

  /** The owner of the proxy's own commands. */
  public static final String PROXY = "warp";

  private static final Pattern SPACES = Pattern.compile(" {2,}");

  private final Map<String, Registered> registrations = new LinkedHashMap<>();
  private volatile CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>();
  private final Executor executor;
  private volatile Runnable onChange = () -> {};

  /** Creates a manager running commands on virtual threads. */
  public WarpCommandManager() {
    this(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("warp-command-", 0).factory()));
  }

  /**
   * Creates a manager running commands with the given executor; tests pass {@code Runnable::run}.
   *
   * @param executor runs commands; shut down by {@link #close()} if it is an executor service
   */
  public WarpCommandManager(Executor executor) {
    this.executor = executor;
  }

  /** A command under one name: its own, or an alias. */
  private record Registered(String owner, LiteralCommandNode<CommandSource> node) {}

  // ---------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------

  /**
   * Registers a Brigadier command and its aliases.
   *
   * @param owner who registers it (a plugin identifier, or {@link #PROXY})
   * @param command the command's root literal
   * @param aliases other names
   * @return the registration
   * @throws IllegalStateException if another owner holds one of the names
   */
  public synchronized Subscription register(
      String owner, LiteralArgumentBuilder<CommandSource> command, String... aliases) {
    LiteralCommandNode<CommandSource> node = command.build();
    List<String> names = new ArrayList<>();
    names.add(node.getName().toLowerCase(Locale.ROOT));
    for (String alias : aliases) {
      names.add(alias.toLowerCase(Locale.ROOT));
    }
    for (String name : names) {
      Registered existing = registrations.get(name);
      if (existing != null && !existing.owner().equals(owner)) {
        throw new IllegalStateException(
            "Command /" + name + " is already registered by " + describe(existing.owner()));
      }
    }
    List<Registered> added = new ArrayList<>();
    for (String name : names) {
      LiteralCommandNode<CommandSource> named =
          name.equals(node.getName()) ? node : alias(name, node);
      Registered registered = new Registered(owner, named);
      registrations.put(name, registered);
      added.add(registered);
    }
    rebuild();
    return new Subscription() {
      private volatile boolean active = true;

      @Override
      public boolean isActive() {
        return active;
      }

      @Override
      public void unsubscribe() {
        if (active) {
          active = false;
          remove(added);
        }
      }
    };
  }

  /**
   * Registers a simple command and its aliases.
   *
   * @param owner who registers it
   * @param name the command's name
   * @param command the command
   * @param aliases other names
   * @return the registration
   */
  public Subscription register(
      String owner, String name, SimpleCommand command, String... aliases) {
    return register(owner, simple(name, command), aliases);
  }

  /**
   * Removes a command, whoever registered it.
   *
   * @param name the name
   * @return {@code true} if it existed
   */
  public synchronized boolean unregister(String name) {
    boolean removed = registrations.remove(name.toLowerCase(Locale.ROOT)) != null;
    if (removed) {
      rebuild();
    }
    return removed;
  }

  private synchronized void remove(List<Registered> registered) {
    boolean changed = registrations.values().removeIf(registered::contains);
    if (changed) {
      rebuild();
    }
  }

  private void rebuild() {
    CommandDispatcher<CommandSource> next = new CommandDispatcher<>();
    for (Registered registered : registrations.values()) {
      next.getRoot().addChild(registered.node());
    }
    this.dispatcher = next;
    onChange.run();
  }

  /**
   * Sets what runs once the commands changed, to refresh the command lists players see.
   *
   * @param listener runs after every registration and removal, under the manager's lock: it must
   *     only schedule work
   */
  public void onChange(Runnable listener) {
    this.onChange = listener;
  }

  /** Builds an alias: a literal that runs the command, and redirects to it for its arguments. */
  private static LiteralCommandNode<CommandSource> alias(
      String name, LiteralCommandNode<CommandSource> target) {
    LiteralArgumentBuilder<CommandSource> alias =
        LiteralArgumentBuilder.<CommandSource>literal(name)
            .requires(target.getRequirement())
            .executes(target.getCommand());
    for (var child : target.getChildren()) {
      alias.then(child);
    }
    return alias.build();
  }

  private static LiteralArgumentBuilder<CommandSource> simple(String name, SimpleCommand command) {
    String alias = name.toLowerCase(Locale.ROOT);
    return LiteralArgumentBuilder.<CommandSource>literal(alias)
        .requires(command::hasPermission)
        .executes(
            context -> {
              runSimple(
                  command, new SimpleCommand.Invocation(context.getSource(), alias, List.of()));
              return Command.SINGLE_SUCCESS;
            })
        .then(
            RequiredArgumentBuilder.<CommandSource, String>argument(
                    "arguments", StringArgumentType.greedyString())
                .suggests(
                    (context, builder) -> {
                      String typed = builder.getRemaining();
                      List<String> words = words(typed);
                      int lastStart = typed.lastIndexOf(' ') + 1;
                      var suggestions = builder.createOffset(builder.getStart() + lastStart);
                      for (String suggestion :
                          command.suggest(
                              new SimpleCommand.Invocation(context.getSource(), alias, words))) {
                        suggestions.suggest(suggestion);
                      }
                      return suggestions.buildFuture();
                    })
                .executes(
                    context -> {
                      List<String> words =
                          words(StringArgumentType.getString(context, "arguments"));
                      runSimple(
                          command, new SimpleCommand.Invocation(context.getSource(), alias, words));
                      return Command.SINGLE_SUCCESS;
                    }));
  }

  private static void runSimple(SimpleCommand command, SimpleCommand.Invocation invocation) {
    try {
      command.execute(invocation);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Splits typed arguments on spaces, keeping a trailing empty word while one is being typed. */
  static List<String> words(String typed) {
    if (typed.isEmpty()) {
      return List.of("");
    }
    return Arrays.asList(typed.split(" ", -1));
  }

  // ---------------------------------------------------------------------------
  // Lookup and dispatch
  // ---------------------------------------------------------------------------

  /**
   * Returns whether a command line starts with a command the proxy runs for this source. A proxy
   * command the source may not use is not the proxy's to answer: the backend gets it, so that a
   * backend command of the same name still works for the players the proxy's is not meant for.
   *
   * @param source who typed it
   * @param commandLine the command line, without {@code /}
   * @return {@code true} if the proxy owns its first word and the source may use it
   */
  public boolean owns(CommandSource source, String commandLine) {
    int space = commandLine.indexOf(' ');
    String name =
        (space < 0 ? commandLine : commandLine.substring(0, space)).toLowerCase(Locale.ROOT);
    var node = dispatcher.getRoot().getChild(name);
    return node != null && node.canUse(source);
  }

  /**
   * Returns whether a command of this name exists.
   *
   * @param name the name
   * @return {@code true} if registered
   */
  public boolean hasCommand(String name) {
    return dispatcher.getRoot().getChild(name.toLowerCase(Locale.ROOT)) != null;
  }

  /**
   * Returns the current tree, to build the command list a player sees.
   *
   * @return the root
   */
  public RootCommandNode<CommandSource> root() {
    return dispatcher.getRoot();
  }

  /**
   * Returns the executor commands run on, for other work that calls into plugins and must stay off
   * the event loop, such as the permission checks of a command list.
   *
   * @return the executor
   */
  public Executor executor() {
    return executor;
  }

  /**
   * Returns the tree of the proxy's commands a player may use, to merge into its server's.
   *
   * @param source the player
   * @param version its protocol version
   * @return the tree
   */
  public dev.warp.protocol.command.CommandTree treeFor(
      CommandSource source, dev.warp.protocol.ProtocolVersion version) {
    return BrigadierTrees.treeFor(dispatcher.getRoot(), source, version);
  }

  /**
   * Runs a command line on a virtual thread. Syntax errors and failures are told to the source. A
   * command the source may not use is not run, as if it did not exist.
   *
   * @param source who runs it
   * @param typed the command line, without {@code /}; the command's name in any case
   * @return completed with {@code true} if a proxy command matched it
   */
  public CompletableFuture<Boolean> execute(CommandSource source, String typed) {
    if (!owns(source, typed)) {
      return CompletableFuture.completedFuture(false);
    }
    String commandLine = collapseSpaces(normalize(typed));
    CommandDispatcher<CommandSource> current = dispatcher;
    return CompletableFuture.supplyAsync(
        () -> {
          ParseResults<CommandSource> parsed = current.parse(commandLine, source);
          try {
            current.execute(parsed);
          } catch (CommandSyntaxException e) {
            source.sendMessage(Component.text(String.valueOf(e.getMessage()), NamedTextColor.RED));
          } catch (RuntimeException e) {
            logger.error("Command /{} failed", commandLine, e);
            // Rendered by the source: a player in its language, the console in the default one.
            source.sendMessage(Message.COMMAND_FAILED.of());
          }
          return true;
        },
        executor);
  }

  /**
   * Parses a command line without running it, on the calling thread: for the console, which
   * highlights the line being typed and hints at the arguments that follow.
   *
   * @param source who types it
   * @param typed the command line so far, without {@code /}
   * @return the parse
   */
  public ParseResults<CommandSource> parse(CommandSource source, String typed) {
    return dispatcher.parse(normalize(typed), source);
  }

  /**
   * Returns the usage of each child of a node that the source may use, as Brigadier writes it:
   * {@code <player> <server>}, {@code (on|off)}.
   *
   * @param node a node of a {@link #parse} result, or the {@link #root()}
   * @param source who types the command
   * @return each child's usage, in the order of the children
   */
  public Map<CommandNode<CommandSource>, String> usage(
      CommandNode<CommandSource> node, CommandSource source) {
    return dispatcher.getSmartUsage(node, source);
  }

  /**
   * Computes the suggestions for a command line being typed.
   *
   * @param source who types it
   * @param typed the command line so far, without {@code /}
   * @return the suggestions
   */
  public CompletableFuture<Suggestions> suggest(CommandSource source, String typed) {
    String commandLine = normalize(typed);
    CommandDispatcher<CommandSource> current = dispatcher;
    return CompletableFuture.supplyAsync(() -> current.parse(commandLine, source), executor)
        .thenCompose(parsed -> suggestions(parsed, source));
  }

  /**
   * Brigadier's {@link CommandDispatcher#getCompletionSuggestions}, without the nodes the source
   * may not use: Brigadier suggests every child, so a subcommand behind a permission would show its
   * name to everyone.
   */
  private static CompletableFuture<Suggestions> suggestions(
      ParseResults<CommandSource> parsed, CommandSource source) {
    CommandContextBuilder<CommandSource> context = parsed.getContext();
    String input = parsed.getReader().getString();
    SuggestionContext<CommandSource> before = context.findSuggestionContext(input.length());
    int start = Math.min(before.startPos, input.length());
    String lowerCase = input.toLowerCase(Locale.ROOT);
    List<CompletableFuture<Suggestions>> futures = new ArrayList<>();
    for (CommandNode<CommandSource> node : before.parent.getChildren()) {
      if (!node.canUse(source)) {
        continue;
      }
      try {
        futures.add(
            node.listSuggestions(
                context.build(input), new SuggestionsBuilder(input, lowerCase, start)));
      } catch (CommandSyntaxException e) {
        // No suggestion from this node, as in Brigadier.
      }
    }
    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .thenApply(
            _ -> Suggestions.merge(input, futures.stream().map(CompletableFuture::join).toList()));
  }

  /**
   * Lowercases the command's name, as commands are registered: {@code /LOBBY} runs {@code /lobby}.
   */
  private static String normalize(String commandLine) {
    int space = commandLine.indexOf(' ');
    return space < 0
        ? commandLine.toLowerCase(Locale.ROOT)
        : commandLine.substring(0, space).toLowerCase(Locale.ROOT) + commandLine.substring(space);
  }

  /**
   * Removes the spaces Brigadier would trip over: leading, trailing, and runs of them between words
   * ({@code /send Steve lobby} runs as {@code /send Steve lobby}). Only for running a command: a
   * trailing space while typing asks for the next argument's suggestions.
   */
  static String collapseSpaces(String commandLine) {
    return SPACES.matcher(commandLine.strip()).replaceAll(" ");
  }

  private static String describe(@Nullable String owner) {
    return PROXY.equals(owner) ? "the proxy" : "plugin " + owner;
  }

  @Override
  public void close() {
    if (executor instanceof ExecutorService service) {
      service.shutdownNow();
    }
  }
}
