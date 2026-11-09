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
package dev.warp.proxy.management;

import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.metrics.Metrics;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.antibot.AntiBotStatus;
import dev.warp.proxy.config.MetricsConfig;
import dev.warp.proxy.logging.LogCode;
import dev.warp.proxy.management.console.RemoteConsole;
import dev.warp.proxy.metrics.PrometheusText;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers the management endpoints, one request per connection, with JSON:
 *
 * <ul>
 *   <li>{@code GET /livez}: 200 while the process runs
 *   <li>{@code GET /startupz}: 200 once the proxy listens and its plugins are enabled, else 503
 *   <li>{@code GET /readyz}: 200 while new players may be sent here, else 503 with the reason
 *   <li>{@code GET /drain}: where the drain stands
 *   <li>{@code POST /drain}: starts the drain (bearer token): 202, or 200 if one was in progress;
 *       with {@code ?wait=true}, answers 200 once every player left or the time-out expired
 *   <li>{@code GET /metrics}: the metrics, Prometheus text or OpenMetrics (see {@link
 *       PrometheusText})
 *   <li>{@code /console/...}: the remote console, when enabled (see {@link RemoteConsole})
 * </ul>
 *
 * <p>Probes also answer {@code HEAD}. Runs on the management event loop; reads only what {@link
 * ProxyHealth} exposes, so no plugin code runs here.
 */
final class ManagementHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

  private static final Logger logger = LoggerFactory.getLogger(ManagementHttpHandler.class);

  private static final String JSON = "application/json; charset=utf-8";
  private static final String BEARER = "Bearer ";
  private static final String TEXT = "text/plain; charset=utf-8";

  private final ProxyHealth health;
  private final boolean requireServer;
  private final byte @Nullable [] tokenDigest;
  private final @Nullable RemoteConsole console;
  private final MetricsConfig metrics;

  /**
   * Creates the handler of one connection.
   *
   * @param health the proxy
   * @param requireServer whether readiness needs a registered backend server
   * @param tokenDigest the SHA-256 of the bearer token, or {@code null} if none is configured
   * @param console the remote console, or {@code null} when it is off
   * @param metrics the metrics endpoint's settings
   */
  ManagementHttpHandler(
      ProxyHealth health,
      boolean requireServer,
      byte @Nullable [] tokenDigest,
      @Nullable RemoteConsole console,
      MetricsConfig metrics) {
    this.health = health;
    this.requireServer = requireServer;
    this.tokenDigest = tokenDigest;
    this.console = console;
    this.metrics = metrics;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
    if (!request.decoderResult().isSuccess()) {
      respond(ctx, false, HttpResponseStatus.BAD_REQUEST, error("malformed request"));
      return;
    }
    HttpMethod method = request.method();
    boolean head = HttpMethod.HEAD.equals(method);
    QueryStringDecoder uri = new QueryStringDecoder(request.uri());
    if (uri.path().equals("/console") || uri.path().startsWith("/console/")) {
      RemoteConsole remote = console;
      if (remote == null) {
        respond(
            ctx,
            head,
            HttpResponseStatus.NOT_FOUND,
            error("the remote console is off; see management.console in warp.conf"));
      } else {
        remote.handle(ctx, request, uri);
      }
      return;
    }
    switch (uri.path()) {
      case "/livez" -> {
        if (readOnly(ctx, method, head)) {
          respond(ctx, head, HttpResponseStatus.OK, status("alive"));
        }
      }
      case "/startupz" -> {
        if (readOnly(ctx, method, head)) {
          boolean started = health.isStarted();
          respond(
              ctx,
              head,
              started ? HttpResponseStatus.OK : HttpResponseStatus.SERVICE_UNAVAILABLE,
              status(started ? "started" : "starting"));
        }
      }
      case "/readyz" -> {
        if (readOnly(ctx, method, head)) {
          readiness(ctx, head);
        }
      }
      case "/drain" -> {
        if (HttpMethod.POST.equals(method)) {
          startDrain(ctx, request, uri);
        } else if (readOnly(ctx, method, head)) {
          respond(ctx, head, HttpResponseStatus.OK, drainJson(health.drainStatus()));
        }
      }
      case "/antibot", "/antibot/blocklist" -> {
        AntiBotService bots = health.antiBot();
        if (bots == null) {
          respond(ctx, head, HttpResponseStatus.SERVICE_UNAVAILABLE, error("starting"));
        } else if (readOnly(ctx, method, head)) {
          if (uri.path().equals("/antibot")) {
            respond(ctx, head, HttpResponseStatus.OK, AntiBotEndpoints.json(bots.status()));
          } else {
            respondText(ctx, head, AntiBotStatus.blocklistText(bots.blocklist()), TEXT);
          }
        }
      }
      case "/metrics" -> {
        if (!metrics.enabled()) {
          respond(
              ctx,
              head,
              HttpResponseStatus.NOT_FOUND,
              error("metrics are off; see management.metrics.enabled in warp.conf"));
        } else if ((!metrics.requireToken() || authorize(ctx, request, "GET /metrics"))
            && readOnly(ctx, method, head)) {
          metrics(ctx, request, head, uri);
        }
      }
      case "/antibot/lockdown" -> lockdown(ctx, request, method, uri);
      default ->
          respond(
              ctx,
              head,
              HttpResponseStatus.NOT_FOUND,
              error(
                  "no such endpoint; try /livez, /startupz, /readyz, /drain, /antibot,"
                      + " /antibot/blocklist, /antibot/lockdown or /metrics"));
    }
  }

  /**
   * {@code GET /metrics}: the registry in the Prometheus text format, or OpenMetrics when {@code
   * Accept} asks for it, gzipped when {@code Accept-Encoding} allows, restricted to one prefix with
   * {@code ?filter=}. The scrape runs on a virtual thread, never on this loop.
   */
  private void metrics(
      ChannelHandlerContext ctx, FullHttpRequest request, boolean head, QueryStringDecoder uri) {
    PrometheusText.Format format =
        PrometheusText.negotiate(request.headers().get(HttpHeaderNames.ACCEPT));
    List<String> filters = uri.parameters().getOrDefault("filter", List.of());
    String filter = filters.isEmpty() || filters.getFirst().isBlank() ? null : filters.getFirst();
    String encoding = request.headers().get(HttpHeaderNames.ACCEPT_ENCODING);
    boolean gzip = encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip");
    Metrics registry = health.metrics();
    Thread.ofVirtual()
        .name("warp-metrics-scrape")
        .start(
            () -> {
              FullHttpResponse response;
              try {
                byte[] body =
                    PrometheusText.write(registry, format, filter).getBytes(StandardCharsets.UTF_8);
                if (gzip) {
                  body = gzip(body);
                }
                response =
                    new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.OK,
                        head ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body));
                response
                    .headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, format.contentType())
                    .setInt(HttpHeaderNames.CONTENT_LENGTH, body.length)
                    .set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE)
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                if (gzip) {
                  response.headers().set(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
                }
              } catch (IOException | RuntimeException e) {
                logger.warn(LogCode.METRICS_SCRAPE_FAILED.marker(), "GET /metrics failed", e);
                response =
                    response(
                        head, HttpResponseStatus.INTERNAL_SERVER_ERROR, error("scrape failed"));
              }
              FullHttpResponse answer = response;
              ctx.executor().execute(() -> send(ctx, answer));
            });
  }

  private static byte[] gzip(byte[] body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(body.length / 4 + 64);
    try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
      out.write(body);
    }
    return bytes.toByteArray();
  }

  /** {@code POST /antibot/lockdown?enabled=true|false}: turns lockdown on or off (bearer token). */
  private void lockdown(
      ChannelHandlerContext ctx,
      FullHttpRequest request,
      HttpMethod method,
      QueryStringDecoder uri) {
    if (!HttpMethod.POST.equals(method)) {
      FullHttpResponse response =
          response(false, HttpResponseStatus.METHOD_NOT_ALLOWED, error("use POST"));
      response.headers().set(HttpHeaderNames.ALLOW, "POST");
      send(ctx, response);
      return;
    }
    if (!authorize(ctx, request, "POST /antibot/lockdown")) {
      return;
    }
    AntiBotService bots = health.antiBot();
    List<String> values = uri.parameters().get("enabled");
    String value = values == null || values.isEmpty() ? "" : values.getLast();
    Boolean enabled =
        switch (value.toLowerCase(Locale.ROOT)) {
          case "true", "on", "1" -> Boolean.TRUE;
          case "false", "off", "0" -> Boolean.FALSE;
          default -> null;
        };
    if (bots == null || enabled == null) {
      respond(
          ctx,
          false,
          bots == null ? HttpResponseStatus.SERVICE_UNAVAILABLE : HttpResponseStatus.BAD_REQUEST,
          error(bots == null ? "starting" : "add ?enabled=true or ?enabled=false"));
      return;
    }
    try {
      boolean changed = bots.lockdown(enabled, "management");
      JsonObject body = new JsonObject();
      body.addProperty("lockdown", bots.isLockdown());
      body.addProperty("changed", changed);
      respond(ctx, false, HttpResponseStatus.OK, body);
    } catch (IllegalStateException e) {
      respond(ctx, false, HttpResponseStatus.CONFLICT, error(e.getMessage()));
    }
  }

  /**
   * Checks the bearer token of a mutating request, answering 403 or 401 when it is missing.
   *
   * @return {@code true} if the request may go on
   */
  private boolean authorize(ChannelHandlerContext ctx, FullHttpRequest request, String endpoint) {
    byte[] expected = tokenDigest;
    if (expected == null) {
      respond(
          ctx,
          false,
          HttpResponseStatus.FORBIDDEN,
          error(
              endpoint
                  + " is disabled: no management token is configured"
                  + " (management.token-file or WARP_MANAGEMENT_TOKEN)"));
      return false;
    }
    if (!authorized(request, expected)) {
      logger.warn(
          LogCode.MANAGEMENT_UNAUTHORIZED.marker(),
          "Refused {} from {}: missing or wrong bearer token",
          endpoint,
          ctx.channel().remoteAddress());
      FullHttpResponse response =
          response(false, HttpResponseStatus.UNAUTHORIZED, error("missing or wrong bearer token"));
      response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer realm=\"warp\"");
      send(ctx, response);
      return false;
    }
    return true;
  }

  private static void respondText(
      ChannelHandlerContext ctx, boolean head, String text, String contentType) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    FullHttpResponse response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            head ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(bytes));
    response
        .headers()
        .set(HttpHeaderNames.CONTENT_TYPE, contentType)
        .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
        .set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE)
        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    send(ctx, response);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.debug("Management connection {} failed", ctx.channel().remoteAddress(), cause);
    var _ = ctx.close();
  }

  // ---------------------------------------------------------------------------
  // Endpoints
  // ---------------------------------------------------------------------------

  private void readiness(ChannelHandlerContext ctx, boolean head) {
    ReadinessState state = ReadinessState.of(health, requireServer);
    JsonObject body = new JsonObject();
    body.addProperty("ready", state == ReadinessState.READY);
    body.addProperty("status", state.label());
    body.addProperty("started", health.isStarted());
    body.addProperty("servers", health.serverCount());
    JsonArray holds = new JsonArray();
    for (String reason : health.readinessHolds()) {
      holds.add(reason);
    }
    body.add("holds", holds);
    body.addProperty("drain", lower(health.drainStatus().phase()));
    respond(
        ctx,
        head,
        state == ReadinessState.READY
            ? HttpResponseStatus.OK
            : HttpResponseStatus.SERVICE_UNAVAILABLE,
        body);
  }

  private void startDrain(
      ChannelHandlerContext ctx, FullHttpRequest request, QueryStringDecoder uri) {
    if (!authorize(ctx, request, "POST /drain")) {
      return;
    }
    if (!health.isStarted() || health.isStopping()) {
      respond(
          ctx,
          false,
          HttpResponseStatus.SERVICE_UNAVAILABLE,
          error(health.isStopping() ? "the proxy is shutting down" : "the proxy is starting"));
      return;
    }
    boolean already = health.isDraining();
    CompletableFuture<DrainStatus> done;
    try {
      done = health.drain(DrainTrigger.HTTP);
    } catch (IllegalStateException e) {
      respond(ctx, false, HttpResponseStatus.SERVICE_UNAVAILABLE, error(e.getMessage()));
      return;
    }
    if (!already) {
      logger.info("Drain requested by POST /drain from {}", ctx.channel().remoteAddress());
    }
    if (!waits(uri)) {
      respond(
          ctx,
          false,
          already ? HttpResponseStatus.OK : HttpResponseStatus.ACCEPTED,
          drainJson(health.drainStatus()));
      return;
    }
    // Held open until the drain is over, which may take far longer than a request should.
    if (ctx.pipeline().get(ReadTimeoutHandler.class) != null) {
      ctx.pipeline().remove(ReadTimeoutHandler.class);
    }
    var _ =
        done.whenComplete(
            (status, error) ->
                ctx.executor()
                    .execute(
                        () ->
                            respond(
                                ctx,
                                false,
                                HttpResponseStatus.OK,
                                drainJson(status != null ? status : health.drainStatus()))));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Answers 405 to anything but GET and HEAD; returns whether the request may go on. */
  private static boolean readOnly(ChannelHandlerContext ctx, HttpMethod method, boolean head) {
    if (head || HttpMethod.GET.equals(method)) {
      return true;
    }
    FullHttpResponse response =
        response(false, HttpResponseStatus.METHOD_NOT_ALLOWED, error("use GET"));
    response.headers().set(HttpHeaderNames.ALLOW, "GET, HEAD");
    send(ctx, response);
    return false;
  }

  private static boolean authorized(FullHttpRequest request, byte[] expected) {
    String header = request.headers().get(HttpHeaderNames.AUTHORIZATION);
    if (header == null
        || header.length() <= BEARER.length()
        || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return false;
    }
    // Comparing digests takes the same time whatever the token's length and content.
    return MessageDigest.isEqual(sha256(header.substring(BEARER.length()).trim()), expected);
  }

  private static boolean waits(QueryStringDecoder uri) {
    List<String> values = uri.parameters().get("wait");
    if (values == null || values.isEmpty()) {
      return false;
    }
    String value = values.getLast();
    return value.isEmpty() || value.equalsIgnoreCase("true") || value.equals("1");
  }

  /**
   * Returns the SHA-256 of a token, as the server compares it.
   *
   * @param token the token
   * @return its digest
   */
  static byte[] sha256(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is always available", e);
    }
  }

  /**
   * Describes a drain status as the endpoints report it.
   *
   * @param status the status
   * @return its JSON form
   */
  static JsonObject drainJson(DrainStatus status) {
    JsonObject body = new JsonObject();
    body.addProperty("draining", status.isDraining());
    body.addProperty("phase", lower(status.phase()));
    body.addProperty("trigger", status.trigger() == null ? null : lower(status.trigger()));
    Instant startedAt = status.startedAt();
    body.addProperty("startedAt", startedAt == null ? null : startedAt.toString());
    body.addProperty("elapsedMillis", status.elapsed().toMillis());
    body.addProperty("players", status.players());
    body.addProperty("transferred", status.transferred());
    body.addProperty("kept", status.kept());
    body.addProperty("kicked", status.kicked());
    body.addProperty("refused", status.refused());
    body.addProperty("outcome", status.outcome() == null ? null : lower(status.outcome()));
    return body;
  }

  private static JsonObject status(String status) {
    JsonObject body = new JsonObject();
    body.addProperty("status", status);
    return body;
  }

  private static JsonObject error(@Nullable String message) {
    JsonObject body = new JsonObject();
    body.addProperty("error", message);
    return body;
  }

  private static String lower(@Nullable Enum<?> value) {
    return value == null ? "" : value.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static void respond(
      ChannelHandlerContext ctx, boolean head, HttpResponseStatus status, JsonObject body) {
    send(ctx, response(head, status, body));
  }

  private static FullHttpResponse response(
      boolean head, HttpResponseStatus status, JsonObject body) {
    byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
    FullHttpResponse response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            status,
            head ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(bytes));
    response
        .headers()
        .set(HttpHeaderNames.CONTENT_TYPE, JSON)
        .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
        .set(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_STORE)
        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    return response;
  }

  private static void send(ChannelHandlerContext ctx, FullHttpResponse response) {
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }
}
