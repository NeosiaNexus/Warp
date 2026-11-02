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
package dev.warp.proxy.connection;

import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McIdentifier;
import dev.warp.protocol.packet.cookie.CookieRequest;
import dev.warp.protocol.packet.cookie.CookieResponse;
import dev.warp.protocol.packet.cookie.StoreCookie;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.netty.channel.EventLoop;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cookie traffic of one client connection, from 1.20.5: the requests and stores the proxy makes
 * of its own accord, and the routing of each of the client's answers to whoever asked.
 *
 * <h3>Routing</h3>
 *
 * <p>The client answers every Cookie Request with a Cookie Response for the same key, in the order
 * the requests reached it. Requests come from the proxy ({@link #request}) and from backends
 * ({@link #relay}), which may ask for the same key at the same time. Each request is recorded under
 * the canonical form of its key, in a queue per key, at the moment it is written to the client and
 * on the client's event loop, so the queue's order is the wire's. Each answer goes to the oldest
 * request of its key:
 *
 * <ul>
 *   <li>the proxy's: completes the future {@link #request} returned, and goes nowhere else;
 *   <li>a backend's: written to that backend, the one that asked, if it is still connected, and
 *       dropped otherwise. Never to another backend: a vanilla server kicks a player who answers a
 *       question it did not ask.
 * </ul>
 *
 * <p>A request the proxy stops waiting for (time-out) keeps its place: the late answer is still the
 * proxy's, and is dropped rather than handed to a backend. An answer to no request is dropped too;
 * in the login state, where the proxy is the server, its caller refuses the client instead, as
 * vanilla does.
 *
 * <h3>Threading</h3>
 *
 * <p>{@link #request} and {@link #store} may be called from any thread: the work moves to the
 * client's event loop, where everything else runs. Futures complete on that event loop, so their
 * dependent stages must not block.
 */
final class ClientCookies {

  private static final Logger logger = LoggerFactory.getLogger(ClientCookies.class);

  /** How long the proxy waits for the client to answer a request of its own, by default. */
  static final Duration DEFAULT_TIMEOUT =
      Duration.ofSeconds(ConnectedPlayer.COOKIE_TIMEOUT_SECONDS);

  /**
   * Most requests, the proxy's and the backends' together, the client may leave unanswered at once.
   * A vanilla client answers each one as soon as it reads it; one that stops answering would
   * otherwise make the proxy remember requests without end.
   */
  static final int MAX_PENDING = 256;

  private final MinecraftConnection client;
  private final ProtocolVersion version;
  private final ScheduledExecutorService timeouts;

  /** Unanswered requests by canonical key, oldest first. Accessed on the event loop only. */
  private final Map<String, Queue<Pending>> pendingByKey = new HashMap<>();

  private int pendingCount;
  private boolean closed;

  /**
   * Creates the cookie traffic of a client connection, closed with it.
   *
   * @param client the client connection
   * @param version the client's protocol version
   * @param timeouts runs the proxy's request time-outs: the client's event loop in production, one
   *     whose clock a test controls otherwise
   */
  ClientCookies(
      MinecraftConnection client, ProtocolVersion version, ScheduledExecutorService timeouts) {
    this.client = client;
    this.version = version;
    this.timeouts = timeouts;
    client.channel().closeFuture().addListener(_ -> close());
  }

  /**
   * Returns whether the client has a cookie store: from 1.20.5.
   *
   * @return {@code true} from 1.20.5
   */
  boolean supported() {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5);
  }

  // ---------------------------------------------------------------------------
  // The proxy's own requests and stores
  // ---------------------------------------------------------------------------

  /**
   * Asks the client for the cookie it holds under {@code key}, for the proxy itself: in the login
   * state, before any backend is involved, as in configuration and play. The request is written
   * once the client reads the proxy's state (see {@link MinecraftConnection#writeWhenSettled}).
   *
   * <p>The future completes with the cookie, empty if the client holds none, or exceptionally: with
   * a {@link TimeoutException} if the client has not answered within {@code timeout}, with an
   * {@link IllegalStateException} if the connection closes first, is not in a state with cookies,
   * or has too many requests unanswered. Its stages run on the client's event loop.
   *
   * @param key the cookie's identifier, with or without its namespace
   * @param timeout how long to wait for the answer
   * @return the future answer
   * @throws UnsupportedOperationException if the client predates 1.20.5
   * @throws IllegalArgumentException if {@code key} is not a valid identifier, or {@code timeout}
   *     is not positive
   */
  CompletableFuture<Optional<byte[]>> request(String key, Duration timeout) {
    requireSupported("request cookies");
    String canonical = McIdentifier.canonical(key);
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Cookie request timeout must be positive: " + timeout);
    }
    long timeoutNanos = saturatedNanos(timeout);
    CompletableFuture<Optional<byte[]>> answer = new CompletableFuture<>();
    onEventLoop(() -> sendRequest(new ProxyRequest(key, canonical, answer), timeoutNanos));
    return answer;
  }

  /**
   * Stores a cookie on the client, for the proxy itself. The client keeps it for this proxy, and
   * carries it over if a Transfer sends it elsewhere. Written once the client reads the proxy's
   * state.
   *
   * <p>Off the event loop, the call returns at once and a client no longer able to receive it
   * (disconnected, or in another state) is logged instead of thrown at.
   *
   * @param key the cookie's identifier, with or without its namespace
   * @param payload the cookie, at most 5 KiB; copied
   * @throws UnsupportedOperationException if the client predates 1.20.5
   * @throws IllegalArgumentException if {@code key} is not a valid identifier, or the payload is
   *     larger than 5 KiB
   * @throws IllegalStateException on the event loop, if the client is neither in configuration nor
   *     in play, nor moving to configuration
   */
  void store(String key, byte[] payload) {
    requireSupported("store cookies");
    StoreCookie packet = new StoreCookie(key, payload.clone());
    EventLoop loop = client.channel().eventLoop();
    if (loop.inEventLoop()) {
      sendStore(packet);
      return;
    }
    loop.execute(
        () -> {
          try {
            sendStore(packet);
          } catch (IllegalStateException e) {
            logger.warn("Cookie {} not stored on {}: {}", key, describeClient(), e.getMessage());
          }
        });
  }

  private void sendStore(StoreCookie packet) {
    if (!client.channel().isActive()) {
      logger.debug("Cookie {} not stored on {}: disconnected", packet.key(), describeClient());
      return;
    }
    ProtocolState state = client.encoder().state();
    boolean storable =
        state == ProtocolState.CONFIGURATION
            || state == ProtocolState.PLAY
            // after Login Success, the client already reads the configuration state
            || (state == ProtocolState.LOGIN && client.isStateChangePending());
    if (!storable) {
      throw new IllegalStateException(
          "Cookies are stored in the configuration and play states, not in " + state);
    }
    client.writeWhenSettled(packet);
  }

  private void sendRequest(ProxyRequest request, long timeoutNanos) {
    if (closed || !client.channel().isActive()) {
      request.fail(disconnected(request.key()));
      return;
    }
    ProtocolState state = client.encoder().state();
    if (state != ProtocolState.LOGIN
        && state != ProtocolState.CONFIGURATION
        && state != ProtocolState.PLAY) {
      request.fail(
          new IllegalStateException(
              "Cookies are requested in the login, configuration and play states, not in "
                  + state));
      return;
    }
    if (pendingCount >= MAX_PENDING) {
      request.fail(
          new IllegalStateException(
              "The client left " + pendingCount + " cookie requests unanswered"));
      return;
    }
    enqueue(request.canonicalKey(), request);
    request.timeout =
        timeouts.schedule(
            () -> onEventLoop(() -> timedOut(request, timeoutNanos)),
            timeoutNanos,
            TimeUnit.NANOSECONDS);
    client.writeWhenSettled(new CookieRequest(request.key()));
    keepReadingForAnswer();
  }

  private void timedOut(ProxyRequest request, long timeoutNanos) {
    // The request keeps its place in the queue: the client's late answer is still the proxy's.
    request.fail(
        new TimeoutException(
            "No answer to the cookie request for "
                + request.canonicalKey()
                + " within "
                + TimeUnit.NANOSECONDS.toMillis(timeoutNanos)
                + " ms"));
  }

  /** {@code duration} in nanoseconds, or the longest delay there is when that overflows. */
  private static long saturatedNanos(Duration duration) {
    try {
      return duration.toNanos();
    } catch (ArithmeticException tooLong) {
      return Long.MAX_VALUE;
    }
  }

  // ---------------------------------------------------------------------------
  // Backends' requests
  // ---------------------------------------------------------------------------

  /**
   * Passes a backend's cookie request on to the client, and records that the answer goes back to
   * that backend. Called on the event loop.
   *
   * <p>The request is written in the client's state, in turn with what the backend sent around it:
   * a request made in the backend's configuration or play state is in the client's own state, one
   * made in the backend's login state reaches a client past its own. Like the proxy's own packets,
   * it waits for a state change the client has yet to acknowledge.
   *
   * @param backend the backend that asked
   * @param request its request, relayed with the key exactly as the backend wrote it
   */
  void relay(MinecraftConnection backend, CookieRequest request) {
    if (closed || !client.channel().isActive()) {
      return; // the backend goes when the player does
    }
    if (pendingCount >= MAX_PENDING) {
      logger.warn(
          "Disconnecting {}: {} cookie requests left unanswered", describeClient(), pendingCount);
      client.close();
      return;
    }
    enqueue(McIdentifier.canonical(request.key()), new BackendRequest(backend));
    client.writeWhenSettled(request);
    keepReadingForAnswer();
  }

  // ---------------------------------------------------------------------------
  // The client's answers
  // ---------------------------------------------------------------------------

  /**
   * Routes one of the client's answers to the oldest request of its key. Called on the event loop.
   *
   * @param response the client's answer
   * @return {@code false} if no request of that key awaits an answer: the answer was dropped
   */
  boolean answered(CookieResponse response) {
    String canonical = McIdentifier.canonical(response.key());
    Queue<Pending> queue = pendingByKey.get(canonical);
    if (queue == null) {
      logger.debug("Dropped a cookie {} that {} sent unasked", canonical, describeClient());
      return false;
    }
    Pending oldest = queue.remove(); // a key's queue goes with its last request: never empty
    if (queue.isEmpty()) {
      pendingByKey.remove(canonical);
    }
    pendingCount--;
    switch (oldest) {
      case ProxyRequest request -> request.answer(response.payload());
      case BackendRequest(MinecraftConnection backend) -> forward(backend, response);
    }
    return true;
  }

  private void forward(MinecraftConnection backend, CookieResponse response) {
    if (!backend.channel().isActive() || !backend.encoder().canEncode(CookieResponse.class)) {
      logger.trace("Dropped cookie {} for a backend {} left", response.key(), describeClient());
      return;
    }
    backend.writeAndFlush(response);
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Fails every request of the proxy still waiting, and forgets every request. Called when the
   * client connection closes; idempotent.
   */
  void close() {
    if (closed) {
      return;
    }
    closed = true;
    for (Queue<Pending> queue : pendingByKey.values()) {
      for (Pending pending : queue) {
        if (pending instanceof ProxyRequest request) {
          request.fail(disconnected(request.key()));
        }
      }
    }
    pendingByKey.clear();
    pendingCount = 0;
  }

  /**
   * Returns how many requests the client has yet to answer.
   *
   * @return the proxy's and the backends' together, timed out ones included
   */
  int pendingCount() {
    return pendingCount;
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private void enqueue(String canonicalKey, Pending pending) {
    pendingByKey.computeIfAbsent(canonicalKey, _ -> new ArrayDeque<>()).add(pending);
    pendingCount++;
  }

  /**
   * Keeps reading a client that waits between two servers, whose reads the switch paused, so that
   * its answer does not wait for the next server's configuration. What else it sends meanwhile is
   * held for that server.
   */
  private void keepReadingForAnswer() {
    if (client.sessionHandler() instanceof SwitchWaitSessionHandler waiting) {
      waiting.readAnswers();
    }
  }

  private void requireSupported(String what) {
    if (!supported()) {
      throw new UnsupportedOperationException(
          "Cannot "
              + what
              + ": cookies need Minecraft 1.20.5 or newer, the client plays "
              + version.name());
    }
  }

  private IllegalStateException disconnected(String key) {
    return new IllegalStateException(
        "The client disconnected before answering the cookie request for " + key);
  }

  private String describeClient() {
    return String.valueOf(client.channel().remoteAddress());
  }

  /** Runs {@code task} on the client's event loop: right away when already on it. */
  private void onEventLoop(Runnable task) {
    EventLoop loop = client.channel().eventLoop();
    if (loop.inEventLoop()) {
      task.run();
    } else {
      loop.execute(task);
    }
  }

  /** A request the client has yet to answer. */
  private sealed interface Pending permits ProxyRequest, BackendRequest {}

  /** A backend's request: the answer is written to that backend. */
  private record BackendRequest(MinecraftConnection backend) implements Pending {}

  /** The proxy's own request: the answer completes its future. */
  private static final class ProxyRequest implements Pending {

    private final String key;
    private final String canonicalKey;
    private final CompletableFuture<Optional<byte[]>> answer;
    private @Nullable ScheduledFuture<?> timeout;

    ProxyRequest(String key, String canonicalKey, CompletableFuture<Optional<byte[]>> answer) {
      this.key = key;
      this.canonicalKey = canonicalKey;
      this.answer = answer;
    }

    String key() {
      return key;
    }

    String canonicalKey() {
      return canonicalKey;
    }

    void answer(byte @Nullable [] payload) {
      cancelTimeout();
      var _ = answer.complete(Optional.ofNullable(payload));
    }

    void fail(Throwable cause) {
      cancelTimeout();
      var _ = answer.completeExceptionally(cause);
    }

    private void cancelTimeout() {
      ScheduledFuture<?> task = timeout;
      if (task != null) {
        var _ = task.cancel(false);
        timeout = null;
      }
    }
  }
}
