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

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.command.CommandSource;
import dev.warp.proxy.command.WarpCommandManager;
import dev.warp.proxy.config.RemoteConsoleConfig;
import dev.warp.proxy.config.RemoteConsoleConfig.Scope;
import dev.warp.proxy.config.RemoteConsoleConfig.Token;
import dev.warp.proxy.i18n.PlayerMessages;
import dev.warp.proxy.network.CidrRanges;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslHandler;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RemoteConsole")
class RemoteConsoleTest {

  private static final String OPS_SECRET = "o".repeat(40);
  private static final String DASH_SECRET = "d".repeat(40);
  private static final Token OPS =
      new Token(
          "ops",
          Set.of(Scope.LOGS, Scope.COMMANDS),
          RemoteConsoleConfig.sha256(OPS_SECRET),
          OPS_SECRET);
  private static final Token DASH =
      new Token("dash", Set.of(Scope.LOGS), RemoteConsoleConfig.sha256(DASH_SECRET), DASH_SECRET);
  private static final String LOOPBACK = "127.0.0.1";
  private static final String TRUSTED = "10.1.2.3";
  private static final String OUTSIDE = "203.0.113.9";

  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final WarpCommandManager commands = new WarpCommandManager(Runnable::run);
  private final RemoteConsole remote;

  RemoteConsoleTest() {
    var _ =
        commands.register(
            "test",
            LiteralArgumentBuilder.<CommandSource>literal("echo")
                .then(
                    RequiredArgumentBuilder.<CommandSource, String>argument("text", greedyString())
                        .executes(
                            context -> {
                              context
                                  .getSource()
                                  .sendMessage(Component.text(getString(context, "text")));
                              return 1;
                            })));
    remote =
        new RemoteConsole(
            new RemoteConsoleConfig(
                true,
                List.of(OPS, DASH),
                CidrRanges.parse(List.of("10.0.0.0/8")),
                100,
                3,
                Duration.ofMinutes(15)),
            commands,
            PlayerMessages::bundled,
            "1.2.3",
            List.of(),
            clock::get);
  }

  @AfterEach
  void stop() {
    remote.stop();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private EmbeddedChannel channel(String address, boolean tls) throws Exception {
    EmbeddedChannel channel =
        new EmbeddedChannel() {
          @Override
          protected SocketAddress remoteAddress0() {
            return new InetSocketAddress(address, 40000);
          }
        };
    channel
        .pipeline()
        .addLast(
            new SimpleChannelInboundHandler<FullHttpRequest>() {
              @Override
              protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                remote.handle(ctx, request, new QueryStringDecoder(request.uri()));
              }
            });
    if (tls) {
      // After the handler: its answers never pass through it, but the pipeline holds TLS.
      SSLEngine engine = SSLContext.getDefault().createSSLEngine();
      engine.setUseClientMode(false);
      channel.pipeline().addLast(new SslHandler(engine));
    }
    return channel;
  }

  private static FullHttpRequest request(
      HttpMethod method, String uri, @Nullable String token, String body) {
    ByteBuf content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
    FullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, content);
    if (token != null) {
      request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
    }
    request.headers().set(RemoteConsole.OPERATOR_HEADER, "alice@laptop");
    return request;
  }

  private static FullHttpResponse answer(EmbeddedChannel channel) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      channel.runPendingTasks();
      Object message = channel.readOutbound();
      if (message != null) {
        return assertInstanceOf(FullHttpResponse.class, message);
      }
      Thread.sleep(5);
    }
    throw new AssertionError("no answer");
  }

  private FullHttpResponse send(String address, boolean tls, FullHttpRequest request)
      throws Exception {
    EmbeddedChannel channel = channel(address, tls);
    channel.writeInbound(request);
    return answer(channel);
  }

  private static JsonObject json(FullHttpResponse response) {
    return JsonParser.parseString(response.content().toString(StandardCharsets.UTF_8))
        .getAsJsonObject();
  }

  private static String frames(EmbeddedChannel channel) {
    channel.runPendingTasks();
    StringBuilder text = new StringBuilder();
    Object message;
    while ((message = channel.readOutbound()) != null) {
      if (message instanceof HttpContent content) {
        text.append(content.content().toString(StandardCharsets.UTF_8));
        content.release();
      }
    }
    return text.toString();
  }

  private String awaitAudit(String text) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      for (ConsoleRecord record : remote.history().replay(0, LogFilter.DEFAULT, 100).records()) {
        if (record.message().contains(text)) {
          return record.message();
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("no audit line containing " + text);
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("authentication")
  class Authentication {

    @Test
    @DisplayName("should ask for a token, as a bearer, when none is sent")
    void missing() throws Exception {
      FullHttpResponse response =
          send(LOOPBACK, false, request(HttpMethod.GET, "/console/commands", null, ""));

      assertEquals(401, response.status().code());
      assertEquals(
          "Bearer realm=\"warp-console\"",
          response.headers().get(HttpHeaderNames.WWW_AUTHENTICATE));
    }

    @Test
    @DisplayName("should lock out an address after its wrong tokens, with Retry-After")
    void lockout() throws Exception {
      for (int i = 0; i < 3; i++) {
        assertEquals(
            401,
            send(OUTSIDE, false, request(HttpMethod.GET, "/console/logs", "guess" + i, ""))
                .status()
                .code());
      }

      FullHttpResponse locked =
          send(OUTSIDE, false, request(HttpMethod.GET, "/console/logs", DASH_SECRET, ""));

      assertEquals(429, locked.status().code());
      assertEquals("900", locked.headers().get(HttpHeaderNames.RETRY_AFTER));
    }

    @Test
    @DisplayName("should refuse a token without the scope an endpoint needs")
    void scope() throws Exception {
      FullHttpResponse response =
          send(
              LOOPBACK, false, request(HttpMethod.POST, "/console/command", DASH_SECRET, "echo x"));

      assertEquals(403, response.status().code());
      assertTrue(json(response).get("error").getAsString().contains("commands scope"));
    }

    @Test
    @DisplayName("should answer 404 to an unknown endpoint and 405 to a wrong method")
    void routes() throws Exception {
      assertEquals(
          404,
          send(LOOPBACK, false, request(HttpMethod.GET, "/console/nope", OPS_SECRET, ""))
              .status()
              .code());
      FullHttpResponse wrongMethod =
          send(LOOPBACK, false, request(HttpMethod.GET, "/console/command", OPS_SECRET, ""));
      assertEquals(405, wrongMethod.status().code());
      assertEquals("POST", wrongMethod.headers().get(HttpHeaderNames.ALLOW));
    }
  }

  @Nested
  @DisplayName("commands without TLS")
  class Plaintext {

    private FullHttpRequest echo() {
      return request(HttpMethod.POST, "/console/command", OPS_SECRET, "echo hi");
    }

    @Test
    @DisplayName("should refuse commands in clear text from outside the trusted networks")
    void outside() throws Exception {
      FullHttpResponse response = send(OUTSIDE, false, echo());

      assertEquals(403, response.status().code());
      assertTrue(json(response).get("error").getAsString().contains("TLS"));
    }

    @Test
    @DisplayName("should accept commands from the loopback, the trusted networks, and over TLS")
    void accepted() throws Exception {
      assertEquals(200, send(LOOPBACK, false, echo()).status().code());
      assertEquals(200, send(TRUSTED, false, echo()).status().code());
      assertEquals(200, send(OUTSIDE, true, echo()).status().code());
    }

    @Test
    @DisplayName("should stream the log in clear text from anywhere")
    void logs() throws Exception {
      EmbeddedChannel channel = channel(OUTSIDE, false);

      channel.writeInbound(request(HttpMethod.GET, "/console/logs", DASH_SECRET, ""));

      HttpResponse response = assertInstanceOf(HttpResponse.class, channel.readOutbound());
      assertEquals(200, response.status().code());
      var _ = channel.close();
    }
  }

  @Nested
  @DisplayName("commands")
  class Commands {

    @Test
    @DisplayName("should run a command and answer what it replied")
    void output() throws Exception {
      JsonObject answer =
          json(
              send(
                  LOOPBACK,
                  false,
                  request(HttpMethod.POST, "/console/command", OPS_SECRET, "/echo hello world")));

      assertTrue(answer.get("found").getAsBoolean());
      assertTrue(answer.get("done").getAsBoolean());
      assertEquals("echo hello world", answer.get("command").getAsString());
      assertEquals(
          "hello world",
          answer.getAsJsonArray("output").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    @DisplayName("should read the command from a JSON body")
    void jsonBody() throws Exception {
      FullHttpRequest request =
          request(HttpMethod.POST, "/console/command", OPS_SECRET, "{\"command\":\"echo json\"}");
      request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");

      JsonObject answer = json(send(LOOPBACK, false, request));

      assertEquals(
          "json",
          answer.getAsJsonArray("output").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    @DisplayName("should say that an unknown command is unknown")
    void unknown() throws Exception {
      JsonObject answer =
          json(
              send(
                  LOOPBACK,
                  false,
                  request(HttpMethod.POST, "/console/command", OPS_SECRET, "nope")));

      assertFalse(answer.get("found").getAsBoolean());
      assertEquals(
          "Unknown command \"nope\".",
          answer.getAsJsonArray("output").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    @DisplayName("should refuse an empty command and several lines")
    void invalid() throws Exception {
      assertEquals(
          400,
          send(LOOPBACK, false, request(HttpMethod.POST, "/console/command", OPS_SECRET, "  "))
              .status()
              .code());
      assertEquals(
          400,
          send(LOOPBACK, false, request(HttpMethod.POST, "/console/command", OPS_SECRET, "a\nb"))
              .status()
              .code());
    }

    @Test
    @DisplayName("should complete a line")
    void suggest() throws Exception {
      JsonObject answer =
          json(
              send(
                  LOOPBACK, false, request(HttpMethod.POST, "/console/suggest", OPS_SECRET, "ec")));

      JsonObject first = answer.getAsJsonArray("suggestions").get(0).getAsJsonObject();
      assertEquals("echo", first.get("text").getAsString());
      assertEquals(0, first.get("start").getAsInt());
      assertEquals(2, first.get("end").getAsInt());
    }

    @Test
    @DisplayName("should describe the commands as a tree")
    void tree() throws Exception {
      JsonObject tree =
          json(send(LOOPBACK, false, request(HttpMethod.GET, "/console/commands", OPS_SECRET, "")));

      JsonObject echo = tree.getAsJsonArray("commands").get(0).getAsJsonObject();
      assertEquals("echo", echo.get("literal").getAsString());
      JsonObject text = echo.getAsJsonArray("children").get(0).getAsJsonObject();
      assertEquals("text", text.get("argument").getAsString());
      assertEquals("greedy", text.get("type").getAsString());
      assertTrue(text.get("executes").getAsBoolean());
    }
  }

  @Nested
  @DisplayName("audit")
  class Audit {

    @Test
    @DisplayName("should log who ran which command, from where")
    void command() throws Exception {
      remote.start();

      var _ =
          send(
              LOOPBACK,
              false,
              request(HttpMethod.POST, "/console/command", OPS_SECRET, "echo audited"));

      assertEquals(
          "Remote console: ops (operator \"alice@laptop\") from 127.0.0.1 ran \"echo audited\"",
          awaitAudit("ran \"echo audited\""));
    }

    @Test
    @DisplayName("should name the forwarded client of a trusted reverse proxy only")
    void forwarded() throws Exception {
      remote.start();
      FullHttpRequest trusted = request(HttpMethod.POST, "/console/command", OPS_SECRET, "echo a");
      trusted.headers().set("X-Forwarded-For", "198.51.100.7, 10.0.0.1");
      FullHttpRequest outside = request(HttpMethod.GET, "/console/logs", DASH_SECRET, "");
      outside.headers().set("X-Forwarded-For", "198.51.100.8");

      var _ = send(TRUSTED, false, trusted);
      EmbeddedChannel stream = channel(OUTSIDE, false);
      stream.writeInbound(outside);

      assertTrue(awaitAudit("ran \"echo a\"").contains("from 198.51.100.7 via 10.1.2.3"));
      assertTrue(awaitAudit("dash").contains("from 203.0.113.9 attached"));
      var _ = stream.close();
    }

    @Test
    @DisplayName("should log wrong tokens and lockouts")
    void refusals() throws Exception {
      remote.start();

      for (int i = 0; i < 3; i++) {
        var _ = send(OUTSIDE, false, request(HttpMethod.GET, "/console/logs", "guess", ""));
      }

      assertTrue(awaitAudit("refused a wrong token from 203.0.113.9").contains("1 of 3"));
      assertTrue(awaitAudit("locked out 203.0.113.9").contains("for 15m"));
    }
  }

  @Nested
  @DisplayName("log stream")
  class Stream {

    private EmbeddedChannel attach(String uri, @Nullable String lastEventId) throws Exception {
      EmbeddedChannel channel = channel(LOOPBACK, false);
      FullHttpRequest request = request(HttpMethod.GET, uri, OPS_SECRET, "");
      if (lastEventId != null) {
        request.headers().set("Last-Event-ID", lastEventId);
      }
      channel.writeInbound(request);
      HttpResponse response = assertInstanceOf(HttpResponse.class, channel.readOutbound());
      assertEquals(
          "text/event-stream; charset=utf-8", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
      return channel;
    }

    private String instance(String frames) {
      String hello =
          frames
              .lines()
              .filter(line -> line.startsWith("data: {\"proxy\""))
              .findFirst()
              .orElseThrow();
      return JsonParser.parseString(hello.substring(6))
          .getAsJsonObject()
          .get("instance")
          .getAsString();
    }

    @Test
    @DisplayName("should say hello, replay the last lines, then stream the new ones")
    void replayThenLive() throws Exception {
      remote.history().append(LogHistoryTest.info("old {}", 1));
      remote.history().append(LogHistoryTest.info("old {}", 2));
      remote.history().append(LogHistoryTest.info("old {}", 3));

      EmbeddedChannel channel = attach("/console/logs?replay=2", null);
      String opened = frames(channel);
      remote.history().append(LogHistoryTest.info("new {}", 4));
      String live = frames(channel);

      assertTrue(opened.startsWith("retry: 2000\nevent: hello\n"), opened);
      assertTrue(opened.contains("\"user\":\"ops\""), opened);
      assertFalse(opened.contains("old 1"), opened);
      assertTrue(opened.indexOf("old 2") < opened.indexOf("old 3"), opened);
      assertTrue(live.contains("\"message\":\"new 4\""), live);
      assertEquals(1, remote.streams());
      var _ = channel.close();
      assertEquals(0, remote.streams());
    }

    @Test
    @DisplayName("should filter the replay and the live lines")
    void filtered() throws Exception {
      remote.history().append(LogHistoryTest.info("alice joined"));
      remote.history().append(LogHistoryTest.info("bob joined"));

      EmbeddedChannel channel = attach("/console/logs?grep=bob", null);
      String opened = frames(channel);
      remote.history().append(LogHistoryTest.info("alice left"));

      assertTrue(opened.contains("bob joined"), opened);
      assertFalse(opened.contains("alice"), opened);
      assertEquals("", frames(channel));
      var _ = channel.close();
    }

    @Test
    @DisplayName("should resume after Last-Event-ID, without a repeat")
    void resume() throws Exception {
      remote.history().append(LogHistoryTest.info("line {}", 1));
      EmbeddedChannel first = attach("/console/logs?replay=0", null);
      String instance = instance(frames(first));
      var _ = first.close();
      remote.history().append(LogHistoryTest.info("line {}", 2));
      remote.history().append(LogHistoryTest.info("line {}", 3));

      EmbeddedChannel second = attach("/console/logs", instance + "-2");
      String resumed = frames(second);

      assertTrue(resumed.contains("\"resumed\":true"), resumed);
      assertFalse(resumed.contains("line 2"), resumed);
      assertTrue(resumed.contains("line 3"), resumed);
      var _ = second.close();
    }

    @Test
    @DisplayName("should start afresh after the Last-Event-ID of another run of the proxy")
    void otherRun() {
      assertEquals(-1, remote.resumeAfter("deadbeef-3"));
      assertEquals(-1, remote.resumeAfter("garbage"));
    }

    @Test
    @DisplayName("should end the stream with bye when the proxy stops")
    void bye() throws Exception {
      EmbeddedChannel channel = attach("/console/logs?replay=0", null);
      var _ = frames(channel);

      remote.stop();
      channel.runPendingTasks();
      List<Object> messages = new ArrayList<>();
      Object message;
      while ((message = channel.readOutbound()) != null) {
        messages.add(message);
      }

      HttpContent bye = assertInstanceOf(HttpContent.class, messages.getFirst());
      assertTrue(bye.content().toString(StandardCharsets.UTF_8).contains("event: bye"));
      assertInstanceOf(LastHttpContent.class, messages.getLast());
      assertFalse(channel.isOpen());
    }

    @Test
    @DisplayName("should refuse a malformed filter")
    void badFilter() throws Exception {
      FullHttpResponse response =
          send(
              LOOPBACK, false, request(HttpMethod.GET, "/console/logs?level=loud", OPS_SECRET, ""));

      assertEquals(400, response.status().code());
      assertNotNull(json(response).get("error"));
    }

    @Test
    @DisplayName("should tell the clients that may run commands when the commands change")
    void commandsChanged() throws Exception {
      EmbeddedChannel channel = attach("/console/logs?replay=0", null);
      var _ = frames(channel);

      remote.commandsChanged();
      String text = "";
      long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
      while (!text.contains("event: commands") && System.nanoTime() < deadline) {
        Thread.sleep(50);
        text += frames(channel);
      }

      assertTrue(text.contains("event: commands"), text);
      var _ = channel.close();
    }
  }

  @Test
  @DisplayName("should describe its tokens without their values")
  void describe() {
    String description = remote.describe(false);

    assertEquals(
        "tokens ops [logs, commands], dash [logs]; commands from loopback and "
            + CidrRanges.parse(List.of("10.0.0.0/8"))
            + " only (no TLS)",
        description);
    assertFalse(description.contains(OPS_SECRET));
  }
}
