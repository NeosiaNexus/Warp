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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.login.EncryptionRequest;
import dev.warp.proxy.auth.AuthenticationException;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.config.LimitsConfig;
import dev.warp.proxy.config.ProxyProtocolConfig;
import dev.warp.proxy.i18n.PlayerMessages;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.limit.Refusal;
import dev.warp.proxy.network.CidrRanges;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.crypto.Cipher;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.NetUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Drives connections through the pipeline {@link ServerChannelInitializer} builds, with the limits
 * of {@link ClientLimits} on, and reads what a client would.
 */
@DisplayName("connection limits")
class ConnectionLimitsTest {

  private static final InetSocketAddress CLIENT = socket("203.0.113.7", 40000);
  private static final InetSocketAddress LOAD_BALANCER = socket("10.0.0.1", 34567);
  private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

  /** No interval elapses during a test: once spent, a budget stays spent. */
  private static final Duration HOUR = Duration.ofHours(1);

  private final LogCapture loginLog = new LogCapture(LoginSessionHandler.class);
  private final LogCapture limitsLog = new LogCapture(ClientLimits.class);
  private final LogCapture proxyProtocolLog = new LogCapture(ProxyProtocolHandler.class);

  @AfterEach
  void closeLogs() {
    loginLog.close();
    limitsLog.close();
    proxyProtocolLog.close();
  }

  // ---------------------------------------------------------------------------
  // Connections
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("connections")
  class Connections {

    @Test
    @DisplayName("should reset the connections of an address over its rate before any handler")
    void overRate() {
      ServerChannelInitializer initializer = initializer(limits(LimitsConfig.DEFAULTS));
      for (int i = 0; i < 10; i++) {
        EmbeddedChannel accepted = connect(CLIENT, initializer);
        assertNotNull(accepted.pipeline().get(ServerChannelInitializer.CONNECTION_HANDLER));
      }

      EmbeddedChannel refused = connect(CLIENT, initializer);

      assertFalse(refused.isActive());
      assertNull(refused.pipeline().get(ServerChannelInitializer.CONNECTION_HANDLER));
      assertTrue(connect(socket("203.0.113.8", 40000), initializer).isActive());
    }

    @Test
    @DisplayName("should let a crowd connect and log in from loopback, exempt by default")
    void loopbackCrowd() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);
      ServerChannelInitializer initializer = initializer(limits);

      for (int i = 0; i < 50; i++) {
        EmbeddedChannel channel = connect(socket("127.0.0.1", 50000 + i), initializer);
        channel.writeInbound(loginHandshake(), loginStart("Bot" + i));
        assertEquals(0x02, packetId(channel), "Login Success for bot " + i);
        channel.finishAndReleaseAll();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // PROXY protocol
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("behind the PROXY protocol")
  class BehindProxyProtocol {

    private final ProxyProtocolConfig proxyProtocol =
        new ProxyProtocolConfig(true, CidrRanges.parse(List.of("10.0.0.0/24")));

    @Test
    @DisplayName("should limit the client address of the header, not the load balancer's")
    void clientAddress() {
      ClientLimits limits = new ClientLimits(LimitsConfig.DEFAULTS, proxyProtocol.trustedProxies());
      ServerChannelInitializer initializer = initializer(limits, proxyProtocol);

      // Many clients through one load balancer: none is limited by the others.
      for (int i = 0; i < 50; i++) {
        EmbeddedChannel channel = connect(LOAD_BALANCER, initializer);
        channel.writeInbound(
            ascii("PROXY TCP4 198.51.100." + (i + 1) + " 10.0.0.1 40000 25577\r\n"));
        assertTrue(channel.isActive(), "client " + i);
      }
      // One client over its rate, through the same load balancer.
      for (int i = 0; i < 10; i++) {
        EmbeddedChannel channel = connect(LOAD_BALANCER, initializer);
        channel.writeInbound(ascii("PROXY TCP4 203.0.113.7 10.0.0.1 40000 25577\r\n"));
        assertTrue(channel.isActive());
      }
      EmbeddedChannel refused = connect(LOAD_BALANCER, initializer);
      refused.writeInbound(
          Unpooled.wrappedBuffer(
              ascii("PROXY TCP4 203.0.113.7 10.0.0.1 40000 25577\r\n"),
              loginHandshake(),
              loginStart("Steve")));

      assertFalse(refused.isActive());
      assertNull(refused.readOutbound(), "nothing read from the refused connection");
      assertTrue(warningsAndInfo(proxyProtocolLog).isEmpty(), "counted, not logged");
    }

    @Test
    @DisplayName("should never limit the load balancer's own health checks")
    void healthChecks() {
      ClientLimits limits = new ClientLimits(LimitsConfig.DEFAULTS, proxyProtocol.trustedProxies());
      ServerChannelInitializer initializer = initializer(limits, proxyProtocol);

      for (int i = 0; i < 100; i++) {
        EmbeddedChannel channel = connect(LOAD_BALANCER, initializer);
        channel.writeInbound(ascii("PROXY UNKNOWN\r\n"), statusHandshake(), statusRequest());
        assertEquals(0x00, packetId(channel), "status response " + i);
        channel.finishAndReleaseAll();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Server list pings
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("server list pings")
  class StatusPings {

    @Test
    @DisplayName("should close a ping over the limit without an answer, and let the address log in")
    void apartFromLogins() {
      ClientLimits limits = limits(withRates(LimitsConfig.Rate.UNLIMITED, rate(3), rate(1)));
      ServerChannelInitializer initializer = initializer(limits);
      for (int i = 0; i < 3; i++) {
        EmbeddedChannel channel = connect(CLIENT, initializer);
        channel.writeInbound(statusHandshake(), statusRequest());
        assertEquals(0x00, packetId(channel), "status response " + i);
      }

      EmbeddedChannel refused = connect(CLIENT, initializer);
      refused.writeInbound(statusHandshake(), statusRequest());

      assertFalse(refused.isActive());
      assertNull(refused.readOutbound(), "no answer");
      EmbeddedChannel login = connect(CLIENT, initializer);
      login.writeInbound(loginHandshake(), loginStart("Steve"));
      assertEquals(0x02, packetId(login), "Login Success");
    }
  }

  // ---------------------------------------------------------------------------
  // Logins
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("logins")
  class Logins {

    @Test
    @DisplayName("should let a shared address log ten in at once, but only two during an attack")
    void tooFast() {
      ClientLimits limits = limits(LimitsConfig.DEFAULTS);
      ServerChannelInitializer initializer = initializer(limits);
      for (int i = 0; i < 10; i++) {
        EmbeddedChannel channel = connect(CLIENT, initializer);
        channel.writeInbound(loginHandshake(), loginStart("Player" + i));
        assertEquals(0x02, packetId(channel), "Login Success " + i);
        channel.finishAndReleaseAll();
      }
      // The anti-bot detects an attack: another address of the NAT now gets the strict budget.
      limits.attackMode(() -> true);
      InetSocketAddress other = socket("203.0.113.8", 40000);
      for (int i = 0; i < 2; i++) {
        EmbeddedChannel channel = connect(other, initializer);
        channel.writeInbound(loginHandshake(), loginStart("Steve"));
        assertEquals(0x02, packetId(channel), "Login Success under attack " + i);
        channel.finishAndReleaseAll();
      }

      EmbeddedChannel refused = connect(other, initializer);
      refused.writeInbound(loginHandshake(), loginStart("Steve"));

      assertEquals(
          "You are logging in too fast. Please try again in 3 s.", disconnectReason(refused));
      assertFalse(refused.isActive());
      assertTrue(
          warningsAndInfo(loginLog).stream()
              .noneMatch(line -> line.contains("Steve") && line.contains("Disconnecting")),
          "a refusal is counted, not logged");
    }

    @Test
    @DisplayName("should refuse a login while its address has the most in progress, until one ends")
    void inProgress() {
      ClientLimits limits = limits(withLogins(LimitsConfig.Rate.UNLIMITED, 2, 0, HOUR));
      ServerChannelInitializer initializer =
          onlineInitializer(limits, mock(MojangSessionService.class));
      EmbeddedChannel first = startOnlineLogin(initializer).channel();
      startOnlineLogin(initializer);

      EmbeddedChannel refused = connect(CLIENT, initializer);
      refused.writeInbound(loginHandshake(), loginStart("Steve"));

      assertEquals(english(LoginSessionHandler.TOO_MANY_LOGINS_REASON), disconnectReason(refused));
      var _ = first.close();
      startOnlineLogin(initializer);
    }

    @Test
    @DisplayName("should close a login that takes longer than the timeout, and free its place")
    void timeout() {
      ClientLimits limits =
          limits(withLogins(LimitsConfig.Rate.UNLIMITED, 1, 0, Duration.ofSeconds(10)));
      ServerChannelInitializer initializer =
          onlineInitializer(limits, mock(MojangSessionService.class));
      EmbeddedChannel stalled = startOnlineLogin(initializer).channel();

      // Under the 30 s read timeout, which would close the connection first.
      stalled.advanceTimeBy(9, TimeUnit.SECONDS);
      stalled.runPendingTasks();
      assertTrue(stalled.isActive(), "still within the timeout");
      stalled.advanceTimeBy(1, TimeUnit.SECONDS);
      stalled.runPendingTasks();

      assertEquals(english(LoginSessionHandler.TIMED_OUT_REASON), disconnectReason(stalled));
      assertFalse(stalled.isActive());
      startOnlineLogin(initializer);
      limits.report();
      assertEquals(
          List.of("Refused before login over the last 60 s: logins timed out: 1"), info(limitsLog));
    }

    @Test
    @DisplayName("should not time out a player once logged in")
    void noTimeoutOnceLoggedIn() {
      ClientLimits limits =
          limits(withLogins(LimitsConfig.Rate.UNLIMITED, 1, 0, Duration.ofSeconds(10)));
      ServerChannelInitializer initializer = initializer(limits);
      EmbeddedChannel channel = connect(CLIENT, initializer);
      channel.writeInbound(loginHandshake(), loginStart("Steve"));
      assertEquals(0x02, packetId(channel), "Login Success");

      channel.advanceTimeBy(20, TimeUnit.SECONDS);
      channel.runPendingTasks();

      assertNull(channel.readOutbound(), "no disconnect");
      // Logged in: the place is free for another login of the address.
      EmbeddedChannel next = connect(CLIENT, initializer);
      next.writeInbound(loginHandshake(), loginStart("Alex"));
      assertEquals(0x02, packetId(next));
    }
  }

  // ---------------------------------------------------------------------------
  // Authentications
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("authentications in flight")
  class Authentications {

    @Test
    @DisplayName("should queue a login while the proxy authenticates its most, then say it is busy")
    void busyAtLoginStart() {
      ClientLimits limits = limits(withLogins(LimitsConfig.Rate.UNLIMITED, 0, 1, HOUR));
      ServerChannelInitializer initializer =
          onlineInitializer(limits, mock(MojangSessionService.class));
      assertNotNull(limits.lane().tryAcquire());

      EmbeddedChannel channel = connect(CLIENT, initializer);
      channel.writeInbound(loginHandshake(), loginStart("Steve"));
      assertNull(channel.readOutbound(), "waiting in line: no encryption request yet");
      assertEquals(1, limits.lane().waiting());
      channel.advanceTimeBy(10, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();

      assertEquals(
          english(LoginSessionHandler.busyReason(Duration.ofSeconds(10))),
          disconnectReason(channel));
      assertEquals(0, limits.lane().waiting());
      assertEquals(1, limits.refusals(Refusal.AUTHENTICATION_BUSY));
    }

    @Test
    @DisplayName("should send the encryption request once a slot frees for a queued login")
    void grantedFromLine() {
      ClientLimits limits = limits(withLogins(LimitsConfig.Rate.UNLIMITED, 0, 1, HOUR));
      ServerChannelInitializer initializer =
          onlineInitializer(limits, mock(MojangSessionService.class));
      var taken = limits.lane().tryAcquire();
      assertNotNull(taken);
      EmbeddedChannel channel = connect(CLIENT, initializer);
      channel.writeInbound(loginHandshake(), loginStart("Steve"));

      taken.release();
      channel.runPendingTasks();

      assertEquals(0x01, packetId(channel), "Encryption Request");
      assertTrue(limits.lane().mustWait(), "the slot is held for this login");
    }

    @Test
    @DisplayName("should hold quietly an encryption response that finds the proxy at its most")
    void fullAtEncryptionResponse() {
      ClientLimits limits = limits(withLogins(LimitsConfig.Rate.UNLIMITED, 0, 1, HOUR));
      ServerChannelInitializer initializer =
          onlineInitializer(limits, mock(MojangSessionService.class));
      EmbeddedChannel channel = startOnlineLogin(initializer).channel();
      assertNotNull(limits.lane().tryAcquire(), "another player takes the only slot");

      // Not even decryptable: no RSA work before a slot, and it waits silently for one.
      channel.writeInbound(encryptionResponse(new byte[128], new byte[128]));
      assertTrue(channel.isActive(), "waiting in line");
      channel.advanceTimeBy(10, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();

      assertFalse(channel.isActive());
      assertNull(
          channel.readOutbound(), "the client expects encrypted packets: nothing to tell it");
      assertTrue(
          warningsAndInfo(loginLog).isEmpty(), () -> String.valueOf(warningsAndInfo(loginLog)));
    }

    @Test
    @DisplayName("should hold an authentication only while the session server is asked")
    void slotDuringRequest() throws Exception {
      ClientLimits limits = limits(withLogins(LimitsConfig.Rate.UNLIMITED, 0, 1, HOUR));
      MojangSessionService sessionService = mock(MojangSessionService.class);
      CountDownLatch requested = new CountDownLatch(1);
      CountDownLatch answer = new CountDownLatch(1);
      AtomicReference<@Nullable Thread> requesting = new AtomicReference<>();
      when(sessionService.hasJoined(eq("Steve"), anyString()))
          .thenAnswer(
              invocation -> {
                requesting.set(Thread.currentThread());
                requested.countDown();
                if (!answer.await(10, TimeUnit.SECONDS)) {
                  throw new AssertionError("the test never let the session server answer");
                }
                throw new AuthenticationException("not this time");
              });
      ServerChannelInitializer initializer = onlineInitializer(limits, sessionService);
      OnlineLogin login = startOnlineLogin(initializer);
      EncryptionRequest request = login.encryptionRequest();

      login
          .channel()
          .writeInbound(
              encryptionResponse(
                  rsaEncrypt(request, new byte[16]), rsaEncrypt(request, request.verifyToken())));
      assertTrue(requested.await(10, TimeUnit.SECONDS), "the session server was asked");

      assertTrue(limits.lane().mustWait(), "the request in flight holds the only slot");
      answer.countDown();
      Thread thread = requesting.get();
      assertNotNull(thread);
      thread.join(10_000);
      assertTrue(!limits.lane().mustWait(), "released when the session server answered");
    }

    @Test
    @DisplayName("should count forged encryption responses instead of logging each one")
    void forgedResponses() {
      ClientLimits limits = limits(withLogins(LimitsConfig.Rate.UNLIMITED, 0, 1, HOUR));
      ServerChannelInitializer initializer =
          onlineInitializer(limits, mock(MojangSessionService.class));

      for (int i = 0; i < 20; i++) {
        EmbeddedChannel channel = startOnlineLogin(initializer).channel();
        // Bytes no RSA key decrypts: what a fake-authentication flood sends.
        channel.writeInbound(encryptionResponse(new byte[128], new byte[128]));
        assertFalse(channel.isActive());
      }

      assertEquals(20, limits.refusals(Refusal.AUTHENTICATION_FAILED));
      assertTrue(
          warningsAndInfo(loginLog).isEmpty(), () -> String.valueOf(warningsAndInfo(loginLog)));
      assertTrue(!limits.lane().mustWait(), "every slot given back");
    }

    @Test
    @DisplayName("should count players the session server denies instead of logging each one")
    void deniedByTheSessionServer() throws Exception {
      ClientLimits limits = limits(withLogins(LimitsConfig.Rate.UNLIMITED, 0, 1, HOUR));
      MojangSessionService sessionService = mock(MojangSessionService.class);
      CountDownLatch denied = new CountDownLatch(3);
      when(sessionService.hasJoined(eq("Steve"), anyString()))
          .thenAnswer(
              invocation -> {
                denied.countDown();
                throw AuthenticationException.notJoined("did not join");
              });
      ServerChannelInitializer initializer = onlineInitializer(limits, sessionService);

      List<EmbeddedChannel> channels = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        OnlineLogin login = startOnlineLogin(initializer);
        EncryptionRequest request = login.encryptionRequest();
        login
            .channel()
            .writeInbound(
                encryptionResponse(
                    rsaEncrypt(request, new byte[16]), rsaEncrypt(request, request.verifyToken())));
        channels.add(login.channel());
        // One at a time: the only slot comes back once the session server answered.
        assertTrue(waitFor(() -> !limits.lane().mustWait()), "the slot came back");
      }
      assertTrue(denied.await(10, TimeUnit.SECONDS));
      channels.forEach(EmbeddedChannel::runPendingTasks);

      assertEquals(3, limits.refusals(Refusal.AUTHENTICATION_FAILED));
      assertTrue(
          warningsAndInfo(loginLog).isEmpty(), () -> String.valueOf(warningsAndInfo(loginLog)));
    }
  }

  /** Waits up to 10 s for a condition another thread makes true. */
  private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - deadline > 0) {
        return false;
      }
      Thread.sleep(5);
    }
    return true;
  }

  // ---------------------------------------------------------------------------
  // Report
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("should log the refusals in one line per report, and nothing when there were none")
  void report() {
    ClientLimits limits = limits(withRates(rate(1), rate(1), rate(1)));
    ServerChannelInitializer initializer = initializer(limits);
    connect(CLIENT, initializer);
    for (int i = 0; i < 41; i++) {
      connect(CLIENT, initializer);
    }

    limits.report();
    limits.report();

    assertEquals(
        List.of(
            "Refused before login over the last 60 s: connections over their address's rate: 41"),
        info(limitsLog));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static ClientLimits limits(LimitsConfig config) {
    return new ClientLimits(config, CidrRanges.none());
  }

  private static ServerChannelInitializer initializer(ClientLimits limits) {
    return new ServerChannelInitializer(
        TestLoginContexts.limited(false, new MojangSessionService(), limits));
  }

  private static ServerChannelInitializer initializer(
      ClientLimits limits, ProxyProtocolConfig proxyProtocol) {
    return new ServerChannelInitializer(
        TestLoginContexts.limited(false, new MojangSessionService(), limits), proxyProtocol);
  }

  private static ServerChannelInitializer onlineInitializer(
      ClientLimits limits, MojangSessionService sessionService) {
    return new ServerChannelInitializer(TestLoginContexts.limited(true, sessionService, limits));
  }

  private static EmbeddedChannel connect(
      InetSocketAddress peer, ServerChannelInitializer initializer) {
    EmbeddedChannel channel = new ProxyProtocolTest.PeerChannel(peer);
    // Scheduled tasks, the login timeout among them, run only when a test advances the time.
    channel.freezeTime();
    channel.pipeline().addLast(initializer);
    return channel;
  }

  /** An online login waiting for the client's encryption response. */
  private record OnlineLogin(EmbeddedChannel channel, EncryptionRequest encryptionRequest) {}

  /** Starts an online login of {@link #CLIENT}, and checks that it is asked for encryption. */
  private static OnlineLogin startOnlineLogin(ServerChannelInitializer initializer) {
    EmbeddedChannel channel = connect(CLIENT, initializer);
    channel.writeInbound(loginHandshake(), loginStart("Steve"));
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame, "expected an Encryption Request");
    try {
      VarInt.read(frame);
      assertEquals(0x01, VarInt.read(frame), "Encryption Request");
      return new OnlineLogin(channel, EncryptionRequest.CODEC.decode(frame, VERSION));
    } finally {
      frame.release();
    }
  }

  private static LimitsConfig.Rate rate(int burst) {
    return new LimitsConfig.Rate(burst, HOUR);
  }

  private static LimitsConfig withRates(
      LimitsConfig.Rate connections, LimitsConfig.Rate statusPings, LimitsConfig.Rate logins) {
    LimitsConfig d = LimitsConfig.DEFAULTS;
    return new LimitsConfig(
        true,
        CidrRanges.none(),
        connections,
        statusPings,
        logins,
        0,
        0,
        Duration.ZERO,
        d.reportInterval(),
        d.maxPendingConnections(),
        d.handshakeTimeout(),
        d.statusTimeout(),
        d.authenticationQueue(),
        d.authenticationQueueWait(),
        d.loginsUnderAttack(),
        d.maxConcurrentLoginsPerAddressUnderAttack(),
        d.pendingEvictionAfter());
  }

  private static LimitsConfig withLogins(
      LimitsConfig.Rate logins, int perAddress, int authentications, Duration timeout) {
    return new LimitsConfig(
        true,
        CidrRanges.none(),
        LimitsConfig.Rate.UNLIMITED,
        LimitsConfig.Rate.UNLIMITED,
        logins,
        perAddress,
        authentications,
        timeout,
        LimitsConfig.DEFAULTS.reportInterval(),
        LimitsConfig.DEFAULTS.maxPendingConnections(),
        LimitsConfig.DEFAULTS.handshakeTimeout(),
        LimitsConfig.DEFAULTS.statusTimeout(),
        LimitsConfig.DEFAULTS.authenticationQueue(),
        LimitsConfig.DEFAULTS.authenticationQueueWait(),
        LimitsConfig.DEFAULTS.loginsUnderAttack(),
        LimitsConfig.DEFAULTS.maxConcurrentLoginsPerAddressUnderAttack(),
        LimitsConfig.DEFAULTS.pendingEvictionAfter());
  }

  private static List<String> info(LogCapture log) {
    return log.events().stream()
        .filter(event -> Level.INFO.equals(event.getLevel()))
        .map(event -> event.getMessage().getFormattedMessage())
        .toList();
  }

  private static List<String> warningsAndInfo(LogCapture log) {
    return log.events().stream()
        .filter(event -> event.getLevel().isMoreSpecificThan(Level.INFO))
        .map(event -> event.getMessage().getFormattedMessage())
        .toList();
  }

  private static int packetId(EmbeddedChannel channel) {
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame, "expected a packet");
    try {
      VarInt.read(frame);
      return VarInt.read(frame);
    } finally {
      frame.release();
    }
  }

  private static ByteBuf statusHandshake() {
    return handshake(1);
  }

  private static ByteBuf loginHandshake() {
    return handshake(2);
  }

  private static ByteBuf handshake(int nextState) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    VarInt.write(body, VERSION.protocol());
    McString.write(body, "play.warp.dev");
    body.writeShort(25565);
    VarInt.write(body, nextState);
    return frame(body);
  }

  private static ByteBuf statusRequest() {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    return frame(body);
  }

  /** A 1.21.4 Login Start: the name, then the UUID the client claims. */
  private static ByteBuf loginStart(String name) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    body.writeLong(0).writeLong(0);
    return frame(body);
  }

  /** A 1.21.4 Encryption Response: the encrypted shared secret, then the encrypted token. */
  private static ByteBuf encryptionResponse(byte[] encryptedSecret, byte[] encryptedToken) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x01);
    VarInt.write(body, encryptedSecret.length);
    body.writeBytes(encryptedSecret);
    VarInt.write(body, encryptedToken.length);
    body.writeBytes(encryptedToken);
    return frame(body);
  }

  /** Encrypts as a vanilla client does: PKCS#1 v1.5, which the protocol mandates. Shared. */
  static byte[] rsaEncrypt(EncryptionRequest request, byte[] data) throws GeneralSecurityException {
    PublicKey proxyKey =
        KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(request.publicKey()));
    Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
    cipher.init(Cipher.ENCRYPT_MODE, proxyKey);
    return cipher.doFinal(data);
  }

  private static ByteBuf frame(ByteBuf body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes());
    frame.writeBytes(body);
    body.release();
    return frame;
  }

  private static ByteBuf ascii(String text) {
    return Unpooled.wrappedBuffer(text.getBytes(StandardCharsets.US_ASCII));
  }

  private static InetSocketAddress socket(String literal, int port) {
    try {
      return new InetSocketAddress(
          InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal)), port);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * What a player is told, as English plain text: vanilla messages too, as the client shows them.
   */
  private static String english(Component reason) {
    return PlayerMessages.bundled().plainEnglish(reason);
  }

  /** Reads a Login Disconnect and returns its reason as English plain text. */
  private static String disconnectReason(EmbeddedChannel channel) {
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame, "expected a Login Disconnect");
    try {
      VarInt.read(frame);
      assertEquals(0x00, VarInt.read(frame), "Login Disconnect");
      String json = McString.read(frame);
      return english(GsonComponentSerializer.gson().deserialize(json));
    } finally {
      frame.release();
    }
  }
}
