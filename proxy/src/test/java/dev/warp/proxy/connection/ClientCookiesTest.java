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

import static dev.warp.proxy.connection.SwitchHarness.LOBBY;
import static dev.warp.proxy.connection.SwitchHarness.SURVIVAL;
import static dev.warp.proxy.connection.SwitchHarness.cookieAnswer;
import static dev.warp.proxy.connection.SwitchHarness.cookieQuestion;
import static dev.warp.proxy.connection.SwitchHarness.opaqueFrame;
import static dev.warp.proxy.connection.SwitchHarness.types;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.cookie.CookieRequest;
import dev.warp.protocol.packet.cookie.CookieResponse;
import dev.warp.protocol.packet.cookie.StoreCookie;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The cookie traffic of a player: the proxy's own requests and stores, the backends' requests, and
 * the routing of each of the client's answers to whoever asked.
 */
@DisplayName("ClientCookies")
class ClientCookiesTest {

  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

  private static final byte[] SESSION = {1, 2, 3};

  private final List<SwitchHarness> harnesses = new ArrayList<>();

  @AfterEach
  void tearDown() {
    harnesses.forEach(SwitchHarness::close);
  }

  // ---------------------------------------------------------------------------
  // The proxy's requests
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("the proxy's requests")
  class ProxyRequests {

    @Test
    @DisplayName("should ask the client and complete with its cookie, telling the backend nothing")
    void completesWithCookie() throws Exception {
      SwitchHarness setup = playingOnLobby();

      CompletableFuture<Optional<byte[]>> cookie = setup.player.requestCookie("primemc:session");
      CookieRequest asked = onlySent(setup, CookieRequest.class);
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "primemc:session", SESSION));

      assertEquals("primemc:session", asked.key());
      assertArrayEquals(SESSION, cookie.get().orElseThrow());
      assertTrue(setup.framesSentOn(setup.current()).isEmpty(), "the backend asked nothing");
      assertEquals(0, setup.player.cookies().pendingCount());
    }

    @Test
    @DisplayName("should complete empty when the client holds no cookie under the key")
    void emptyWhenAbsent() throws Exception {
      SwitchHarness setup = playingOnLobby();

      CompletableFuture<Optional<byte[]>> cookie = setup.player.requestCookie("primemc:session");
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "primemc:session", null));

      assertEquals(Optional.empty(), cookie.get());
    }

    @Test
    @DisplayName("should tell an empty cookie apart from no cookie")
    void emptyCookieIsPresent() throws Exception {
      SwitchHarness setup = playingOnLobby();

      CompletableFuture<Optional<byte[]>> cookie = setup.player.requestCookie("primemc:session");
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "primemc:session", new byte[0]));

      assertEquals(0, cookie.get().orElseThrow().length);
    }

    @Test
    @DisplayName("should take the client's namespaced answer for a key asked without namespace")
    void canonicalKeys() throws Exception {
      SwitchHarness setup = playingOnLobby();

      CompletableFuture<Optional<byte[]>> cookie = setup.player.requestCookie("session");
      CookieRequest asked = onlySent(setup, CookieRequest.class);
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "minecraft:session", SESSION));

      assertEquals("session", asked.key(), "the key goes out as written");
      assertArrayEquals(SESSION, cookie.get().orElseThrow());
    }

    @Test
    @DisplayName("should answer several requests for the same key in the order they were made")
    void sameKeyInOrder() throws Exception {
      SwitchHarness setup = playingOnLobby();

      CompletableFuture<Optional<byte[]>> first = setup.player.requestCookie("primemc:session");
      CompletableFuture<Optional<byte[]>> second = setup.player.requestCookie("primemc:session");
      setup.fromClient(
          cookieAnswer(VERSION, ProtocolState.PLAY, "primemc:session", new byte[] {1}));

      assertTrue(first.isDone());
      assertFalse(second.isDone());
      setup.fromClient(
          cookieAnswer(VERSION, ProtocolState.PLAY, "primemc:session", new byte[] {2}));
      assertArrayEquals(new byte[] {1}, first.get().orElseThrow());
      assertArrayEquals(new byte[] {2}, second.get().orElseThrow());
    }

    @Test
    @DisplayName("should fail at once when the client left too many requests unanswered")
    void tooManyPending() {
      SwitchHarness setup = playingOnLobby();
      for (int i = 0; i < ClientCookies.MAX_PENDING; i++) {
        var _ = setup.player.requestCookie("primemc:key" + i);
      }

      CompletableFuture<Optional<byte[]>> refused = setup.player.requestCookie("primemc:more");

      assertInstanceOf(IllegalStateException.class, failureOf(refused));
      assertEquals(ClientCookies.MAX_PENDING, setup.player.cookies().pendingCount());
    }

    @Test
    @DisplayName("should refuse a client older than 1.20.5, which has no cookies")
    void refusesOldClient() {
      SwitchHarness setup = harness(ProtocolVersion.MINECRAFT_1_20_4, ProtocolState.PLAY);

      UnsupportedOperationException refused =
          assertThrows(UnsupportedOperationException.class, () -> setup.player.requestCookie("a"));
      assertEquals(
          "Cannot request cookies: cookies need Minecraft 1.20.5 or newer, the client plays 1.20.4",
          refused.getMessage());
    }

    @Test
    @DisplayName(
        "should refuse a key that is not an identifier, and a time-out that is not positive")
    void refusesInvalidArguments() {
      SwitchHarness setup = playingOnLobby();

      assertThrows(IllegalArgumentException.class, () -> setup.player.requestCookie("Bad Key"));
      assertThrows(
          IllegalArgumentException.class,
          () -> setup.player.requestCookie("primemc:session", Duration.ZERO));
      assertTrue(setup.sentToClient().isEmpty());
    }

    @Test
    @DisplayName("should fail a request for a client in a state without cookies")
    void refusesStateWithoutCookies() {
      SwitchHarness setup = harness(VERSION, ProtocolState.STATUS);

      CompletableFuture<Optional<byte[]>> refused = setup.player.requestCookie("primemc:session");

      assertInstanceOf(IllegalStateException.class, failureOf(refused));
      assertTrue(setup.sentToClient().isEmpty());
    }
  }

  // ---------------------------------------------------------------------------
  // Backends' requests
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("backends' requests")
  class BackendRequests {

    @Test
    @DisplayName("should relay a backend's request untouched and hand it the client's answer as is")
    void relaysBothWays() {
      SwitchHarness setup = playingOnLobby();
      MinecraftConnection lobby = setup.current();
      ByteBuf question = cookieQuestion(VERSION, ProtocolState.PLAY, "lobby:visits");
      byte[] questionBytes = ByteBufUtil.getBytes(question);
      ByteBuf answer = cookieAnswer(VERSION, ProtocolState.PLAY, "lobby:visits", SESSION);
      byte[] answerBytes = ByteBufUtil.getBytes(answer);

      setup.receive(lobby, question);
      List<byte[]> relayed = setup.framesToClient();
      setup.fromClient(answer);
      List<byte[]> answered = setup.framesSentOn(lobby);

      assertEquals(1, relayed.size());
      assertArrayEquals(questionBytes, relayed.getFirst());
      assertEquals(1, answered.size());
      assertArrayEquals(answerBytes, answered.getFirst());
    }

    @Test
    @DisplayName("should not relay a request from a backend the client no longer plays on")
    void ignoresServerBeingLeft() {
      SwitchHarness setup = playingOnLobby();
      MinecraftConnection lobby = setup.current();
      setup.holdConnections();
      setup.player.switchServer(SURVIVAL);
      // Survival accepts the player: the client leaves the lobby.
      setup.login(SURVIVAL);
      setup.sentToClient();

      setup.receive(lobby, cookieQuestion(VERSION, ProtocolState.PLAY, "lobby:visits"));

      assertTrue(setup.sentToClient().isEmpty());
      assertEquals(0, setup.player.cookies().pendingCount());
    }

    @Test
    @DisplayName("should drop the answer for a backend that left, not give it to another one")
    void answerForBackendThatLeft() {
      SwitchHarness setup = playingOnLobby();
      MinecraftConnection lobby = setup.current();
      setup.receive(lobby, cookieQuestion(VERSION, ProtocolState.PLAY, "lobby:visits"));
      MinecraftConnection survival = playOn(setup, SURVIVAL);
      lobby.close();

      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "lobby:visits", SESSION));

      assertTrue(setup.framesSentOn(survival).isEmpty());
      assertEquals(0, setup.player.cookies().pendingCount());
    }

    @Test
    @DisplayName("should disconnect a client that leaves too many requests unanswered")
    void tooManyPendingFromBackend() {
      SwitchHarness setup = playingOnLobby();
      MinecraftConnection lobby = setup.current();
      for (int i = 0; i < ClientCookies.MAX_PENDING; i++) {
        setup.receive(lobby, cookieQuestion(VERSION, ProtocolState.PLAY, "lobby:key" + i));
      }
      assertTrue(setup.client.channel().isOpen());

      setup.receive(lobby, cookieQuestion(VERSION, ProtocolState.PLAY, "lobby:more"));

      assertFalse(setup.client.channel().isOpen());
    }
  }

  // ---------------------------------------------------------------------------
  // Interleaved requests
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("interleaved requests")
  class Interleaved {

    @Test
    @DisplayName("should route each answer to whoever asked, in order per key, across keys")
    void routesByKeyInOrder() throws Exception {
      SwitchHarness setup = playingOnLobby();
      MinecraftConnection lobby = setup.current();

      CompletableFuture<Optional<byte[]>> proxyFirst = setup.player.requestCookie("shared:key");
      setup.receive(lobby, cookieQuestion(VERSION, ProtocolState.PLAY, "shared:key"));
      setup.receive(lobby, cookieQuestion(VERSION, ProtocolState.PLAY, "lobby:own"));
      CompletableFuture<Optional<byte[]>> proxyLast = setup.player.requestCookie("shared:key");
      CompletableFuture<Optional<byte[]>> proxyOther = setup.player.requestCookie("proxy:other");
      assertEquals(5, setup.sentToClient().size());

      // The client answers in the order it was asked.
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "shared:key", new byte[] {1}));
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "shared:key", new byte[] {2}));
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "lobby:own", new byte[] {3}));
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "shared:key", new byte[] {4}));
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "proxy:other", null));

      assertArrayEquals(new byte[] {1}, proxyFirst.get().orElseThrow());
      assertEquals(List.of("shared:key=02", "lobby:own=03"), answersTo(setup, lobby));
      assertArrayEquals(new byte[] {4}, proxyLast.get().orElseThrow());
      assertEquals(Optional.empty(), proxyOther.get());
      assertEquals(0, setup.player.cookies().pendingCount());
    }

    @Test
    @DisplayName("should give the backend the first answer when it asked for the key first")
    void backendFirst() throws Exception {
      SwitchHarness setup = playingOnLobby();
      MinecraftConnection lobby = setup.current();
      setup.receive(lobby, cookieQuestion(VERSION, ProtocolState.PLAY, "shared:key"));
      CompletableFuture<Optional<byte[]>> proxy = setup.player.requestCookie("shared:key");

      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "shared:key", new byte[] {1}));

      assertFalse(proxy.isDone());
      assertEquals(List.of("shared:key=01"), answersTo(setup, lobby));
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "shared:key", new byte[] {2}));
      assertArrayEquals(new byte[] {2}, proxy.get().orElseThrow());
    }

    @Test
    @DisplayName("should drop an answer nobody asked for, rather than pass it to the backend")
    void unaskedAnswer() {
      SwitchHarness setup = playingOnLobby();

      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "lobby:visits", SESSION));

      assertTrue(setup.framesSentOn(setup.current()).isEmpty());
      assertTrue(setup.client.channel().isOpen());
    }
  }

  // ---------------------------------------------------------------------------
  // Time-outs
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("time-outs")
  class Timeouts {

    @Test
    @DisplayName("should fail with a time-out after 10 s without an answer, not before")
    void defaultTimeout() {
      SwitchHarness setup = playingOnLobby();
      EmbeddedChannel client = clientChannel(setup);
      client.freezeTime();

      CompletableFuture<Optional<byte[]>> cookie = setup.player.requestCookie("primemc:session");
      advance(client, ClientCookies.DEFAULT_TIMEOUT.minusMillis(1));
      assertFalse(cookie.isDone());
      advance(client, Duration.ofMillis(1));

      assertInstanceOf(TimeoutException.class, failureOf(cookie));
    }

    @Test
    @DisplayName("should honour the time-out of the request")
    void customTimeout() {
      SwitchHarness setup = playingOnLobby();
      EmbeddedChannel client = clientChannel(setup);
      client.freezeTime();

      CompletableFuture<Optional<byte[]>> cookie =
          setup.player.requestCookie("primemc:session", Duration.ofMillis(250));
      advance(client, Duration.ofMillis(250));

      assertInstanceOf(TimeoutException.class, failureOf(cookie));
    }

    @Test
    @DisplayName("should keep the late answer from the backend that asked for the key next")
    void lateAnswerStaysWithProxy() {
      SwitchHarness setup = playingOnLobby();
      MinecraftConnection lobby = setup.current();
      EmbeddedChannel client = clientChannel(setup);
      client.freezeTime();
      CompletableFuture<Optional<byte[]>> cookie = setup.player.requestCookie("shared:key");
      advance(client, ClientCookies.DEFAULT_TIMEOUT);
      setup.receive(lobby, cookieQuestion(VERSION, ProtocolState.PLAY, "shared:key"));

      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "shared:key", new byte[] {1}));
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "shared:key", new byte[] {2}));

      assertInstanceOf(TimeoutException.class, failureOf(cookie));
      assertEquals(List.of("shared:key=02"), answersTo(setup, lobby));
    }

    @Test
    @DisplayName("should take a time-out too long to count in nanoseconds as the longest there is")
    void enormousTimeout() throws Exception {
      SwitchHarness setup = playingOnLobby();

      CompletableFuture<Optional<byte[]>> cookie =
          setup.player.requestCookie("primemc:session", Duration.ofSeconds(Long.MAX_VALUE));
      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "primemc:session", SESSION));

      assertArrayEquals(SESSION, cookie.get().orElseThrow());
    }

    @Test
    @DisplayName("should not time out a request the client answered")
    void answeredInTime() throws Exception {
      SwitchHarness setup = playingOnLobby();
      EmbeddedChannel client = clientChannel(setup);
      client.freezeTime();
      CompletableFuture<Optional<byte[]>> cookie = setup.player.requestCookie("primemc:session");

      setup.fromClient(cookieAnswer(VERSION, ProtocolState.PLAY, "primemc:session", SESSION));
      advance(client, ClientCookies.DEFAULT_TIMEOUT);

      assertArrayEquals(SESSION, cookie.get().orElseThrow());
    }
  }

  // ---------------------------------------------------------------------------
  // Disconnect
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("disconnect")
  class Disconnect {

    @Test
    @DisplayName("should fail every request still waiting when the client disconnects")
    void failsPending() {
      SwitchHarness setup = playingOnLobby();
      CompletableFuture<Optional<byte[]>> first = setup.player.requestCookie("primemc:session");
      CompletableFuture<Optional<byte[]>> second = setup.player.requestCookie("primemc:session");
      setup.receive(setup.current(), cookieQuestion(VERSION, ProtocolState.PLAY, "lobby:visits"));

      setup.client.close();
      setup.runPendingTasks();

      assertInstanceOf(IllegalStateException.class, failureOf(first));
      assertInstanceOf(IllegalStateException.class, failureOf(second));
      assertEquals(0, setup.player.cookies().pendingCount());
    }

    @Test
    @DisplayName("should fail a request made once the client is gone")
    void failsAfterDisconnect() {
      SwitchHarness setup = playingOnLobby();
      setup.client.close();
      setup.runPendingTasks();

      CompletableFuture<Optional<byte[]>> cookie = setup.player.requestCookie("primemc:session");

      assertInstanceOf(IllegalStateException.class, failureOf(cookie));
    }

    @Test
    @DisplayName("should cancel the time-out of a request failed by the disconnect")
    void cancelsTimeout() {
      SwitchHarness setup = playingOnLobby();
      EmbeddedChannel client = clientChannel(setup);
      client.freezeTime();
      CompletableFuture<Optional<byte[]>> cookie = setup.player.requestCookie("primemc:session");

      setup.client.close();
      setup.runPendingTasks();
      advance(client, ClientCookies.DEFAULT_TIMEOUT);

      assertInstanceOf(IllegalStateException.class, failureOf(cookie));
    }
  }

  // ---------------------------------------------------------------------------
  // Stores
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("stores")
  class Stores {

    @Test
    @DisplayName("should store a cookie on a client in play")
    void storesInPlay() {
      SwitchHarness setup = playingOnLobby();

      setup.player.storeCookie("primemc:session", SESSION);

      StoreCookie stored = onlySent(setup, StoreCookie.class);
      assertEquals("primemc:session", stored.key());
      assertArrayEquals(SESSION, stored.payload());
    }

    @Test
    @DisplayName("should store a cookie on a client in configuration")
    void storesInConfiguration() {
      SwitchHarness setup = harness(VERSION, ProtocolState.CONFIGURATION);

      setup.player.storeCookie("primemc:session", SESSION);

      assertArrayEquals(SESSION, onlySent(setup, StoreCookie.class).payload());
    }

    @Test
    @DisplayName("should refuse to store in the login state, which has no such packet")
    void refusesLogin() {
      SwitchHarness setup = harness(VERSION, ProtocolState.LOGIN);

      assertThrows(
          IllegalStateException.class, () -> setup.player.storeCookie("primemc:session", SESSION));
      assertTrue(setup.sentToClient().isEmpty());
    }

    @Test
    @DisplayName(
        "should refuse a payload above 5 KiB, a key that is not an identifier, an old client")
    void refusesInvalid() {
      SwitchHarness setup = playingOnLobby();
      SwitchHarness old = harness(ProtocolVersion.MINECRAFT_1_20_4, ProtocolState.PLAY);

      assertThrows(
          IllegalArgumentException.class,
          () -> setup.player.storeCookie("primemc:session", new byte[5121]));
      assertThrows(IllegalArgumentException.class, () -> setup.player.storeCookie("A", SESSION));
      assertThrows(UnsupportedOperationException.class, () -> old.player.storeCookie("a", SESSION));
      assertTrue(setup.sentToClient().isEmpty());
    }
  }

  // ---------------------------------------------------------------------------
  // State changes
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("during a state change")
  class StateChanges {

    @Test
    @DisplayName("should hold what the proxy sends after Start Configuration until acknowledged")
    void heldUntilAcknowledged() throws Exception {
      SwitchHarness setup = playingOnLobby();
      setup.holdConnections();
      setup.player.switchServer(SURVIVAL);
      // Survival accepts the player: the proxy sends Start Configuration.
      setup.login(SURVIVAL);
      setup.sentToClient();

      CompletableFuture<Optional<byte[]>> cookie = setup.player.requestCookie("primemc:session");
      setup.player.storeCookie("primemc:session", SESSION);
      assertTrue(setup.sentToClient().isEmpty(), "the client reads configuration ids already");
      setup.client.setState(ProtocolState.CONFIGURATION); // the client acknowledged

      assertEquals(List.of(CookieRequest.class, StoreCookie.class), types(setup.sentToClient()));
      setup.fromClient(
          cookieAnswer(VERSION, ProtocolState.CONFIGURATION, "primemc:session", SESSION));
      assertArrayEquals(SESSION, cookie.get().orElseThrow());
    }
  }

  // ---------------------------------------------------------------------------
  // Between two servers
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("between two servers")
  class BetweenServers {

    @Test
    @DisplayName("should relay a request the next server makes during its login, and answer it")
    void backendLoginRequest() {
      SwitchHarness setup = waitingForSurvival();
      MinecraftConnection survival = setup.current();
      ByteBuf answer =
          cookieAnswer(VERSION, ProtocolState.LOGIN, "survival:ticket", new byte[] {9});
      byte[] answerInLogin = ByteBufUtil.getBytes(answer);
      answer.release();

      setup.receive(survival, cookieQuestion(VERSION, ProtocolState.LOGIN, "survival:ticket"));
      CookieRequest relayed = onlySent(setup, CookieRequest.class);
      boolean reading = setup.client.channel().config().isAutoRead();
      setup.fromClient(
          cookieAnswer(VERSION, ProtocolState.CONFIGURATION, "survival:ticket", new byte[] {9}));
      List<byte[]> answered = setup.framesSentOn(survival);

      assertEquals("survival:ticket", relayed.key(), "asked in the client's configuration state");
      assertTrue(reading, "the waiting client must be read for its answer");
      assertEquals(1, answered.size());
      assertArrayEquals(answerInLogin, answered.getFirst(), "answered in the server's login state");
    }

    @Test
    @DisplayName(
        "should hold what else the waiting client sends for the next server's configuration")
    void holdsFramesForNextServer() {
      SwitchHarness setup = waitingForSurvival();
      MinecraftConnection survival = setup.current();
      setup.receive(survival, cookieQuestion(VERSION, ProtocolState.LOGIN, "survival:ticket"));
      setup.sentToClient();
      ByteBuf opaque = opaqueFrame(0x7F);
      byte[] opaqueBytes = ByteBufUtil.getBytes(opaque);

      setup.fromClient(opaque);
      assertTrue(setup.framesSentOn(survival).isEmpty(), "held while the server logs in");
      setup.player.serverSwitch().loggedIn(survival);
      setup.runPendingTasks();

      List<byte[]> sent = setup.framesSentOn(survival);
      assertArrayEquals(opaqueBytes, sent.getLast(), "after Login Acknowledged");
      assertInstanceOf(ClientConfigSessionHandler.class, setup.client.sessionHandler());
    }

    /** A player waiting between the lobby and survival, survival's login under way. */
    private SwitchHarness waitingForSurvival() {
      SwitchHarness setup = playingOnLobby();
      setup.player.beginSwitch(SURVIVAL);
      setup.client.setState(ProtocolState.CONFIGURATION);
      setup.client.setAutoRead(false);
      setup.client.setSessionHandler(new SwitchWaitSessionHandler(setup.player));
      MinecraftConnection survival = setup.backend(ProtocolState.LOGIN);
      setup.player.serverSwitch().connected(new BackendConnection(survival, SURVIVAL));
      survival.setSessionHandler(
          new BackendLoginSessionHandler(setup.player, survival, SURVIVAL.address(), new byte[0]));
      setup.sentToClient();
      setup.framesSentOn(survival); // its Handshake and Login Start
      return setup;
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private SwitchHarness harness(ProtocolVersion version, ProtocolState clientState) {
    SwitchHarness setup = new SwitchHarness(version, List.of(), clientState);
    harnesses.add(setup);
    return setup;
  }

  /** A player playing on the lobby, both sides in PLAY. */
  private SwitchHarness playingOnLobby() {
    SwitchHarness setup = harness(VERSION, ProtocolState.PLAY);
    MinecraftConnection lobby = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(lobby, LOBBY));
    setup.player.setCurrentServerName(LOBBY.name());
    lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
    setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
    return setup;
  }

  /** Moves the player to a new backend in PLAY, without a switch. */
  private static MinecraftConnection playOn(SwitchHarness setup, ServerInfo to) {
    MinecraftConnection backend = setup.backend(ProtocolState.PLAY);
    setup.player.setBackendConnection(new BackendConnection(backend, to));
    backend.setSessionHandler(new BackendPlaySessionHandler(setup.player, backend));
    return backend;
  }

  /** Drains what the client received, which must be exactly one {@code type} packet. */
  private static <T extends Packet> T onlySent(SwitchHarness setup, Class<T> type) {
    List<SwitchHarness.Sent> sent = setup.sentToClient();
    assertEquals(1, sent.size(), () -> "sent " + types(sent));
    return sent.getFirst().as(type);
  }

  /** The cookie answers a backend received, as {@code key=hex}. */
  private static List<String> answersTo(SwitchHarness setup, MinecraftConnection backend) {
    List<String> answers = new ArrayList<>();
    for (byte[] frame : setup.framesSentOn(backend)) {
      CookieResponse response =
          SwitchHarness.decodeFrame(
              frame,
              VERSION,
              backend.encoder().state(),
              PacketDirection.SERVERBOUND,
              CookieResponse.class);
      byte[] payload = response.payload();
      answers.add(response.key() + "=" + (payload == null ? "-" : ByteBufUtil.hexDump(payload)));
    }
    return answers;
  }

  private static EmbeddedChannel clientChannel(SwitchHarness setup) {
    return (EmbeddedChannel) setup.client.channel();
  }

  private static void advance(EmbeddedChannel channel, Duration duration) {
    channel.advanceTimeBy(duration.toNanos(), TimeUnit.NANOSECONDS);
    channel.runScheduledPendingTasks();
  }

  private static Throwable failureOf(CompletableFuture<?> future) {
    assertTrue(future.isCompletedExceptionally(), "the request must have failed");
    ExecutionException failed = assertThrows(ExecutionException.class, future::get);
    Throwable cause = failed.getCause();
    assertNotNull(cause);
    return cause;
  }
}
