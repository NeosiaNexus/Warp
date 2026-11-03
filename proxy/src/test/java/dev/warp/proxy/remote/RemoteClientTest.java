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
package dev.warp.proxy.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.command.WarpCommandManager;
import dev.warp.proxy.console.ComponentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The remote console's client: events, records and commands")
class RemoteClientTest {

  private static JsonObject json(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  @Nested
  @DisplayName("SseParser")
  class Parser {

    private final List<SseParser.Event> events = new ArrayList<>();
    private final SseParser parser = new SseParser(events::add);

    private void feed(String... lines) {
      for (String line : lines) {
        parser.line(line);
      }
    }

    @Test
    @DisplayName("should dispatch an event at each blank line, with its name, data and id")
    void events() {
      feed("retry: 2000", "event: hello", "data: {}", "", "id: a-1", "event: log", "data: x", "");

      assertEquals(
          List.of(new SseParser.Event("hello", "{}", null), new SseParser.Event("log", "x", "a-1")),
          events);
      assertEquals(2000, parser.retryMillis());
      assertEquals("a-1", parser.lastId());
    }

    @Test
    @DisplayName("should join data lines, skip comments, and name an unnamed event message")
    void dataAndComments() {
      feed(": keep-alive", "data: a", "data:b", "");

      assertEquals(List.of(new SseParser.Event("message", "a\nb", null)), events);
    }

    @Test
    @DisplayName("should dispatch nothing for a blank line without data")
    void empty() {
      feed("event: log", "", "");

      assertTrue(events.isEmpty());
    }
  }

  @Nested
  @DisplayName("RemoteRecords")
  class Records {

    @Test
    @DisplayName("should rebuild the event, its parameters highlighted as on the proxy")
    void parameterized() {
      LogEvent event =
          RemoteRecords.event(
              json(
                  "{\"timestamp\":\"2026-01-02T03:04:05.006Z\",\"level\":\"WARN\",\"logger\":"
                      + "\"dev.warp.proxy.WarpServer\",\"thread\":\"main\",\"message\":\"Alice"
                      + " joined\",\"format\":\"{} joined\",\"parameters\":[\"Alice\"]}"),
              null);

      assertEquals(Level.WARN, event.getLevel());
      assertEquals("dev.warp.proxy.WarpServer", event.getLoggerName());
      assertEquals(1767323045L, event.getInstant().getEpochSecond());
      ParameterizedMessage message =
          assertInstanceOf(ParameterizedMessage.class, event.getMessage());
      assertEquals("Alice joined", message.getFormattedMessage());
    }

    @Test
    @DisplayName("should prefix each line with its proxy's name when there are several")
    void prefix() {
      LogEvent event =
          RemoteRecords.event(
              json(
                  "{\"level\":\"INFO\",\"logger\":\"x\",\"message\":\"Alice joined\",\"format\":"
                      + "\"{} joined\",\"parameters\":[\"Alice\"]}"),
              "eu-1");

      assertEquals("[eu-1] Alice joined", event.getMessage().getFormattedMessage());
    }

    @Test
    @DisplayName("should fall back to the text when the format does not give it back")
    void mismatch() {
      LogEvent event =
          RemoteRecords.event(
              json(
                  "{\"level\":\"INFO\",\"logger\":\"x\",\"message\":\"[redacted]\",\"format\":"
                      + "\"{}\",\"parameters\":[\"other\"]}"),
              null);

      assertInstanceOf(SimpleMessage.class, event.getMessage());
      assertEquals("[redacted]", event.getMessage().getFormattedMessage());
    }

    @Test
    @DisplayName("should render a command's reply from its component, and append a stack trace")
    void componentsAndExceptions() {
      LogEvent reply =
          RemoteRecords.event(
              json(
                  "{\"level\":\"INFO\",\"logger\":\"dev.warp.proxy.console.Console\",\"message\":"
                      + "\"3 online\",\"component\":{\"text\":\"3 online\",\"color\":\"aqua\"}}"),
              null);
      LogEvent failure =
          RemoteRecords.event(
              json(
                  "{\"level\":\"ERROR\",\"logger\":\"x\",\"message\":\"failed\",\"exception\":"
                      + "\"java.lang.IllegalStateException: boom\"}"),
              null);

      ComponentMessage message = assertInstanceOf(ComponentMessage.class, reply.getMessage());
      assertEquals(
          "3 online", PlainTextComponentSerializer.plainText().serialize(message.component()));
      assertEquals(
          "failed\njava.lang.IllegalStateException: boom",
          failure.getMessage().getFormattedMessage());
    }

    @Test
    @DisplayName("should read the session a command's output belongs to")
    void session() {
      assertEquals(
          "abc", RemoteRecords.session(json("{\"context\":{\"console.session\":\"abc\"}}")));
      assertNull(RemoteRecords.session(json("{\"message\":\"x\"}")));
    }
  }

  @Nested
  @DisplayName("RemoteCommands")
  class Commands {

    private static final String TREE =
        "{\"commands\":[{\"literal\":\"send\",\"children\":[{\"argument\":\"who\",\"type\":"
            + "\"word\",\"children\":[{\"argument\":\"where\",\"type\":\"word\",\"executes\":"
            + "true}]}]},{\"literal\":\"glist\",\"executes\":true,\"children\":[{\"argument\":"
            + "\"server\",\"type\":\"greedy\",\"executes\":true}]}]}";

    private final WarpCommandManager commands = new WarpCommandManager(Runnable::run);
    private final List<String> ran = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private final RemoteCommands remote =
        new RemoteCommands(
            commands,
            line -> {
              ran.add(line);
              return 1;
            },
            line -> {
              asked.add(line);
              return CompletableFuture.completedFuture(
                  RemoteCommands.suggestions(
                      line,
                      json("{\"suggestions\":[{\"start\":5,\"end\":7,\"text\":\"Alice\"}]}")));
            });

    @Test
    @DisplayName("should rebuild the proxy's commands, and send the line typed to it")
    void rebuild() throws Exception {
      assertEquals(2, remote.replace(json(TREE)));

      boolean found =
          commands
              .execute(new dev.warp.proxy.console.ConsoleCommandSource(), "send Alice lobby")
              .get(5, TimeUnit.SECONDS);

      assertTrue(found);
      assertEquals(List.of("send Alice lobby"), ran);
      assertTrue(commands.hasCommand("glist"));
    }

    @Test
    @DisplayName("should ask the proxy to complete arguments, and complete names itself")
    void suggestions() throws Exception {
      remote.replace(json(TREE));
      var source = new dev.warp.proxy.console.ConsoleCommandSource();

      Suggestions names = commands.suggest(source, "gl").get(5, TimeUnit.SECONDS);
      Suggestions arguments = commands.suggest(source, "send Al").get(5, TimeUnit.SECONDS);

      assertEquals(List.of("glist"), names.getList().stream().map(Suggestion::getText).toList());
      assertEquals(
          List.of("Alice"), arguments.getList().stream().map(Suggestion::getText).toList());
      assertEquals(List.of("send Al"), asked);
    }

    @Test
    @DisplayName("should drop the commands the proxy no longer has")
    void replace() {
      remote.replace(json(TREE));

      remote.replace(json("{\"commands\":[{\"literal\":\"alert\",\"executes\":true}]}"));

      assertFalse(commands.hasCommand("send"));
      assertTrue(commands.hasCommand("alert"));
    }

    @Test
    @DisplayName("should skip suggestions whose range lies outside the line")
    void outOfRange() {
      Suggestions suggestions =
          RemoteCommands.suggestions(
              "ab", json("{\"suggestions\":[{\"start\":0,\"end\":9,\"text\":\"x\"}]}"));

      assertTrue(suggestions.isEmpty());
    }
  }
}
