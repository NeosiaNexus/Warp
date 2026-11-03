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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.command.CommandSource;
import dev.warp.proxy.command.WarpCommandManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jline.reader.Candidate;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The console's command line")
class CommandInputTest {

  private final WarpCommandManager commands = new WarpCommandManager(Runnable::run);
  private final List<String> ran = new CopyOnWriteArrayList<>();
  private final List<String> replies = new CopyOnWriteArrayList<>();
  private final CommandSource source =
      new CommandSource() {
        @Override
        public boolean hasPermission(String permission) {
          return true;
        }

        @Override
        public void sendMessage(Component message) {
          replies.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
      };

  private static SuggestionProvider<CommandSource> names(String... names) {
    return (context, builder) -> {
      for (String name : names) {
        if (name.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
          builder.suggest(name, new LiteralMessage("a " + name));
        }
      }
      return builder.buildFuture();
    };
  }

  @BeforeEach
  void register() {
    Command<CommandSource> record =
        context -> {
          ran.add(context.getInput());
          return Command.SINGLE_SUCCESS;
        };
    var _ =
        commands.register(
            "warp",
            LiteralArgumentBuilder.<CommandSource>literal("send")
                .then(
                    RequiredArgumentBuilder.<CommandSource, String>argument(
                            "player", StringArgumentType.word())
                        .suggests(names("Steve", "Alex"))
                        .then(
                            RequiredArgumentBuilder.<CommandSource, String>argument(
                                    "server", StringArgumentType.word())
                                .suggests(names("lobby", "survival"))
                                .executes(record))));
    var _ =
        commands.register(
            "warp",
            LiteralArgumentBuilder.<CommandSource>literal("lockdown")
                .then(LiteralArgumentBuilder.<CommandSource>literal("on").executes(record))
                .then(LiteralArgumentBuilder.<CommandSource>literal("off").executes(record)));
    var _ =
        commands.register(
            "warp", LiteralArgumentBuilder.<CommandSource>literal("glist").executes(record));
    var _ =
        commands.register(
            "warp",
            LiteralArgumentBuilder.<CommandSource>literal("stats")
                .executes(record)
                .then(LiteralArgumentBuilder.<CommandSource>literal("full").executes(record))
                .then(LiteralArgumentBuilder.<CommandSource>literal("short").executes(record)));
  }

  private CommandInput.Analysis analyse(String line) {
    return CommandInput.analyse(commands, source, line);
  }

  @Nested
  @DisplayName("highlighting")
  class Highlighting {

    @Test
    @DisplayName("marks a known command and its arguments")
    void known() {
      assertEquals(
          List.of(
              new CommandInput.Span(0, 4, Style.ACCENT),
              new CommandInput.Span(5, 10, Style.ARGUMENT),
              new CommandInput.Span(11, 16, Style.ARGUMENT)),
          analyse("send Steve lobby").spans());
    }

    @Test
    @DisplayName("marks an unknown command as an error")
    void unknown() {
      assertEquals(List.of(new CommandInput.Span(0, 4, Style.BAD)), analyse("nope x").spans());
    }

    @Test
    @DisplayName("leaves a command being typed alone")
    void typing() {
      assertTrue(analyse("gl").spans().isEmpty());
      assertEquals(
          List.of(new CommandInput.Span(0, 8, Style.ACCENT)), analyse("lockdown o").spans());
    }

    @Test
    @DisplayName("marks what does not parse as an error")
    void error() {
      assertEquals(
          new CommandInput.Span(9, 14, Style.BAD), analyse("lockdown maybe").spans().getLast());
    }

    @Test
    @DisplayName("skips a leading slash")
    void slash() {
      assertEquals(new CommandInput.Span(1, 6, Style.ACCENT), analyse("/glist").spans().getFirst());
    }
  }

  @Nested
  @DisplayName("hints")
  class Hints {

    @Test
    @DisplayName("show the arguments after a command")
    void afterCommand() {
      CommandInput.Analysis analysis = analyse("send ");

      assertEquals("<player> <server>", analysis.hint());
      assertFalse(analysis.midWord());
      assertEquals("<player> <server>", CommandInput.tailTip("send ", analysis));
    }

    @Test
    @DisplayName("show what follows the word being typed, in JLine's tail tip convention")
    void midWord() {
      CommandInput.Analysis analysis = analyse("send Ste");

      assertEquals("<server>", analysis.hint());
      assertTrue(analysis.midWord());
      // JLine drops the tail tip's first word in the middle of a word.
      assertEquals("_ <server>", CommandInput.tailTip("send Ste", analysis));
      // ...unless the word has a dash, in which case it puts a space in front.
      assertEquals("<server>", CommandInput.tailTip("send Ste-ve", analysis));
    }

    @Test
    @DisplayName("list literal choices")
    void choices() {
      assertEquals("(on|off)", analyse("lockdown ").hint());
    }

    @Test
    @DisplayName("bracket the choices of a command that also runs alone")
    void optionalChoices() {
      assertEquals("[full|short]", analyse("stats ").hint());
    }

    @Test
    @DisplayName("are empty at the end of a command")
    void complete() {
      assertEquals("", analyse("send Steve lobby ").hint());
    }
  }

  @Nested
  @DisplayName("completion")
  class Completion {

    private final CommandLineSupport support =
        new CommandLineSupport(
            commands, source, Palette.of(ColorDepth.TRUE_COLOR, Theme.DARK, false));

    @Test
    @DisplayName("offers the dispatcher's suggestions with their tooltips")
    void suggestions() throws Exception {
      List<Candidate> candidates = support.candidates("send S", 6, 5);

      assertEquals(List.of("Steve"), candidates.stream().map(Candidate::value).toList());
      assertEquals("a Steve", candidates.getFirst().descr());
    }

    @Test
    @DisplayName("completes command names, with or without a slash")
    void commandNames() throws Exception {
      assertEquals(
          List.of("glist"), support.candidates("gl", 2, 0).stream().map(Candidate::value).toList());
      assertEquals(
          List.of("survival"),
          support.candidates("/send Steve s", 13, 12).stream().map(Candidate::value).toList());
    }

    @Test
    @DisplayName("styles the line by its analysis")
    void render() {
      AttributedString line = support.render("send Steve", analyse("send Steve"));

      assertEquals("send Steve", line.toString());
      assertEquals(
          Palette.of(ColorDepth.TRUE_COLOR, Theme.DARK, false).jline(Style.ACCENT),
          line.styleAt(0));
      assertEquals(
          Palette.of(ColorDepth.TRUE_COLOR, Theme.DARK, false).jline(Style.ARGUMENT),
          line.styleAt(5));
    }
  }

  @Nested
  @DisplayName("running commands")
  class Running {

    @TempDir private Path directory;

    private final AtomicInteger shutdowns = new AtomicInteger();

    private WarpConsole console(String input) {
      return new WarpConsole(
          commands,
          source,
          directory.resolve("history"),
          shutdowns::incrementAndGet,
          new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("runs each line of standard input, then stops reading at its end, quietly")
    void plainInput() throws InterruptedException {
      WarpConsole console = console("glist\n/send Steve lobby\n");

      console.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (ran.size() < 2 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      Thread.sleep(100);

      assertEquals(List.of("glist", "send Steve lobby"), ran);
      assertEquals(0, shutdowns.get(), "the end of standard input must not stop the proxy");
      console.close();
    }

    @Test
    @DisplayName("removes control characters from what is typed before running it")
    void controlCharacters() {
      WarpConsole console = console("");
      console.start();

      console.run("\u0007gl\u001B[1mist\r");

      assertEquals(List.of("glist"), ran);
    }

    @Test
    @DisplayName("says a command is unknown, and which one was meant")
    void unknown() {
      WarpConsole console = console("");
      console.start();

      console.run("glsit");

      assertTrue(replies.getLast().startsWith("Unknown command \"glsit\". Did you mean glist?"));
      assertTrue(replies.getLast().contains("Commands: glist, lockdown, send, stats"));
    }

    @Test
    @DisplayName("gives the usage of an incomplete command instead of running it")
    void incomplete() {
      WarpConsole console = console("");
      console.start();

      console.run("lockdown");

      assertEquals("Incomplete command. Usage: lockdown (on|off)", replies.getLast());
      assertTrue(ran.isEmpty());
      assertNull(CommandInput.incompleteUsage(commands, source, "lockdown on"));
    }

    @Test
    @DisplayName("finds the closest command name within two edits")
    void closest() {
      assertEquals("glist", CommandInput.closest(List.of("glist", "send"), "glsit"));
      assertNull(CommandInput.closest(List.of("glist", "send"), "teleport"));
    }
  }
}
