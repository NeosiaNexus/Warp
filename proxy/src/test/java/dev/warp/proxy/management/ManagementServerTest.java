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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.lifecycle.DrainOutcome;
import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.proxy.config.ManagementConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ManagementServer")
class ManagementServerTest {

  private static final String TOKEN = "s3cret-token";
  private static final InetSocketAddress ANY_PORT =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

  private final FakeHealth health = new FakeHealth();

  /**
   * The longest the tests wait for an answer: far more than a local one takes, and less than the
   * time-out PIT gives a test (its usual time plus 4 s), so that a mutant that never answers fails
   * the test instead of hanging it until PIT kills the JVM running it, which costs a restart.
   */
  private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(3);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(ANSWER_TIMEOUT).build();
  private @Nullable ManagementServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop();
    }
    client.close();
  }

  private ManagementServer start(@Nullable String token, boolean requireServer) {
    ManagementServer started =
        new ManagementServer(
            new ManagementConfig(true, ANY_PORT, ANY_PORT, token, requireServer), health);
    started.start();
    server = started;
    return started;
  }

  private ManagementServer start() {
    return start(TOKEN, true);
  }

  private HttpResponse<String> send(String method, String path, @Nullable String token)
      throws IOException, InterruptedException {
    ManagementServer running = server;
    assertNotNull(running);
    InetSocketAddress address = running.httpAddress();
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + address.getPort() + path))
            .timeout(ANSWER_TIMEOUT)
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return send("GET", path, null);
  }

  private static JsonObject json(HttpResponse<String> response) {
    assertEquals(
        "application/json; charset=utf-8",
        response.headers().firstValue("content-type").orElse(""));
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  private String agentReply() throws IOException {
    ManagementServer running = server;
    assertNotNull(running);
    InetSocketAddress agent = running.agentCheckAddress();
    assertNotNull(agent);
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), agent.getPort())) {
      socket.setSoTimeout((int) ANSWER_TIMEOUT.toMillis());
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      String line = reader.readLine();
      assertEquals(-1, reader.read(), "the agent closes after one line");
      return line;
    }
  }

  // ---------------------------------------------------------------------------
  // Probes
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("probes")
  class Probes {

    @Test
    @DisplayName("should answer liveness while the proxy starts, drains and stops")
    void liveness() throws Exception {
      start();

      assertEquals(200, get("/livez").statusCode());
      health.draining = true;
      assertEquals(200, get("/livez").statusCode());
      health.stopping = true;
      HttpResponse<String> response = get("/livez");

      assertEquals(200, response.statusCode());
      assertEquals("alive", json(response).get("status").getAsString());
    }

    @Test
    @DisplayName("should answer startup 503 until the proxy listens with its plugins enabled")
    void startup() throws Exception {
      start();
      health.started = false;

      HttpResponse<String> starting = get("/startupz");
      health.started = true;
      HttpResponse<String> started = get("/startupz");

      assertEquals(503, starting.statusCode());
      assertEquals("starting", json(starting).get("status").getAsString());
      assertEquals(200, started.statusCode());
    }

    @Test
    @DisplayName("should be ready only once started, with a server, no hold and no drain")
    void readiness() throws Exception {
      start();
      health.started = false;
      assertReadiness(503, "starting");

      health.started = true;
      health.servers = 0;
      assertReadiness(503, "no-server");

      health.servers = 2;
      health.holds.add("primemc: no lobby yet");
      HttpResponse<String> held = assertReadiness(503, "held");
      assertEquals(
          "primemc: no lobby yet", json(held).getAsJsonArray("holds").get(0).getAsString());

      health.holds.clear();
      HttpResponse<String> ready = assertReadiness(200, "ready");
      assertTrue(json(ready).get("ready").getAsBoolean());
      assertEquals(2, json(ready).get("servers").getAsInt());

      health.draining = true;
      assertReadiness(503, "draining");

      health.stopping = true;
      assertReadiness(503, "stopping");
    }

    @Test
    @DisplayName("should be ready without a server when readiness does not require one")
    void readyWithoutServer() throws Exception {
      start(TOKEN, false);
      health.servers = 0;

      assertReadiness(200, "ready");
    }

    @Test
    @DisplayName("should answer HEAD with the status and no body")
    void head() throws Exception {
      start();
      health.started = false;

      HttpResponse<String> response = send("HEAD", "/readyz", null);

      assertEquals(503, response.statusCode());
      assertEquals("", response.body());
    }

    @Test
    @DisplayName("should refuse other methods on a probe, saying which it allows")
    void methodNotAllowed() throws Exception {
      start();

      HttpResponse<String> response = send("POST", "/readyz", TOKEN);

      assertEquals(405, response.statusCode());
      assertEquals("GET, HEAD", response.headers().firstValue("allow").orElse(""));
    }

    @Test
    @DisplayName("should answer 404 for any other path")
    void notFound() throws Exception {
      start();

      HttpResponse<String> response = get("/metrics");

      assertEquals(404, response.statusCode());
      assertTrue(json(response).has("error"));
    }

    @Test
    @DisplayName("should answer on its own event loop, never a game event loop or a plugin thread")
    void ownThread() throws Exception {
      start();

      get("/readyz");

      assertFalse(health.threads.isEmpty());
      for (String thread : health.threads) {
        assertTrue(thread.startsWith("warp-management"), thread);
      }
    }

    @Test
    @DisplayName("should answer probes while a client holds a connection without a request")
    void slowClient() throws Exception {
      start();
      ManagementServer running = server;
      assertNotNull(running);

      try (Socket idle =
          new Socket(InetAddress.getLoopbackAddress(), running.httpAddress().getPort())) {
        OutputStream out = idle.getOutputStream();
        out.write("GET /readyz HTTP/1.1\r\nHost: x\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();

        assertEquals(200, get("/livez").statusCode());
      }
    }

    private HttpResponse<String> assertReadiness(int code, String status) throws Exception {
      HttpResponse<String> response = get("/readyz");
      assertEquals(code, response.statusCode(), response.body());
      assertEquals(status, json(response).get("status").getAsString());
      assertEquals("no-store", response.headers().firstValue("cache-control").orElse(""));
      return response;
    }
  }

  // ---------------------------------------------------------------------------
  // Drain
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("drain endpoint")
  class Drain {

    @Test
    @DisplayName("should refuse POST /drain when no token is configured, whatever is sent")
    void noToken() throws Exception {
      start(null, true);

      HttpResponse<String> response = send("POST", "/drain", "anything");

      assertEquals(403, response.statusCode());
      assertTrue(json(response).get("error").getAsString().contains("WARP_MANAGEMENT_TOKEN"));
      assertTrue(health.drains.isEmpty());
    }

    @Test
    @DisplayName("should refuse POST /drain without the right bearer token")
    void wrongToken() throws Exception {
      start();

      HttpResponse<String> missing = send("POST", "/drain", null);
      HttpResponse<String> wrong = send("POST", "/drain", "s3cret-tokem");
      HttpResponse<String> prefix = send("POST", "/drain", "s3cret");

      for (HttpResponse<String> response : List.of(missing, wrong, prefix)) {
        assertEquals(401, response.statusCode());
        assertEquals(
            "Bearer realm=\"warp\"", response.headers().firstValue("www-authenticate").orElse(""));
      }
      assertTrue(health.drains.isEmpty());
    }

    @Test
    @DisplayName("should start the drain with the token and answer 202 at once, then 200")
    void starts() throws Exception {
      start();

      HttpResponse<String> first = send("POST", "/drain", TOKEN);
      HttpResponse<String> again = send("POST", "/drain", TOKEN);

      assertEquals(202, first.statusCode());
      assertEquals("announcing", json(first).get("phase").getAsString());
      assertEquals("http", json(first).get("trigger").getAsString());
      assertEquals(200, again.statusCode());
      assertEquals(List.of(DrainTrigger.HTTP, DrainTrigger.HTTP), health.drains);
    }

    @Test
    @DisplayName("should accept the scheme in any case, as HTTP allows")
    void schemeCase() throws Exception {
      start();
      ManagementServer running = server;
      assertNotNull(running);

      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + running.httpAddress().getPort() + "/drain"))
                  .header("Authorization", "bearer " + TOKEN)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(202, response.statusCode());
    }

    @Test
    @DisplayName("should refuse to drain a proxy that has not started")
    void notStarted() throws Exception {
      start();
      health.started = false;

      HttpResponse<String> response = send("POST", "/drain", TOKEN);

      assertEquals(503, response.statusCode());
      assertTrue(health.drains.isEmpty());
    }

    @Test
    @DisplayName("should hold a waiting request until the drain is over, then answer its outcome")
    void waits() throws Exception {
      ManagementServer running = start();

      CompletableFuture<HttpResponse<String>> response =
          client.sendAsync(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:"
                              + running.httpAddress().getPort()
                              + "/drain?wait=true"))
                  .header("Authorization", "Bearer " + TOKEN)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      health.awaitDrain();
      TimeUnit.MILLISECONDS.sleep(200);

      assertFalse(response.isDone(), "the drain is not over");

      health.finish(DrainOutcome.DRAINED);
      HttpResponse<String> done = response.get(ANSWER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

      assertEquals(200, done.statusCode());
      assertEquals("drained", json(done).get("outcome").getAsString());
      assertEquals("stopping", json(done).get("phase").getAsString());
    }

    @Test
    @DisplayName("should report the drain's status on GET, without a token")
    void status() throws Exception {
      start();
      health.status =
          new DrainStatus(
              DrainPhase.WAITING,
              DrainTrigger.SIGNAL,
              Instant.parse("2026-11-02T12:00:00Z"),
              Duration.ofSeconds(42),
              3,
              40,
              2,
              1,
              5,
              null);
      health.draining = true;

      JsonObject body = json(get("/drain"));

      assertTrue(body.get("draining").getAsBoolean());
      assertEquals("waiting", body.get("phase").getAsString());
      assertEquals("signal", body.get("trigger").getAsString());
      assertEquals("2026-11-02T12:00:00Z", body.get("startedAt").getAsString());
      assertEquals(42_000, body.get("elapsedMillis").getAsLong());
      assertEquals(3, body.get("players").getAsInt());
      assertEquals(40, body.get("transferred").getAsInt());
      assertEquals(2, body.get("kept").getAsInt());
      assertEquals(1, body.get("kicked").getAsInt());
      assertEquals(5, body.get("refused").getAsInt());
      assertTrue(body.get("outcome").isJsonNull());
    }
  }

  // ---------------------------------------------------------------------------
  // Agent check
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("HAProxy agent check")
  class AgentCheck {

    @Test
    @DisplayName("should answer up ready, drain or down, one line per connection")
    void replies() throws Exception {
      start();

      assertEquals("up ready", agentReply());
      health.holds.add("hold");
      assertEquals("down", agentReply());
      health.holds.clear();
      health.draining = true;
      assertEquals("drain", agentReply());
      health.status = stopping(DrainOutcome.DRAINED);
      assertEquals("down", agentReply(), "once drained, so that sticky sessions stop too");
    }

    @Test
    @DisplayName("should answer down while starting")
    void starting() throws Exception {
      start();
      health.started = false;

      assertEquals("down", agentReply());
    }
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("should fail to start on a port in use, leaving nothing running")
  void portInUse() throws Exception {
    ManagementServer first = start();
    ManagementServer second =
        new ManagementServer(
            new ManagementConfig(true, first.httpAddress(), null, TOKEN, true), health);

    assertThrows(IllegalStateException.class, second::start);
  }

  @Test
  @DisplayName("should close its ports when stopped")
  void stops() throws Exception {
    ManagementServer running = start();
    int port = running.httpAddress().getPort();

    running.stop();
    server = null;

    assertThrows(
        ConnectException.class, () -> new Socket(InetAddress.getLoopbackAddress(), port).close());
  }

  private static DrainStatus stopping(DrainOutcome outcome) {
    return new DrainStatus(
        DrainPhase.STOPPING,
        DrainTrigger.HTTP,
        Instant.EPOCH,
        Duration.ZERO,
        0,
        0,
        0,
        0,
        0,
        outcome);
  }

  /** The proxy's state as a test sets it, recording the threads that read it. */
  private static final class FakeHealth implements ProxyHealth {

    private volatile boolean started = true;
    private volatile boolean stopping;
    private volatile int servers = 1;
    private final List<String> holds = new CopyOnWriteArrayList<>();
    private volatile boolean draining;
    private volatile DrainStatus status = DrainStatus.notDraining(0);
    private final List<DrainTrigger> drains = new CopyOnWriteArrayList<>();
    private final Set<String> threads = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<DrainStatus> done = new CompletableFuture<>();
    private final CompletableFuture<Void> asked = new CompletableFuture<>();

    @Override
    public boolean isStarted() {
      threads.add(Thread.currentThread().getName());
      return started;
    }

    @Override
    public boolean isStopping() {
      return stopping;
    }

    @Override
    public int serverCount() {
      return servers;
    }

    @Override
    public List<String> readinessHolds() {
      return List.copyOf(holds);
    }

    @Override
    public boolean isDraining() {
      return draining;
    }

    @Override
    public DrainStatus drainStatus() {
      return status;
    }

    @Override
    public CompletableFuture<DrainStatus> drain(DrainTrigger trigger) {
      drains.add(trigger);
      if (!draining) {
        draining = true;
        status =
            new DrainStatus(
                DrainPhase.ANNOUNCING, trigger, Instant.EPOCH, Duration.ZERO, 0, 0, 0, 0, 0, null);
      }
      asked.complete(null);
      return done;
    }

    void awaitDrain() throws Exception {
      asked.get(ANSWER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    void finish(DrainOutcome outcome) {
      status = stopping(outcome);
      done.complete(status);
    }
  }
}
