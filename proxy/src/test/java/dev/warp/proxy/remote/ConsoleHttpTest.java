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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.WarpBuildInfo;
import dev.warp.proxy.management.console.RemoteConsole;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLHandshakeException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ConsoleHttp")
class ConsoleHttpTest {

  @Nested
  @DisplayName("loopback")
  class Loopback {

    @Test
    @DisplayName("should recognize localhost and literal loopback addresses")
    void loopback() {
      assertTrue(ConsoleHttp.loopback("127.0.0.1"));
      assertTrue(ConsoleHttp.loopback("127.0.0.53"));
      assertTrue(ConsoleHttp.loopback("localhost"));
      assertTrue(ConsoleHttp.loopback("LOCALHOST"));
      assertTrue(ConsoleHttp.loopback("::1"));
      assertTrue(ConsoleHttp.loopback("[::1]"));
    }

    @Test
    @DisplayName("should not trust names or other addresses, even ones resolving to loopback")
    void notLoopback() {
      assertFalse(ConsoleHttp.loopback(null));
      assertFalse(ConsoleHttp.loopback("warp-management.minecraft.svc"));
      assertFalse(ConsoleHttp.loopback("10.0.0.1"));
      assertFalse(ConsoleHttp.loopback("fe80::1"));
      assertFalse(ConsoleHttp.loopback("localhost.example.com"));
      assertFalse(ConsoleHttp.loopback(""));
    }
  }

  @Nested
  @DisplayName("explain")
  class Explain {

    @Test
    @DisplayName("should tell what to do about a certificate no trusted authority signed")
    void untrustedCertificate() {
      String pkix =
          ConsoleHttp.explain(
              new SSLHandshakeException(
                  "PKIX path building failed: unable to find valid certification path"));
      String unknown =
          ConsoleHttp.explain(
              new SSLHandshakeException("Received fatal alert: certificate_unknown"));

      assertTrue(pkix.contains("not trusted"), pkix);
      assertTrue(pkix.contains("--ca-file"), pkix);
      assertEquals(pkix, unknown);
    }

    @Test
    @DisplayName("should say when the certificate does not name the address")
    void wrongName() {
      String alternative =
          ConsoleHttp.explain(
              new SSLHandshakeException("No subject alternative names matching IP 10.0.0.5"));
      String matching =
          ConsoleHttp.explain(new SSLHandshakeException("No name matching warp found"));

      assertTrue(
          alternative.startsWith("its certificate does not name this address: "), alternative);
      assertTrue(alternative.endsWith("No subject alternative names matching IP 10.0.0.5"));
      assertTrue(matching.endsWith("No name matching warp found"), matching);
    }

    @Test
    @DisplayName("should repeat any other message, and name the exception without one")
    void otherFailures() {
      assertEquals(
          "Connection refused", ConsoleHttp.explain(new ConnectException("Connection refused")));
      assertEquals("ConnectException", ConsoleHttp.explain(new ConnectException()));
      assertEquals("IOException", ConsoleHttp.explain(new IOException(" ")));
    }
  }

  @Nested
  @DisplayName("describe")
  class Describe {

    @Test
    @DisplayName("should keep the scheme, host and port only")
    void schemeAndAuthority() {
      assertEquals(
          "https://warp.example:9901",
          ConsoleHttp.describe(URI.create("https://warp.example:9901/console/logs?replay=10")));
      assertEquals("http://[::1]:9901", ConsoleHttp.describe(URI.create("http://[::1]:9901")));
    }
  }

  @Nested
  @DisplayName("Answer.error")
  class AnswerError {

    @Test
    @DisplayName("should return the server's message when it gives one")
    void serverMessage() {
      JsonObject body = new JsonObject();
      body.addProperty("error", "no such command");

      assertEquals("no such command", new ConsoleHttp.Answer(404, body).error());
    }

    @Test
    @DisplayName("should describe the status without a message, or with one that is not text")
    void statusOnly() {
      JsonObject structured = new JsonObject();
      structured.add("error", new JsonObject());

      assertEquals("HTTP 502", new ConsoleHttp.Answer(502, new JsonObject()).error());
      assertEquals("HTTP 500", new ConsoleHttp.Answer(500, structured).error());
    }
  }

  @Nested
  @DisplayName("ssl")
  class Ssl {

    @TempDir private Path directory;

    @Test
    @DisplayName("should trust the JDK's authorities without a CA file")
    void defaultTrust() throws Exception {
      assertNotNull(ConsoleHttp.ssl(null));
    }

    @Test
    @DisplayName("should refuse a CA file holding no certificate")
    void emptyFile() throws Exception {
      Path empty = directory.resolve("empty.pem");
      Files.writeString(empty, "", StandardCharsets.UTF_8);
      Path garbage = directory.resolve("garbage.pem");
      Files.writeString(garbage, "-----BEGIN CERTIFICATE-----\nnope\n", StandardCharsets.UTF_8);

      GeneralSecurityException e =
          assertThrows(GeneralSecurityException.class, () -> ConsoleHttp.ssl(empty));
      assertTrue(String.valueOf(e.getMessage()).contains("no certificate in"), e.getMessage());
      assertThrows(GeneralSecurityException.class, () -> ConsoleHttp.ssl(garbage));
      assertThrows(IOException.class, () -> ConsoleHttp.ssl(directory.resolve("missing.pem")));
    }
  }

  @Nested
  @DisplayName("against a console server")
  class AgainstServer {

    private HttpServer server;
    private ConsoleHttp http;
    private final AtomicReference<HttpExchange> last = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/console/command",
          exchange -> {
            String request = record(exchange);
            String line =
                JsonParser.parseString(request).getAsJsonObject().get("command").getAsString();
            if (line.equals("secret")) {
              reply(exchange, 403, "{\"error\":\"no such command\"}");
            } else {
              reply(exchange, 200, "{\"output\":\"ran " + line + "\"}");
            }
          });
      server.createContext(
          "/console/suggest",
          exchange -> {
            record(exchange);
            reply(exchange, 200, "{\"suggestions\":[\"list\",\"lobby\"]}");
          });
      server.createContext(
          "/console/commands",
          exchange -> {
            record(exchange);
            reply(exchange, 502, "<html>bad gateway</html>");
          });
      server.createContext(
          "/console/logs",
          exchange -> {
            record(exchange);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            reply(exchange, 200, "id: 7\ndata: hello\n\n");
          });
      server.start();
      ConsoleTarget target =
          ConsoleTarget.parse("http://127.0.0.1:" + server.getAddress().getPort(), "secret-token");
      http = new ConsoleHttp(target, ConsoleHttp.ssl(null), "session-1", "alice@laptop");
    }

    @AfterEach
    void stop() {
      server.stop(0);
    }

    private String record(HttpExchange exchange) throws IOException {
      last.set(exchange);
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      lastBody.set(body);
      return body;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }

    private HttpExchange last() {
      return Objects.requireNonNull(last.get(), "no request reached the server");
    }

    private Headers requestHeaders() {
      return last().getRequestHeaders();
    }

    @Test
    @DisplayName("should keep the target it was created for")
    void target() {
      assertEquals("127.0.0.1", http.target().name());
      assertEquals("secret-token", http.target().token());
    }

    @Test
    @DisplayName("should POST a command as JSON with the token, session and operator")
    void command() throws Exception {
      ConsoleHttp.Answer answer = http.command("list");

      assertEquals(200, answer.status());
      assertEquals("ran list", answer.body().get("output").getAsString());
      assertEquals("POST", last().getRequestMethod());
      assertEquals("/console/command", last().getRequestURI().getPath());
      assertEquals("{\"command\":\"list\"}", lastBody.get());
      assertEquals("Bearer secret-token", requestHeaders().getFirst("Authorization"));
      assertEquals("session-1", requestHeaders().getFirst(RemoteConsole.SESSION_HEADER));
      assertEquals("alice@laptop", requestHeaders().getFirst(RemoteConsole.OPERATOR_HEADER));
      assertEquals(
          "warp-console/" + WarpBuildInfo.VERSION, requestHeaders().getFirst("User-Agent"));
      assertTrue(requestHeaders().getFirst("Content-Type").startsWith("application/json"));
    }

    @Test
    @DisplayName("should hand back the server's error with its status")
    void refusedCommand() throws Exception {
      ConsoleHttp.Answer answer = http.command("secret");

      assertEquals(403, answer.status());
      assertEquals("no such command", answer.error());
    }

    @Test
    @DisplayName("should complete a line asynchronously")
    void suggest() throws Exception {
      ConsoleHttp.Answer answer = http.suggest("lo").get(10, TimeUnit.SECONDS);

      assertEquals(200, answer.status());
      assertEquals(2, answer.body().getAsJsonArray("suggestions").size());
      assertEquals("/console/suggest", last().getRequestURI().getPath());
      assertEquals("{\"line\":\"lo\"}", lastBody.get());
    }

    @Test
    @DisplayName("should GET the commands and take a page that is not JSON as its status alone")
    void commands() throws Exception {
      ConsoleHttp.Answer answer = http.commands();

      assertEquals("GET", last().getRequestMethod());
      assertEquals("/console/commands", last().getRequestURI().getPath());
      assertEquals(502, answer.status());
      assertTrue(answer.body().isEmpty());
      assertEquals("HTTP 502", answer.error());
    }

    @Test
    @DisplayName(
        "should open the log stream with its filter encoded and the last event to resume from")
    void stream() throws Exception {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("level", "info");
      query.put("filter", "a b&c");

      try (InputStream body = http.stream(query, "7").body()) {
        assertEquals(
            "id: 7\ndata: hello\n\n", new String(body.readAllBytes(), StandardCharsets.UTF_8));
      }

      assertEquals("/console/logs", last().getRequestURI().getPath());
      assertEquals("level=info&filter=a+b%26c", last().getRequestURI().getRawQuery());
      assertEquals("text/event-stream", requestHeaders().getFirst("Accept"));
      assertEquals("7", requestHeaders().getFirst("Last-Event-ID"));
      assertEquals("Bearer secret-token", requestHeaders().getFirst("Authorization"));
    }

    @Test
    @DisplayName("should open the log stream without a query or a last event when there are none")
    void streamFromTheStart() throws Exception {
      HttpResponse<InputStream> response = http.stream(Map.of(), null);
      response.body().close();

      assertEquals(200, response.statusCode());
      assertNull(last().getRequestURI().getRawQuery());
      assertNull(requestHeaders().getFirst("Last-Event-ID"));
    }
  }
}
