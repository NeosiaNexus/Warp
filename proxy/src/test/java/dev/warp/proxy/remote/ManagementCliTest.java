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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ManagementCli")
class ManagementCliTest {

  @TempDir private Path directory;

  private ManagementCli.Options parse(String command, String... args)
      throws ManagementCli.UsageException {
    return ManagementCli.parse(
        command, List.of(args), Map.of(ManagementCli.ENV_TOKEN, "env-token")::get, directory);
  }

  private ManagementCli.Options parseWithoutEnv(String command, String... args)
      throws ManagementCli.UsageException {
    return ManagementCli.parse(command, List.of(args), name -> null, directory);
  }

  @Nested
  @DisplayName("health arguments")
  class HealthArguments {

    @Test
    @DisplayName("should probe readiness on the local management server by default")
    void defaults() throws Exception {
      ManagementCli.Options options = parse("health");

      assertFalse(options.drain());
      assertEquals(URI.create("http://127.0.0.1:9901/readyz"), options.uri());
      assertEquals("", options.token());
      assertNull(options.caFile());
      assertEquals(Duration.ofSeconds(10), options.timeout());
    }

    @Test
    @DisplayName("should take another probe, and add /readyz to a bare base URL")
    void url() throws Exception {
      assertEquals(
          URI.create("https://warp:9901/livez"),
          parse("health", "--url", "https://warp:9901/livez").uri());
      assertEquals(
          URI.create("http://10.0.0.5:9901/readyz"),
          parse("health", "--url", "http://10.0.0.5:9901").uri());
      assertEquals(
          URI.create("http://10.0.0.5:9901/readyz"),
          parse("health", "--url", "http://10.0.0.5:9901/").uri());
    }

    @Test
    @DisplayName("should take a time-out in seconds and a CA file")
    void timeoutAndCa() throws Exception {
      ManagementCli.Options options = parse("health", "--timeout", "3", "--ca-file", "ca.pem");

      assertEquals(Duration.ofSeconds(3), options.timeout());
      assertEquals(directory.resolve("ca.pem"), options.caFile());
    }

    @Test
    @DisplayName("should refuse drain options, bad URLs and bad time-outs")
    void refusals() {
      assertThrows(ManagementCli.UsageException.class, () -> parse("health", "--wait"));
      assertThrows(ManagementCli.UsageException.class, () -> parse("health", "--token-file", "x"));
      assertThrows(ManagementCli.UsageException.class, () -> parse("health", "--url", "warp:9901"));
      assertThrows(
          ManagementCli.UsageException.class, () -> parse("health", "--url", "ftp://warp/"));
      assertThrows(ManagementCli.UsageException.class, () -> parse("health", "--timeout", "0"));
      assertThrows(ManagementCli.UsageException.class, () -> parse("health", "--timeout", "x"));
      assertThrows(ManagementCli.UsageException.class, () -> parse("health", "--timeout"));
      assertThrows(ManagementCli.UsageException.class, () -> parse("health", "readyz"));
      assertThrows(ManagementCli.UsageException.class, () -> parse("status"));
    }
  }

  @Nested
  @DisplayName("drain arguments")
  class DrainArguments {

    @Test
    @DisplayName("should POST /drain with the token of the environment by default")
    void defaults() throws Exception {
      ManagementCli.Options options = parse("drain");

      assertTrue(options.drain());
      assertEquals(URI.create("http://127.0.0.1:9901/drain"), options.uri());
      assertEquals("env-token", options.token());
    }

    @Test
    @DisplayName("should wait for the drain's end on --wait, as long as a drain may last")
    void waitsForTheEnd() throws Exception {
      ManagementCli.Options options = parse("drain", "--wait", "--url", "http://warp:9901/");

      assertEquals(URI.create("http://warp:9901/drain?wait=true"), options.uri());
      assertTrue(options.timeout().compareTo(Duration.ofHours(1)) > 0);
    }

    @Test
    @DisplayName("should read the token from a file, over the environment")
    void tokenFile() throws Exception {
      Files.writeString(directory.resolve("drain.token"), "file-token\n", StandardCharsets.UTF_8);

      assertEquals("file-token", parse("drain", "--token-file", "drain.token").token());
    }

    @Test
    @DisplayName("should refuse a token on the command line, and no token at all")
    void tokenRefusals() throws Exception {
      Files.writeString(directory.resolve("empty.token"), "\n", StandardCharsets.UTF_8);

      assertThrows(ManagementCli.UsageException.class, () -> parse("drain", "--token", "x"));
      assertThrows(ManagementCli.UsageException.class, () -> parseWithoutEnv("drain"));
      assertThrows(
          ManagementCli.UsageException.class, () -> parse("drain", "--token-file", "missing"));
      assertThrows(
          ManagementCli.UsageException.class, () -> parse("drain", "--token-file", "empty.token"));
    }
  }

  @Nested
  @DisplayName("against a management server")
  class AgainstServer {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws Exception {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/readyz",
          exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.createContext(
          "/livez",
          exchange -> {
            byte[] body = "{\"error\":\"draining\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.createContext(
          "/drain",
          exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean post = exchange.getRequestMethod().equals("POST");
            boolean authorized = "Bearer env-token".equals(authorization.get());
            String query = exchange.getRequestURI().getQuery();
            byte[] body =
                ("{\"phase\":" + ("wait=true".equals(query) ? 3 : 1) + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(!post ? 405 : authorized ? 202 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
      server.stop(0);
    }

    private int execute(String command, String... args) throws Exception {
      ManagementCli.Options options = parse(command, args);
      return ManagementCli.execute(options, new PrintStream(out), new PrintStream(err));
    }

    @Test
    @DisplayName("should exit 0 and print the answer when the probe says yes")
    void healthy() throws Exception {
      assertEquals(ManagementCli.OK, execute("health", "--url", base));
      assertTrue(out.toString(StandardCharsets.UTF_8).contains("/readyz: 200"));
    }

    @Test
    @DisplayName("should exit 1 with the reason when the probe says no")
    void unhealthy() throws Exception {
      assertEquals(ManagementCli.FAILED, execute("health", "--url", base + "/livez"));
      assertTrue(err.toString(StandardCharsets.UTF_8).contains("503"));
      assertTrue(err.toString(StandardCharsets.UTF_8).contains("draining"));
    }

    @Test
    @DisplayName("should exit 3 when nothing listens")
    void unreachable() throws Exception {
      server.stop(0);

      assertEquals(ManagementCli.UNREACHABLE, execute("health", "--url", base));
      assertTrue(err.toString(StandardCharsets.UTF_8).contains("cannot be reached"));
    }

    @Test
    @DisplayName("should start the drain with the bearer token, and wait when asked")
    void drain() throws Exception {
      assertEquals(ManagementCli.OK, execute("drain", "--url", base));
      assertEquals("Bearer env-token", authorization.get());
      assertTrue(out.toString(StandardCharsets.UTF_8).contains("/drain: 202"));

      out.reset();
      assertEquals(ManagementCli.OK, execute("drain", "--url", base, "--wait"));
      assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"phase\":3"));
    }

    @Test
    @DisplayName("should exit 1 and say what to do when the token is refused")
    void refused() throws Exception {
      Files.writeString(directory.resolve("wrong.token"), "wrong", StandardCharsets.UTF_8);

      assertEquals(
          ManagementCli.FAILED, execute("drain", "--url", base, "--token-file", "wrong.token"));
      assertTrue(err.toString(StandardCharsets.UTF_8).contains("token was refused"));
    }
  }
}
