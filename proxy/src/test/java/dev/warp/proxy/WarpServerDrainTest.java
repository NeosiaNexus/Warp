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
package dev.warp.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.ReadinessHold;
import dev.warp.api.server.ServerInfo;
import dev.warp.proxy.config.WarpConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole proxy with its management server: readiness as it starts and as plugins hold it, then a
 * drain asked for over HTTP that stops the proxy and ends the process with its exit code.
 *
 * <p>One test: a JVM publishes one proxy through {@link dev.warp.api.WarpProvider}.
 */
@DisplayName("WarpServer drain and health checks")
class WarpServerDrainTest {

  private static final String TOKEN = "drain-me";

  @TempDir private Path directory;

  /**
   * The longest the test waits for an answer: far more than a local one takes, and less than the
   * time-out PIT gives the test (its usual time plus 4 s), so that a mutant that never answers
   * fails the test instead of hanging it until PIT kills the JVM running it, which costs a restart.
   */
  private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(3);

  /**
   * The event loops' quiet period when the proxy stops: half of Netty's 2 s, so that the test,
   * which kills more mutants than any other, runs 1 s faster, and still long enough to tell event
   * loops shut down together (one quiet period) from one after the other (two).
   */
  private static final Duration QUIET_PERIOD = Duration.ofSeconds(1);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(ANSWER_TIMEOUT).build();

  @Test
  @DisplayName(
      "should be ready once started with a server and no hold, drain on POST /drain, then stop"
          + " and exit with 0")
  void lifecycle() throws Exception {
    int game = freePort();
    int http = freePort();
    int agent = freePort();
    Files.writeString(directory.resolve("token"), TOKEN, StandardCharsets.UTF_8);
    Files.writeString(
        directory.resolve("warp.conf"),
        """
        bind = "127.0.0.1:%d"
        online-mode = false
        servers {}
        forwarding { mode = "none" }
        management {
          enabled = true
          bind = "127.0.0.1:%d"
          agent-check = "127.0.0.1:%d"
          token-file = "token"
        }
        drain {
          transfer-to = "play.example.net"
          propagation-delay = 0s
        }
        """
            .formatted(game, http, agent),
        StandardCharsets.UTF_8);
    CompletableFuture<Integer> exited = new CompletableFuture<>();
    WarpServer server =
        new WarpServer(WarpConfig.load(directory), directory, exited::complete, QUIET_PERIOD);

    assertThrows(IllegalStateException.class, server::drain, "not started yet");

    server.start();
    try {
      assertEquals(200, get(http, "/startupz").statusCode());
      HttpResponse<String> noServer = get(http, "/readyz");
      assertEquals(503, noServer.statusCode());
      assertTrue(noServer.body().contains("\"status\":\"no-server\""), noServer.body());
      assertEquals("down", agentReply(agent));

      var _ =
          server
              .servers()
              .register(
                  new ServerInfo(
                      "lobby", new InetSocketAddress(InetAddress.getLoopbackAddress(), 1)));
      assertEquals(200, get(http, "/readyz").statusCode());
      assertEquals("up ready", agentReply(agent));

      ReadinessHold hold = server.readinessHolds().hold("primemc: no lobby yet");
      HttpResponse<String> held = get(http, "/readyz");
      assertEquals(503, held.statusCode());
      assertTrue(held.body().contains("primemc: no lobby yet"), held.body());
      hold.release();
      assertEquals(200, get(http, "/readyz").statusCode());

      long drainStarted = System.nanoTime();
      HttpResponse<String> drained =
          client.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http + "/drain?wait=true"))
                  .header("Authorization", "Bearer " + TOKEN)
                  .timeout(ANSWER_TIMEOUT)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(200, drained.statusCode());
      assertTrue(drained.body().contains("\"outcome\":\"drained\""), drained.body());
      assertEquals(WarpServer.EXIT_DRAINED, exited.get(5, TimeUnit.SECONDS));
      Duration drainToExit = Duration.ofNanos(System.nanoTime() - drainStarted);
      // Nobody to drain: the time is the stop's, whose event loops each wait out the quiet period
      // before terminating. Both at once, not one after the other.
      assertTrue(
          drainToExit.compareTo(QUIET_PERIOD.multipliedBy(9).dividedBy(5)) < 0,
          "drained and stopped in " + drainToExit.toMillis() + " ms");
      assertTrue(server.isDraining());
      assertEquals(DrainPhase.STOPPING, server.drainStatus().phase());
      assertThrows(
          ConnectException.class, () -> new Socket(InetAddress.getLoopbackAddress(), http).close());
      assertThrows(
          ConnectException.class, () -> new Socket(InetAddress.getLoopbackAddress(), game).close());
    } finally {
      server.stop();
      client.close();
    }
  }

  private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(ANSWER_TIMEOUT)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String agentReply(int port) throws IOException {
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
      socket.setSoTimeout((int) ANSWER_TIMEOUT.toMillis());
      return new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
          .readLine();
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
