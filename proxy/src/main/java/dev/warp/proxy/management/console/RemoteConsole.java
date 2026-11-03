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
package dev.warp.proxy.management.console;

import dev.warp.proxy.command.WarpCommandManager;
import dev.warp.proxy.config.Durations;
import dev.warp.proxy.config.RemoteConsoleConfig;
import dev.warp.proxy.config.RemoteConsoleConfig.Scope;
import dev.warp.proxy.config.RemoteConsoleConfig.Token;
import dev.warp.proxy.console.Ansi;
import dev.warp.proxy.i18n.PlayerMessages;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.apache.logging.log4j.CloseableThreadContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The remote console on the management server, for hosts without a terminal:
 *
 * <ul>
 *   <li>{@code GET /console/logs}: the live log as Server-Sent Events, after a replay of the last
 *       lines ({@code ?replay=N}, or from {@code Last-Event-ID} on a reconnection), filtered by
 *       {@code ?level=}, {@code ?tags=} and {@code ?grep=}; scope {@code logs}
 *   <li>{@code POST /console/command}: runs a command (the body, or {@code {"command": ...}}) and
 *       answers what it replied; scope {@code commands}
 *   <li>{@code POST /console/suggest}: completes a command line ({@code {"line": ...}}); scope
 *       {@code commands}
 *   <li>{@code GET /console/commands}: the commands the token may run, as a tree the client
 *       rebuilds; scope {@code commands}
 * </ul>
 *
 * <p>Every request carries a bearer token of {@code management.console.tokens}, never the drain
 * token; wrong tokens lock their address out (see {@link ConsoleAuth}). Without TLS, the {@code
 * commands} scope is refused outside loopback and the trusted networks. Every command, every
 * attachment and every refusal is logged, with who and from where.
 */
public final class RemoteConsole {

  private static final Logger logger = LoggerFactory.getLogger(RemoteConsole.class);

  /** The audit trail: who did what, from where. */
  private static final Logger AUDIT =
      LoggerFactory.getLogger("dev.warp.proxy.management.console.Audit");

  /** The header a client names its session with, so that it does not see its output twice. */
  public static final String SESSION_HEADER = "Warp-Console-Session";

  /** The header a client names the person at its keyboard with, for the audit log. */
  public static final String OPERATOR_HEADER = "Warp-Console-Operator";

  /** Lines replayed when the client does not say. */
  static final int DEFAULT_REPLAY = 100;

  /** How long a command may run before its answer goes out without waiting for it. */
  static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

  /** After a command, how long its output may stay quiet before the answer goes out. */
  static final Duration SETTLE = Duration.ofMillis(150);

  /** The longest a command's answer waits for late output after the command returned. */
  static final Duration SETTLE_LIMIT = Duration.ofSeconds(2);

  private static final Duration SUGGEST_TIMEOUT = Duration.ofSeconds(2);
  private static final long COMMANDS_CHANGED_DELAY_MILLIS = 500;
  private static final int MAX_COMMAND_LENGTH = 1024;
  private static final String JSON = "application/json; charset=utf-8";
  private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final String ENDPOINTS =
      "/console/logs, /console/command, /console/suggest and /console/commands";

  private final RemoteConsoleConfig config;
  private final WarpCommandManager commands;
  private final Supplier<PlayerMessages> messages;
  private final String version;
  private final String instance;
  private final LogHistory history;
  private final ConsoleAuth auth;
  private final Set<LogStream> streams = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean commandsChangePending = new AtomicBoolean();
  private volatile boolean stopping;

  /**
   * Creates the console, not started.
   *
   * @param config the {@code management.console} section
   * @param commands the proxy's commands
   * @param messages the proxy's messages, which render what commands answer
   * @param version the proxy's version, which the {@code hello} event tells
   * @param secrets values the streamed log must never show (tokens, the forwarding secret)
   */
  public RemoteConsole(
      RemoteConsoleConfig config,
      WarpCommandManager commands,
      Supplier<PlayerMessages> messages,
      String version,
      Collection<String> secrets) {
    this(config, commands, messages, version, secrets, System::nanoTime);
  }

  /**
   * Creates the console with a clock.
   *
   * @param config the section
   * @param commands the proxy's commands
   * @param messages the proxy's messages
   * @param version the proxy's version
   * @param secrets values the streamed log must never show
   * @param clock the clock of the lockout and the rate limits
   */
  RemoteConsole(
      RemoteConsoleConfig config,
      WarpCommandManager commands,
      Supplier<PlayerMessages> messages,
      String version,
      Collection<String> secrets,
      LongSupplier clock) {
    this.config = config;
    this.commands = commands;
    this.messages = messages;
    this.version = version;
    this.instance = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    List<String> masked = new ArrayList<>(secrets);
    for (Token token : config.tokens()) {
      String secret = token.secret();
      if (secret != null) {
        masked.add(secret);
      }
    }
    this.history = new LogHistory(config.history(), new Redactor(masked));
    this.auth = new ConsoleAuth(config, clock);
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /** Starts keeping the log, so that the first clients can replay the startup. */
  public void start() {
    history.install();
  }

  /** Ends every stream with a {@code bye} event and stops keeping the log. Idempotent. */
  public void stop() {
    stopping = true;
    for (LogStream stream : streams) {
      stream.close("stopping");
    }
    history.uninstall();
  }

  /**
   * Tells the clients that may run commands that the commands changed, at most twice a second: a
   * plugin registering ten commands sends one notice.
   */
  public void commandsChanged() {
    if (streams.isEmpty() || !commandsChangePending.compareAndSet(false, true)) {
      return;
    }
    CompletableFuture.delayedExecutor(COMMANDS_CHANGED_DELAY_MILLIS, TimeUnit.MILLISECONDS)
        .execute(
            () -> {
              commandsChangePending.set(false);
              streams.forEach(LogStream::commandsChanged);
            });
  }

  /**
   * Describes the console for the startup log, without any token.
   *
   * @param tls whether the management server speaks TLS
   * @return such as {@code tokens ops [logs, commands]; commands from loopback only}
   */
  public String describe(boolean tls) {
    List<String> tokens = new ArrayList<>();
    for (Token token : config.tokens()) {
      tokens.add(token.toString());
    }
    String commandsFrom;
    if (!config.runsCommands()) {
      commandsFrom = "no token runs commands";
    } else if (tls) {
      commandsFrom = "commands over TLS";
    } else if (config.trustedNetworks().isEmpty()) {
      commandsFrom = "commands from loopback only (no TLS)";
    } else {
      commandsFrom = "commands from loopback and " + config.trustedNetworks() + " only (no TLS)";
    }
    return "tokens " + String.join(", ", tokens) + "; " + commandsFrom;
  }

  /**
   * Returns how many clients are attached to the live log.
   *
   * @return the count
   */
  int streams() {
    return streams.size();
  }

  /**
   * Returns the history the streams read, for tests.
   *
   * @return the history
   */
  LogHistory history() {
    return history;
  }

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  /** An endpoint: its method and the scope it needs. */
  private enum Endpoint {
    LOGS("GET", Scope.LOGS),
    COMMAND("POST", Scope.COMMANDS),
    SUGGEST("POST", Scope.COMMANDS),
    TREE("GET", Scope.COMMANDS);

    private final String method;
    private final Scope scope;

    Endpoint(String method, Scope scope) {
      this.method = method;
      this.scope = scope;
    }
  }

  /**
   * Answers a request under {@code /console}. On the management event loop.
   *
   * @param ctx the connection
   * @param request the request
   * @param uri its path and query
   */
  public void handle(ChannelHandlerContext ctx, FullHttpRequest request, QueryStringDecoder uri) {
    Endpoint endpoint =
        switch (uri.path()) {
          case "/console/logs" -> Endpoint.LOGS;
          case "/console/command" -> Endpoint.COMMAND;
          case "/console/suggest" -> Endpoint.SUGGEST;
          case "/console/commands" -> Endpoint.TREE;
          default -> null;
        };
    if (endpoint == null) {
      respond(ctx, HttpResponseStatus.NOT_FOUND, error("no such endpoint; try " + ENDPOINTS));
      return;
    }
    if (!endpoint.method.equals(request.method().name())) {
      FullHttpResponse response =
          response(HttpResponseStatus.METHOD_NOT_ALLOWED, error("use " + endpoint.method));
      response.headers().set(HttpHeaderNames.ALLOW, endpoint.method);
      send(ctx, response);
      return;
    }
    if (stopping) {
      respond(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, error("the proxy is shutting down"));
      return;
    }
    InetSocketAddress peer = (InetSocketAddress) ctx.channel().remoteAddress();
    Token token = authenticate(ctx, request, peer, uri.path());
    if (token == null) {
      return;
    }
    String who = who(token, request, peer);
    if (!token.allows(endpoint.scope)) {
      AUDIT.warn(
          "Remote console: refused {} {}: the token lacks the {} scope",
          who,
          uri.path(),
          endpoint.scope.configName());
      respond(
          ctx,
          HttpResponseStatus.FORBIDDEN,
          error("this token lacks the " + endpoint.scope.configName() + " scope"));
      return;
    }
    if (endpoint.scope == Scope.COMMANDS && !secure(ctx, peer.getAddress())) {
      AUDIT.warn(
          "Remote console: refused {} {}: commands without TLS are accepted from loopback and"
              + " management.console.trusted-networks only",
          who,
          uri.path());
      respond(
          ctx,
          HttpResponseStatus.FORBIDDEN,
          error(
              "commands without TLS are accepted from loopback and the trusted networks only;"
                  + " connect over TLS (management.tls), or through an SSH tunnel or kubectl"
                  + " port-forward"));
      return;
    }
    switch (endpoint) {
      case LOGS -> stream(ctx, request, uri, token, who);
      case COMMAND -> command(ctx, request, token, who);
      case SUGGEST -> suggest(ctx, request, token);
      case TREE ->
          respond(
              ctx,
              HttpResponseStatus.OK,
              CommandTreeJson.of(commands.root(), source(token, request)));
    }
  }

  /** Checks the token, answering 401 or 429 without one; returns it, or {@code null}. */
  private @Nullable Token authenticate(
      ChannelHandlerContext ctx, FullHttpRequest request, InetSocketAddress peer, String path) {
    String header = request.headers().get(HttpHeaderNames.AUTHORIZATION);
    switch (auth.authenticate(peer.getAddress(), header)) {
      case ConsoleAuth.Result.Granted granted -> {
        return granted.token();
      }
      case ConsoleAuth.Result.LockedOut locked -> {
        logger.debug("Remote console: {} is locked out, refused {}", address(peer), path);
        FullHttpResponse response =
            response(
                HttpResponseStatus.TOO_MANY_REQUESTS,
                error("too many wrong tokens from this address; try again later"));
        response
            .headers()
            .setInt(
                HttpHeaderNames.RETRY_AFTER, (int) Math.max(1, locked.retryAfter().toSeconds()));
        send(ctx, response);
      }
      case ConsoleAuth.Result.Missing _ -> unauthorized(ctx, "missing bearer token");
      case ConsoleAuth.Result.Wrong wrong -> {
        if (wrong.lockedOut()) {
          AUDIT.warn(
              "Remote console: locked out {} for {} after {} wrong tokens (last on {})",
              ConsoleAuth.key(peer.getAddress()),
              Durations.format(config.lockoutDuration()),
              wrong.failures(),
              path);
        } else {
          AUDIT.warn(
              "Remote console: refused a wrong token from {} on {} ({} of {} before a lockout)",
              address(peer),
              path,
              wrong.failures(),
              wrong.limit());
        }
        unauthorized(ctx, "wrong bearer token");
      }
    }
    return null;
  }

  private void unauthorized(ChannelHandlerContext ctx, String message) {
    FullHttpResponse response = response(HttpResponseStatus.UNAUTHORIZED, error(message));
    response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer realm=\"warp-console\"");
    send(ctx, response);
  }

  private boolean secure(ChannelHandlerContext ctx, InetAddress address) {
    return ctx.pipeline().get(SslHandler.class) != null
        || address.isLoopbackAddress()
        || config.trustedNetworks().contains(address);
  }

  // ---------------------------------------------------------------------------
  // GET /console/logs
  // ---------------------------------------------------------------------------

  private void stream(
      ChannelHandlerContext ctx,
      FullHttpRequest request,
      QueryStringDecoder uri,
      Token token,
      String who) {
    LogFilter filter;
    int replay;
    try {
      filter = LogFilter.parse(uri.parameters());
      replay = replayCount(uri);
    } catch (IllegalArgumentException e) {
      respond(ctx, HttpResponseStatus.BAD_REQUEST, error(e.getMessage()));
      return;
    }
    if (!auth.openStream(token)) {
      respond(
          ctx,
          HttpResponseStatus.TOO_MANY_REQUESTS,
          error("too many streams open; close one first"));
      return;
    }
    long after = resumeAfter(request.headers().get("Last-Event-ID"));
    boolean resumed = after >= 0;
    // Held open: the client sends nothing more.
    if (ctx.pipeline().get(ReadTimeoutHandler.class) != null) {
      ctx.pipeline().remove(ReadTimeoutHandler.class);
    }
    HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    response
        .headers()
        .set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=utf-8")
        .set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE)
        .set("X-Accel-Buffering", "no");
    HttpUtil.setTransferEncodingChunked(response, true);
    var _ = ctx.writeAndFlush(response);

    LogStream stream = new LogStream(ctx.channel(), filter, instance, token.allows(Scope.COMMANDS));
    streams.add(stream);
    history.subscribe(stream);
    LogHistory.Replay lines =
        resumed
            ? history.replay(after, filter, config.history())
            : history.replay(0, filter, replay);
    JsonObject hello = new JsonObject();
    hello.addProperty("proxy", "Warp");
    hello.addProperty("version", version);
    hello.addProperty("instance", instance);
    hello.addProperty("user", token.name());
    JsonArray scopes = new JsonArray();
    for (Scope scope : Scope.values()) {
      if (token.allows(scope)) {
        scopes.add(scope.configName());
      }
    }
    hello.add("scopes", scopes);
    hello.addProperty("history", config.history());
    hello.addProperty("resumed", resumed);
    hello.add("filter", filter.json());
    stream.open(hello, lines, resumed);
    long started = System.nanoTime();
    AUDIT.info(
        "Remote console: {} attached to the log ({}{})",
        who,
        describeFilter(filter),
        resumed ? ", resumed" : "");
    var _ =
        ctx.channel()
            .closeFuture()
            .addListener(
                _ -> {
                  history.unsubscribe(stream);
                  streams.remove(stream);
                  auth.closeStream(token);
                  long sent = stream.closed();
                  AUDIT.info(
                      "Remote console: {} detached from the log after {} ({} lines)",
                      who,
                      Durations.format(
                          Duration.ofSeconds(
                              TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))),
                      sent);
                });
  }

  private int replayCount(QueryStringDecoder uri) {
    List<String> values = uri.parameters().get("replay");
    if (values == null || values.isEmpty()) {
      return Math.min(DEFAULT_REPLAY, config.history());
    }
    try {
      int count = Integer.parseInt(values.getLast().strip());
      if (count < 0) {
        throw new IllegalArgumentException("replay must be 0 or more");
      }
      return Math.min(count, config.history());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("replay must be a number of lines", e);
    }
  }

  /**
   * Reads {@code Last-Event-ID}: the line after which a reconnecting client resumes, if it was read
   * from this run of the proxy.
   *
   * @return the line's number, or -1 to start afresh
   */
  long resumeAfter(@Nullable String lastEventId) {
    if (lastEventId == null) {
      return -1;
    }
    String prefix = instance + "-";
    if (!lastEventId.startsWith(prefix)) {
      return -1;
    }
    try {
      long id = Long.parseLong(lastEventId.substring(prefix.length()));
      return id >= 0 && id <= history.last() ? id : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static String describeFilter(LogFilter filter) {
    StringBuilder text =
        new StringBuilder("level ").append(filter.level().name().toLowerCase(Locale.ROOT));
    if (!filter.include().isEmpty() || !filter.exclude().isEmpty()) {
      List<String> tags = new ArrayList<>(filter.include());
      filter.exclude().forEach(tag -> tags.add("-" + tag));
      text.append(", tags ").append(String.join(",", tags));
    }
    if (filter.grep() != null) {
      text.append(", grep \"").append(filter.grep()).append('"');
    }
    return text.toString();
  }

  // ---------------------------------------------------------------------------
  // POST /console/command and /console/suggest
  // ---------------------------------------------------------------------------

  private void command(
      ChannelHandlerContext ctx, FullHttpRequest request, Token token, String who) {
    String line;
    try {
      line = commandLine(request, "command");
    } catch (IllegalArgumentException e) {
      respond(ctx, HttpResponseStatus.BAD_REQUEST, error(e.getMessage()));
      return;
    }
    if (!auth.allowCommand(token)) {
      AUDIT.warn(
          "Remote console: refused {}: more than {} commands in {}",
          who,
          ConsoleAuth.COMMANDS_PER_WINDOW,
          Durations.format(ConsoleAuth.WINDOW));
      respond(ctx, HttpResponseStatus.TOO_MANY_REQUESTS, error("too many commands; slow down"));
      return;
    }
    RemoteCommandSource source = source(token, request);
    String session = session(request);
    try (var _ =
        CloseableThreadContext.put(ConsoleRecord.USER_KEY, token.name())
            .put(ConsoleRecord.SESSION_KEY, session)) {
      AUDIT.info("Remote console: {} ran \"{}\"", who, line);
    }
    var _ =
        Thread.ofVirtual()
            .name("warp-remote-command")
            .start(
                () -> {
                  JsonObject answer = run(source, line);
                  ctx.executor().execute(() -> respond(ctx, HttpResponseStatus.OK, answer));
                });
  }

  /**
   * Runs a command and waits for its output: until it returns and its output has been quiet for
   * {@link #SETTLE}, at most {@link #SETTLE_LIMIT} after it returned, or {@link #COMMAND_TIMEOUT}.
   * On a virtual thread.
   */
  JsonObject run(RemoteCommandSource source, String line) {
    long started = System.nanoTime();
    CompletableFuture<Boolean> done = commands.execute(source, line);
    Boolean found = null;
    String failure = null;
    try {
      found = done.get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      settle(source);
    } catch (TimeoutException e) {
      // Answered with what it said so far; the rest reaches the log.
    } catch (ExecutionException e) {
      found = true;
      failure = "the command failed; the proxy's log has the details";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    List<Component> output = source.finish();
    JsonObject answer = new JsonObject();
    answer.addProperty("command", line);
    answer.addProperty("found", found == null || found);
    answer.addProperty("done", found != null);
    answer.addProperty(
        "durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    JsonArray lines = new JsonArray();
    if (Boolean.FALSE.equals(found)) {
      lines.add(line(Component.text("Unknown command \"" + firstWord(line) + "\".")));
    }
    for (Component component : output) {
      lines.add(line(component));
    }
    answer.add("output", lines);
    if (failure != null) {
      answer.addProperty("error", failure);
    }
    return answer;
  }

  private static void settle(RemoteCommandSource source) throws InterruptedException {
    long returned = System.nanoTime();
    long settle = SETTLE.toNanos();
    while (System.nanoTime() - returned < SETTLE_LIMIT.toNanos()) {
      long quiet = System.nanoTime() - Math.max(returned, source.lastMessage());
      if (quiet >= settle) {
        return;
      }
      Thread.sleep(Duration.ofNanos(Math.max(1_000_000, settle - quiet)));
    }
  }

  private void suggest(ChannelHandlerContext ctx, FullHttpRequest request, Token token) {
    String line;
    try {
      line = commandLine(request, "line");
    } catch (IllegalArgumentException e) {
      respond(ctx, HttpResponseStatus.BAD_REQUEST, error(e.getMessage()));
      return;
    }
    if (!auth.allowSuggestion(token)) {
      respond(ctx, HttpResponseStatus.TOO_MANY_REQUESTS, error("too many completions"));
      return;
    }
    var _ =
        commands
            .suggest(source(token, request), line)
            .orTimeout(SUGGEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete(
                (suggestions, error) ->
                    ctx.executor()
                        .execute(
                            () -> respond(ctx, HttpResponseStatus.OK, suggestions(suggestions))));
  }

  private static JsonObject suggestions(@Nullable Suggestions suggestions) {
    JsonObject json = new JsonObject();
    JsonArray list = new JsonArray();
    if (suggestions != null) {
      for (Suggestion suggestion : suggestions.getList()) {
        JsonObject item = new JsonObject();
        item.addProperty("start", suggestion.getRange().getStart());
        item.addProperty("end", suggestion.getRange().getEnd());
        item.addProperty("text", suggestion.getText());
        var tooltip = suggestion.getTooltip();
        if (tooltip != null) {
          item.addProperty("tooltip", tooltip.getString());
        }
        list.add(item);
      }
    }
    json.add("suggestions", list);
    return json;
  }

  /**
   * Reads the command line of a request: a JSON body's field, or the whole body as text. One line,
   * without its leading {@code /}; a completion keeps its trailing space.
   */
  private static String commandLine(FullHttpRequest request, String field) {
    String body = request.content().toString(StandardCharsets.UTF_8);
    String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE, "");
    String line = body;
    if (contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
      try {
        JsonElement json = JsonParser.parseString(body);
        JsonElement value = json.isJsonObject() ? json.getAsJsonObject().get(field) : null;
        if (value == null || !value.isJsonPrimitive()) {
          throw new IllegalArgumentException("the body needs a \"" + field + "\" text");
        }
        line = value.getAsString();
      } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
        throw new IllegalArgumentException("the body is not valid JSON", e);
      }
    }
    boolean command = field.equals("command");
    if (command) {
      line = line.strip();
    } else {
      line = line.stripLeading();
    }
    if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("one command per request");
    }
    line = Ansi.clean(line).replace('\t', ' ');
    if (line.startsWith("/")) {
      line = line.substring(1);
    }
    if (command && line.isBlank()) {
      throw new IllegalArgumentException("the command is empty");
    }
    if (line.length() > MAX_COMMAND_LENGTH) {
      throw new IllegalArgumentException(
          "the command is longer than " + MAX_COMMAND_LENGTH + " characters");
    }
    return line;
  }

  private RemoteCommandSource source(Token token, FullHttpRequest request) {
    return new RemoteCommandSource(token.name(), session(request), messages);
  }

  private static String session(FullHttpRequest request) {
    String session = request.headers().get(SESSION_HEADER);
    return session != null && SESSION.matcher(session).matches() ? session : "";
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Names who sent a request, for the audit log: the token's name, the operator the client names
   * (in quotes: the client says so, nothing proves it), and the address, the one a trusted reverse
   * proxy forwarded for first.
   */
  private String who(Token token, FullHttpRequest request, InetSocketAddress peer) {
    StringBuilder who = new StringBuilder(token.name());
    String operator = printable(request.headers().get(OPERATOR_HEADER));
    if (!operator.isEmpty()) {
      who.append(" (operator \"").append(operator).append("\")");
    }
    who.append(" from ");
    String forwarded = printable(request.headers().get("X-Forwarded-For"));
    InetAddress address = peer.getAddress();
    if (!forwarded.isEmpty()
        && (address.isLoopbackAddress() || config.trustedNetworks().contains(address))) {
      int comma = forwarded.indexOf(',');
      who.append(comma < 0 ? forwarded : forwarded.substring(0, comma).strip())
          .append(" via ")
          .append(address(peer));
    } else {
      who.append(address(peer));
    }
    return who.toString();
  }

  private static String printable(@Nullable String value) {
    if (value == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < value.length() && out.length() < 64; i++) {
      char c = value.charAt(i);
      if (c >= 0x20 && c != 0x7F && c != '"') {
        out.append(c);
      }
    }
    return out.toString().strip();
  }

  private static String address(InetSocketAddress peer) {
    return peer.getAddress().getHostAddress();
  }

  private static String firstWord(String line) {
    int space = line.indexOf(' ');
    return (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
  }

  private static JsonObject line(Component component) {
    JsonObject json = new JsonObject();
    json.addProperty("text", PlainTextComponentSerializer.plainText().serialize(component));
    json.add("component", GsonComponentSerializer.gson().serializeToTree(component));
    return json;
  }

  private static JsonObject error(@Nullable String message) {
    JsonObject body = new JsonObject();
    body.addProperty("error", message);
    return body;
  }

  private static void respond(
      ChannelHandlerContext ctx, HttpResponseStatus status, JsonObject body) {
    send(ctx, response(status, body));
  }

  private static FullHttpResponse response(HttpResponseStatus status, JsonObject body) {
    byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
    response
        .headers()
        .set(HttpHeaderNames.CONTENT_TYPE, JSON)
        .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
        .set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE)
        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    return response;
  }

  private static void send(ChannelHandlerContext ctx, FullHttpResponse response) {
    var _ = ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }
}
