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

import dev.warp.proxy.command.ProxyCommands;
import dev.warp.proxy.command.WarpCommandManager;
import dev.warp.proxy.console.ConsoleCommandSource;
import dev.warp.proxy.console.WarpConsole;
import dev.warp.proxy.logging.LogCode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.brigadier.suggestion.Suggestions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code console attach}: the live log of one proxy or several, rendered by the local console as
 * the proxy's own console renders it, and, for the proxy chosen for commands, the console's prompt
 * (highlighting, hints, completion, history) running them there.
 *
 * <p>Each proxy has a thread of its own, which reconnects after any loss with a growing delay (1 s
 * to 30 s) and resumes where it left off: the proxy replays what was missed, or says how much it no
 * longer has. A refused token is never retried, so that the client cannot lock itself out.
 */
final class AttachSession {

  private static final Logger logger = LoggerFactory.getLogger("dev.warp.proxy.remote.Console");

  private static final long MIN_BACKOFF_MILLIS = 1000;
  private static final long MAX_BACKOFF_MILLIS = 30_000;

  private final List<ConsoleHttp> targets;
  private final @Nullable ConsoleHttp commandTarget;
  private final Map<String, String> query;
  private final String session;
  private final boolean prefixed;
  private final Path historyFile;
  private final CountDownLatch finished = new CountDownLatch(1);
  private final AtomicInteger streaming = new AtomicInteger();
  private final ConsoleCommandSource source = new ConsoleCommandSource();
  private volatile @Nullable RemoteCommands remoteCommands;
  private volatile boolean running = true;

  /**
   * Creates a session.
   *
   * @param targets the proxies whose log is streamed
   * @param commandTarget the proxy that runs the commands typed, or {@code null} for a read-only
   *     session
   * @param query the filter and the replay
   * @param session this client's session, as its requests name it
   * @param historyFile where the prompt keeps its history
   */
  AttachSession(
      List<ConsoleHttp> targets,
      @Nullable ConsoleHttp commandTarget,
      Map<String, String> query,
      String session,
      Path historyFile) {
    this.targets = List.copyOf(targets);
    this.commandTarget = commandTarget;
    this.query = Map.copyOf(query);
    this.session = session;
    this.prefixed = targets.size() > 1;
    this.historyFile = historyFile;
  }

  /**
   * Runs until Ctrl+C or Ctrl+D at the prompt, or until no proxy is left to stream.
   *
   * @return the exit code: 0 when the operator left, 3 when every proxy refused the token
   * @throws InterruptedException if interrupted
   */
  int run() throws InterruptedException {
    ConsoleHttp commanding = commandTarget;
    if (commanding != null) {
      startPrompt(commanding);
    }
    streaming.set(targets.size());
    for (ConsoleHttp target : targets) {
      var _ =
          Thread.ofVirtual()
              .name("warp-console-" + target.target().name())
              .start(() -> follow(target));
    }
    finished.await();
    running = false;
    return commanding == null ? 3 : 0;
  }

  // ---------------------------------------------------------------------------
  // The prompt
  // ---------------------------------------------------------------------------

  private void startPrompt(ConsoleHttp target) {
    WarpCommandManager commands = new WarpCommandManager();
    RemoteCommands remote =
        new RemoteCommands(
            commands,
            line -> runCommand(target, line),
            line -> suggest(target, line),
            List.of(ProxyCommands.clientLog()));
    try {
      Files.createDirectories(historyFile.getParent());
    } catch (IOException e) {
      logger.debug("Cannot create {}", historyFile.getParent(), e);
    }
    this.remoteCommands = remote;
    WarpConsole console =
        new WarpConsole(
            commands,
            source,
            historyFile,
            () -> {
              running = false;
              finished.countDown();
            });
    // The commands first: a line typed (or piped) at once must find them.
    loadCommands(target);
    console.start();
  }

  private void loadCommands(ConsoleHttp target) {
    RemoteCommands remote = remoteCommands;
    if (remote == null) {
      return;
    }
    try {
      ConsoleHttp.Answer answer = target.commands();
      if (answer.status() == 200) {
        int count = remote.replace(answer.body());
        logger.debug("{} lets this token run {} commands", target.target().name(), count);
      } else {
        logger.warn(
            LogCode.REMOTE_CONSOLE_CLIENT_NO_COMMANDS.marker(),
            "{} refuses to run commands for this token: {}",
            target.target().name(),
            answer.error());
      }
    } catch (IOException e) {
      logger.debug("Cannot read the commands of {}: {}", target.target().name(), e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Runs a line on the proxy and shows its answer as the console shows a command's reply. */
  private int runCommand(ConsoleHttp target, String line) {
    String name = target.target().name();
    try {
      ConsoleHttp.Answer answer = target.command(line);
      if (answer.status() != 200) {
        source.sendMessage(
            Component.text(name + " refused the command: " + answer.error(), NamedTextColor.RED));
        return 0;
      }
      JsonElement output = answer.body().get("output");
      if (output != null && output.isJsonArray()) {
        for (JsonElement element : output.getAsJsonArray()) {
          source.sendMessage(prefix(line(element)));
        }
      }
      JsonElement error = answer.body().get("error");
      if (error != null && error.isJsonPrimitive()) {
        source.sendMessage(Component.text(name + ": " + error.getAsString(), NamedTextColor.RED));
      }
      JsonElement done = answer.body().get("done");
      if (done != null && done.isJsonPrimitive() && !done.getAsBoolean()) {
        source.sendMessage(
            Component.text(
                "Still running on " + name + ": what it says next appears in the log",
                NamedTextColor.GRAY));
      }
      return 1;
    } catch (IOException e) {
      source.sendMessage(
          Component.text(
              name
                  + " cannot be reached ("
                  + ConsoleHttp.explain(e)
                  + "): the command was not sent",
              NamedTextColor.RED));
      return 0;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return 0;
    }
  }

  private CompletableFuture<Suggestions> suggest(ConsoleHttp target, String line) {
    return target
        .suggest(line)
        .thenApply(
            answer ->
                answer.status() == 200
                    ? RemoteCommands.suggestions(line, answer.body())
                    : Suggestions.empty().join());
  }

  private Component line(JsonElement element) {
    if (element.isJsonObject()) {
      JsonElement component = element.getAsJsonObject().get("component");
      if (component != null && component.isJsonObject()) {
        try {
          return GsonComponentSerializer.gson().deserializeFromTree(component);
        } catch (RuntimeException e) {
          // Its text, then.
        }
      }
      JsonElement text = element.getAsJsonObject().get("text");
      if (text != null && text.isJsonPrimitive()) {
        return Component.text(text.getAsString());
      }
    }
    return Component.empty();
  }

  private Component prefix(Component line) {
    ConsoleHttp target = commandTarget;
    return prefixed && target != null
        ? Component.text("[" + target.target().name() + "] ", NamedTextColor.DARK_GRAY).append(line)
        : line;
  }

  // ---------------------------------------------------------------------------
  // The streams
  // ---------------------------------------------------------------------------

  /** Follows one proxy's log until the session ends or the proxy refuses the token. */
  private void follow(ConsoleHttp target) {
    String name = target.target().name();
    String[] lastEventId = {null};
    String[] instance = {null};
    long backoff = MIN_BACKOFF_MILLIS;
    boolean wasConnected = false;
    try {
      while (running) {
        long wait;
        try {
          HttpResponse<InputStream> response = target.stream(query, lastEventId[0]);
          int status = response.statusCode();
          if (status == 200) {
            backoff = MIN_BACKOFF_MILLIS;
            wasConnected = true;
            read(target, response.body(), lastEventId, instance);
            if (!running) {
              return;
            }
            logger.warn(
                LogCode.REMOTE_CONSOLE_CLIENT_UNREACHABLE.marker(), "Lost {}: reconnecting", name);
            wait = MIN_BACKOFF_MILLIS;
          } else {
            String error = errorOf(response);
            if (status == 401 || status == 403) {
              logger.error(
                  LogCode.REMOTE_CONSOLE_CLIENT_REFUSED.marker(),
                  "{} refused the log stream: {}",
                  name,
                  error);
              return;
            }
            if (status == 429) {
              wait =
                  response.headers().firstValueAsLong("Retry-After").stream()
                      .map(seconds -> seconds * 1000)
                      .findFirst()
                      .orElse(MAX_BACKOFF_MILLIS);
              logger.warn(
                  LogCode.REMOTE_CONSOLE_CLIENT_THROTTLED.marker(),
                  "{} refused the log stream for now: {}",
                  name,
                  error);
            } else {
              wait = backoff;
              logger.warn(
                  LogCode.REMOTE_CONSOLE_CLIENT_UNEXPECTED_ANSWER.marker(),
                  "{} answered {}: {}; retrying",
                  name,
                  status,
                  error);
            }
          }
        } catch (IOException e) {
          wait = backoff;
          logger.warn(
              LogCode.REMOTE_CONSOLE_CLIENT_UNREACHABLE.marker(),
              "{} {} ({}); retrying in {} s",
              name,
              wasConnected ? "is unreachable" : "cannot be reached",
              describe(e),
              Math.max(1, wait / 1000));
        }
        backoff = Math.min(MAX_BACKOFF_MILLIS, backoff * 2);
        Thread.sleep(wait + ThreadLocalRandom.current().nextLong(250));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      if (streaming.decrementAndGet() == 0 && commandTarget == null) {
        finished.countDown();
      }
    }
  }

  private void read(ConsoleHttp target, InputStream body, String[] lastEventId, String[] instance)
      throws IOException {
    SseParser parser = new SseParser(event -> handle(target, event, lastEventId, instance));
    try (BufferedReader lines =
        new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
      String line;
      while (running && (line = lines.readLine()) != null) {
        parser.line(line);
        String id = parser.lastId();
        if (id != null) {
          lastEventId[0] = id;
        }
      }
    }
  }

  private void handle(
      ConsoleHttp target, SseParser.Event event, String[] lastEventId, String[] instance) {
    String name = target.target().name();
    JsonObject data;
    try {
      JsonElement json = JsonParser.parseString(event.data());
      data = json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
    } catch (JsonParseException e) {
      return;
    }
    switch (event.name()) {
      case "log" -> {
        if (!session.equals(RemoteRecords.session(data))) {
          append(RemoteRecords.event(data, prefixed ? name : null));
        }
      }
      case "hello" -> {
        String now = text(data, "instance");
        String before = instance[0];
        instance[0] = now;
        if (before == null) {
          logger.info(
              "Attached to {}{} (Warp {}) as {}, {}{}",
              prefixed ? name + " at " : "",
              ConsoleHttp.describe(target.target().base()),
              text(data, "version"),
              text(data, "user"),
              scopes(data),
              target == commandTarget ? "; commands typed here run there" : "");
        } else if (!before.equals(now)) {
          lastEventId[0] = null;
          logger.info("{} restarted: attached again", name);
        } else {
          logger.info("Reattached to {}", name);
        }
        if (target == commandTarget) {
          loadCommands(target);
        }
      }
      case "gap" ->
          logger.warn(
              LogCode.REMOTE_CONSOLE_CLIENT_LINES_MISSED.marker(),
              "{}: {} lines missed ({})",
              name,
              text(data, "missed"),
              "slow".equals(text(data, "reason"))
                  ? "this client read too slowly"
                  : "no longer kept by the proxy");
      case "commands" -> {
        if (target == commandTarget) {
          loadCommands(target);
        }
      }
      case "bye" ->
          logger.info(
              "{} is {}", name, Optional.ofNullable(text(data, "reason")).orElse("leaving"));
      default -> {
        // Events of a newer proxy.
      }
    }
  }

  /** Hands an event to the console's appender, which renders it as the proxy's console does. */
  private static void append(LogEvent event) {
    if (LogManager.getContext(false) instanceof LoggerContext context) {
      Appender console = context.getConfiguration().getAppender("Console");
      if (console != null) {
        console.append(event);
      }
    }
  }

  private static String errorOf(HttpResponse<InputStream> response) {
    try (InputStream in = response.body()) {
      String body = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
      JsonElement json = JsonParser.parseString(body);
      JsonElement error = json.isJsonObject() ? json.getAsJsonObject().get("error") : null;
      if (error != null && error.isJsonPrimitive()) {
        return error.getAsString();
      }
    } catch (IOException | RuntimeException e) {
      // The status says enough.
    }
    return "HTTP " + response.statusCode();
  }

  private static String describe(IOException e) {
    return ConsoleHttp.explain(e);
  }

  private static String scopes(JsonObject hello) {
    JsonElement scopes = hello.get("scopes");
    if (scopes == null || !scopes.isJsonArray() || scopes.getAsJsonArray().isEmpty()) {
      return "no scope";
    }
    List<String> names = new java.util.ArrayList<>();
    scopes.getAsJsonArray().forEach(scope -> names.add(scope.getAsString()));
    return (names.contains("commands") ? "reads the log and runs commands" : "reads the log")
        + " ("
        + String.join(", ", names)
        + ")";
  }

  private static @Nullable String text(JsonObject json, String name) {
    JsonElement value = json.get(name);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
  }
}
