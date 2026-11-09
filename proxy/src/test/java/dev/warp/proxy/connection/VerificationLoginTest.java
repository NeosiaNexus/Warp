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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.antibot.AttackLevel;
import dev.warp.api.antibot.Identity;
import dev.warp.api.antibot.Verification;
import dev.warp.api.antibot.VerificationFailure;
import dev.warp.api.antibot.VerificationMethod;
import dev.warp.api.event.Subscribe;
import dev.warp.api.event.antibot.VerificationFailedEvent;
import dev.warp.api.event.player.PlayerChooseInitialServerEvent;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.codec.McString;
import dev.warp.protocol.codec.McUuid;
import dev.warp.protocol.codec.VarInt;
import dev.warp.protocol.packet.Packet;
import dev.warp.protocol.packet.PacketCodec;
import dev.warp.protocol.packet.PacketDirection;
import dev.warp.protocol.packet.PacketRegistry;
import dev.warp.protocol.packet.StateRegistry;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.config.ConfigDisconnect;
import dev.warp.protocol.packet.config.ConfigPing;
import dev.warp.protocol.packet.config.ConfigPluginMessage;
import dev.warp.protocol.packet.config.ConfigPong;
import dev.warp.protocol.packet.cookie.CookieRequest;
import dev.warp.protocol.packet.cookie.CookieResponse;
import dev.warp.protocol.packet.cookie.StoreCookie;
import dev.warp.protocol.packet.login.EncryptionRequest;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.protocol.packet.login.SetCompression;
import dev.warp.protocol.packet.play.KeepAlive;
import dev.warp.protocol.packet.play.PlayDisconnect;
import dev.warp.protocol.packet.play.Transfer;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.antibot.Probe;
import dev.warp.proxy.antibot.VerificationTokens;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.limit.ClientLimits;
import dev.warp.proxy.limit.Refusal;
import dev.warp.proxy.network.CidrRanges;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The verification of unknown players through Warp's pipeline, as a client sees it: the
 * configuration probe, the pre-join probe, verification before authentication with the transfer
 * back and its token, and the fair queue.
 */
@DisplayName("verification at login")
class VerificationLoginTest {

  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;
  private static final ProtocolVersion FIRST_CONFIGURATION = ProtocolVersion.MINECRAFT_1_20_2;
  private static final ProtocolVersion LEGACY = ProtocolVersion.MINECRAFT_1_16_4;
  private static final String HOST = "play.warp.dev";
  private static final int PORT = 25565;
  private static final ClientInformation SETTINGS =
      new ClientInformation("fr_fr", (byte) 10, 0, true, (byte) 0x7f, 1, false, true, 0);

  @TempDir private Path directory;

  private final ClientLimits limits = ClientLimits.unlimited();
  private final WarpEventBus events = new WarpEventBus(Runnable::run);
  private final AtomicLong millis = new AtomicLong(1_800_000_000_000L);
  private final AtomicLong nanos = new AtomicLong(TimeUnit.HOURS.toNanos(1));
  private final List<VerificationFailedEvent> failures = new CopyOnWriteArrayList<>();
  private @Nullable AntiBotService antiBot;

  /** Another proxy of the fleet, which the client connects to instead when set. */
  private @Nullable AntiBotService peer;

  private UnaryOperator<@Nullable String> env = _ -> null;
  private boolean onlineMode;
  private AntiBotConfig.@Nullable Verification verification;
  private int compressionThreshold = -1;

  /** Whether frames carry the data-length prefix of compressed framing, from Set Compression on. */
  private boolean compressed;

  VerificationLoginTest() {}

  @AfterEach
  void close() {
    if (antiBot != null) {
      bots().close();
    }
    if (peer != null) {
      peer.close();
    }
  }

  // ---------------------------------------------------------------------------
  // The configuration probe (1.20.2+)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("the configuration probe")
  class ConfigurationProbe {

    @Test
    @DisplayName(
        "should ask a ping, a keep-alive and a cookie, and trust a client that answers as vanilla"
            + " does")
    void vanillaPasses() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", MODERN);
      LoginSuccess success = nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      assertEquals("Steve", success.username());
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);

      Asked asked = asked(channel, MODERN);
      answerAsVanilla(channel, asked, MODERN);

      Identity steve = new Identity.NameAtAddress("Steve", address("203.0.113.9"));
      assertTrue(waitFor(() -> bots().verified().check(steve).isPresent()), "trusted");
      Optional<Verification> verification = bots().verified().check(steve);
      assertEquals(VerificationMethod.PROBE, verification.orElseThrow().method());
      assertEquals(1, bots().status().verifications().passed());
      assertEquals(0, bots().status().verifications().escalated());
      // Verified: the player goes on to its first server (which this test has none of).
      assertNotRefused();
    }

    @Test
    @DisplayName("should keep the brand the client sent while probed, for its first server")
    void brandHeldForServer() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      // Plugins hold the choice of its first server: the client waits in the configuration phase.
      new PluginEventBus(events, "test", null, _ -> {})
          .subscribeAsync(
              PlayerChooseInitialServerEvent.class,
              Subscribe.NORMAL,
              30,
              _ -> new CompletableFuture<>());
      EmbeddedChannel channel = login("Steve", "203.0.113.9", MODERN);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);
      Asked asked = asked(channel, MODERN);
      ConfigPluginMessage brand = brand("fabric");
      byte[] brandFrame =
          ByteBufUtil.getBytes(frame(body(ProtocolState.CONFIGURATION, brand, MODERN)));

      answerAsVanilla(channel, asked, MODERN, brand);

      MinecraftConnection connection = channel.pipeline().get(MinecraftConnection.class);
      SwitchWaitSessionHandler waiting =
          assertInstanceOf(
              SwitchWaitSessionHandler.class,
              connection.sessionHandler(),
              "waiting for its first server");
      List<ByteBuf> held = waiting.heldFrames();
      assertEquals(1, held.size(), "the brand, and nothing the probe read");
      assertArrayEquals(brandFrame, ByteBufUtil.getBytes(held.get(0)));
      assertNotRefused();
    }

    @Test
    @DisplayName("should not probe a player it already trusts")
    void knownSkipsProbe() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      bots()
          .trustByCommand(new Identity.NameAtAddress("Alex", address("203.0.113.9")))
          .get(5, TimeUnit.SECONDS);
      EmbeddedChannel channel = login("Alex", "203.0.113.200", MODERN);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);

      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);

      assertNotAsked(channel);
      assertEquals(0, bots().status().verifications().running());
    }

    @Test
    @DisplayName("should ask no cookie of a 1.20.2 client, and pass it")
    void firstConfigurationVersion() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", FIRST_CONFIGURATION);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, FIRST_CONFIGURATION);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), FIRST_CONFIGURATION);

      Asked asked = asked(channel, FIRST_CONFIGURATION);
      assertNull(asked.cookie);
      answerAsVanilla(channel, asked, FIRST_CONFIGURATION);

      assertTrue(
          waitFor(
              () ->
                  bots()
                      .verified()
                      .check(new Identity.NameAtAddress("Steve", address("203.0.113.9")))
                      .isPresent()));
    }

    @Test
    @DisplayName(
        "should disconnect a client that echoes a wrong ping id, count it and tell plugins")
    void wrongEcho() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", MODERN);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);
      Asked asked = asked(channel, MODERN);

      send(channel, ProtocolState.CONFIGURATION, SETTINGS, MODERN);
      send(channel, ProtocolState.CONFIGURATION, new ConfigPong(asked.ping + 1), MODERN);

      nextPacket(channel, ProtocolState.CONFIGURATION, ConfigDisconnect.class, MODERN);
      assertFalse(channel.isOpen());
      assertEquals(1, limits.refusals(Refusal.VERIFICATION_FAILED));
      assertEquals(
          1L, bots().status().verifications().failed().get(VerificationFailure.WRONG_ECHO));
      assertEquals(1, failures.size());
      assertEquals("Steve", failures.getFirst().attempt().name());
      assertEquals(VerificationFailure.WRONG_ECHO, failures.getFirst().reason());
      assertFalse(
          bots()
              .verified()
              .check(new Identity.NameAtAddress("Steve", address("203.0.113.9")))
              .isPresent());
    }

    @Test
    @DisplayName("should disconnect a client that acknowledges a configuration never finished")
    void illegalPacket() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", MODERN);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);
      asked(channel, MODERN);

      send(
          channel,
          ProtocolState.CONFIGURATION,
          new dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration(),
          MODERN);

      nextPacket(channel, ProtocolState.CONFIGURATION, ConfigDisconnect.class, MODERN);
      assertEquals(
          1L, bots().status().verifications().failed().get(VerificationFailure.ILLEGAL_PACKET));
    }

    @Test
    @DisplayName("should time out a client that answers nothing, without a strike")
    void timesOut() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", MODERN);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);
      asked(channel, MODERN);

      advance(channel, AntiBotConfig.Verification.DEFAULTS.probeTimeout());

      nextPacket(channel, ProtocolState.CONFIGURATION, ConfigDisconnect.class, MODERN);
      assertEquals(1, limits.refusals(Refusal.VERIFICATION_TIMEOUT));
      assertEquals(0, limits.refusals(Refusal.VERIFICATION_FAILED));
      assertEquals(VerificationFailure.TIMED_OUT, failures.getFirst().reason());
    }

    @Test
    @DisplayName(
        "should let a client that answers no cookie in after a short grace, flagged, not after the"
            + " whole timeout")
    void noCookieWaitsOneGrace() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", MODERN);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);
      Asked asked = asked(channel, MODERN);
      // node-minecraft-protocol 1.68: settings, brand, pong and keep-alive, never a cookie.
      send(channel, ProtocolState.CONFIGURATION, SETTINGS, MODERN);
      send(channel, ProtocolState.CONFIGURATION, brand("vanilla"), MODERN);
      send(channel, ProtocolState.CONFIGURATION, new ConfigPong(asked.ping), MODERN);
      send(channel, ProtocolState.CONFIGURATION, new KeepAlive(asked.keepAlive), MODERN);
      assertEquals(1, bots().status().verifications().running(), "waiting for the cookie");

      advance(channel, Probe.COOKIE_GRACE);

      Identity steve = new Identity.NameAtAddress("Steve", address("203.0.113.9"));
      assertTrue(waitFor(() -> bots().verified().check(steve).isPresent()), "one flag: trusted");
      assertEquals(1, bots().status().verifications().passed());
      assertEquals(0, bots().status().verifications().running());
      assertNotRefused();
    }

    @Test
    @DisplayName("should let a client in with two flags or more, without trusting it")
    void flaggedPassesUntrusted() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", MODERN);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);
      Asked asked = asked(channel, MODERN);

      // No brand and no cookie kept (a client that stores none), the rest as vanilla.
      send(channel, ProtocolState.CONFIGURATION, SETTINGS, MODERN);
      send(channel, ProtocolState.CONFIGURATION, new ConfigPong(asked.ping), MODERN);
      send(channel, ProtocolState.CONFIGURATION, new KeepAlive(asked.keepAlive), MODERN);
      assertNotNull(asked.cookie);
      send(
          channel, ProtocolState.CONFIGURATION, new CookieResponse(Probe.COOKIE_KEY, null), MODERN);

      assertTrue(waitFor(() -> bots().status().verifications().passed() == 1));
      assertNotRefused();
      assertEquals(1, bots().status().verifications().escalated());
      Thread.sleep(100);
      assertFalse(
          bots()
              .verified()
              .check(new Identity.NameAtAddress("Steve", address("203.0.113.9")))
              .isPresent(),
          "let in, not trusted: probed again next time");
    }

    @Test
    @DisplayName("should probe nobody when verification is off for the mode")
    void neverMode() throws Exception {
      AntiBotConfig.Verification d = AntiBotConfig.Verification.DEFAULTS;
      start(false, withModes(d, AntiBotConfig.Mode.NEVER, AntiBotConfig.Mode.NEVER));
      EmbeddedChannel channel = login("Steve", "203.0.113.9", MODERN);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);

      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);

      assertNotAsked(channel);
    }
  }

  // ---------------------------------------------------------------------------
  // The pre-join probe (1.8 to 1.20.1)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("the pre-join probe")
  class PreJoinProbe {

    @Test
    @DisplayName(
        "should send one keep-alive after Login Success and trust the client that echoes it")
    void echoPasses() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", LEGACY);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, LEGACY);

      KeepAlive keepAlive = nextPacket(channel, ProtocolState.PLAY, KeepAlive.class, LEGACY);
      send(channel, ProtocolState.PLAY, new KeepAlive(keepAlive.id()), LEGACY);

      Identity steve = new Identity.NameAtAddress("Steve", address("203.0.113.9"));
      assertTrue(waitFor(() -> bots().verified().check(steve).isPresent()));
      assertEquals(VerificationMethod.PROBE, bots().verified().check(steve).orElseThrow().method());
      assertNotRefused();
    }

    @Test
    @DisplayName("should disconnect a client that echoes another id")
    void wrongEcho() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", LEGACY);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, LEGACY);
      KeepAlive keepAlive = nextPacket(channel, ProtocolState.PLAY, KeepAlive.class, LEGACY);

      send(channel, ProtocolState.PLAY, new KeepAlive(keepAlive.id() ^ 1), LEGACY);

      nextPacket(channel, ProtocolState.PLAY, PlayDisconnect.class, LEGACY);
      assertEquals(1, limits.refusals(Refusal.VERIFICATION_FAILED));
    }

    @Test
    @DisplayName("should time out a client that never echoes")
    void timesOut() throws Exception {
      start(false, AntiBotConfig.Verification.DEFAULTS);
      EmbeddedChannel channel = login("Steve", "203.0.113.9", LEGACY);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, LEGACY);
      nextPacket(channel, ProtocolState.PLAY, KeepAlive.class, LEGACY);

      advance(channel, AntiBotConfig.Verification.DEFAULTS.probeTimeout());

      nextPacket(channel, ProtocolState.PLAY, PlayDisconnect.class, LEGACY);
      assertEquals(1, limits.refusals(Refusal.VERIFICATION_TIMEOUT));
    }
  }

  // ---------------------------------------------------------------------------
  // Before authentication, under attack (online mode)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("before authentication, under attack")
  class BeforeAuthentication {

    @Test
    @DisplayName(
        "should probe an unknown player before asking it to authenticate, then store its token"
            + " and transfer it back, and let it authenticate on its return")
    void transferBackWithToken() throws Exception {
      start(true, AntiBotConfig.Verification.DEFAULTS);
      attackWaves();

      // Under attack: Login Success without authentication, then the probe.
      EmbeddedChannel channel = login("Newbie", "198.51.100.7", MODERN);
      LoginSuccess success = nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      assertEquals("Newbie", success.username());
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);
      Asked asked = asked(channel, MODERN);
      answerAsVanilla(channel, asked, MODERN);

      StoreCookie stored =
          nextPacket(channel, ProtocolState.CONFIGURATION, StoreCookie.class, MODERN);
      assertEquals(VerificationTokens.COOKIE_KEY, stored.key());
      assertEquals(VerificationTokens.LENGTH, stored.payload().length);
      Transfer transfer = nextPacket(channel, ProtocolState.CONFIGURATION, Transfer.class, MODERN);
      assertEquals(HOST, transfer.host());
      assertEquals(PORT, transfer.port());
      channel.runPendingTasks();
      assertFalse(channel.isOpen(), "never admitted from this path");
      assertEquals(1, bots().status().verifications().tokensIssued());

      // Back by a transfer, with the token: no probe, straight to authentication, although the
      // proxy accepts no transfers of its own accord.
      EmbeddedChannel back = transferLogin("Newbie", "198.51.100.7", MODERN);
      CookieRequest request = nextPacket(back, ProtocolState.LOGIN, CookieRequest.class, MODERN);
      assertEquals(VerificationTokens.COOKIE_KEY, request.key());
      send(
          back,
          ProtocolState.LOGIN,
          new CookieResponse(VerificationTokens.COOKIE_KEY, stored.payload()),
          MODERN);
      nextPacket(back, ProtocolState.LOGIN, EncryptionRequest.class, MODERN);
      assertEquals(1, bots().status().verifications().tokensAccepted());
    }

    @Test
    @DisplayName("should read the acknowledgement of a client it compresses, and probe it")
    void compressedAcknowledgement() throws Exception {
      compressionThreshold = 256;
      start(true, AntiBotConfig.Verification.DEFAULTS);
      attackWaves();

      EmbeddedChannel channel = login("Newbie", "198.51.100.7", MODERN);
      SetCompression compression =
          nextPacket(channel, ProtocolState.LOGIN, SetCompression.class, MODERN);
      assertEquals(256, compression.threshold());
      compressed = true;
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      // Two bytes once framed, which the login's frame limits would refuse as a Login Start.
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);

      assertTrue(channel.isOpen(), "acknowledged");
      assertEquals(0, limits.refusals(Refusal.INVALID_FRAME));
      Asked asked = asked(channel, MODERN);
      answerAsVanilla(channel, asked, MODERN);
      StoreCookie stored =
          nextPacket(channel, ProtocolState.CONFIGURATION, StoreCookie.class, MODERN);
      assertEquals(VerificationTokens.COOKIE_KEY, stored.key());
      nextPacket(channel, ProtocolState.CONFIGURATION, Transfer.class, MODERN);
      assertNotRefused();
    }

    @Test
    @DisplayName(
        "should let another proxy of the fleet, which issued no token itself, accept the transfer"
            + " back with the token")
    void fleetPeerAcceptsTransfer() throws Exception {
      env =
          name ->
              switch (name) {
                case AntiBotConfig.ENV_SECRET -> "the fleet's identity secret";
                case AntiBotConfig.ENV_TOKEN_SECRET -> "the fleet's token secret";
                default -> null;
              };
      start(true, AntiBotConfig.Verification.DEFAULTS);
      attackWaves();
      EmbeddedChannel channel = login("Newbie", "198.51.100.7", MODERN);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);
      answerAsVanilla(channel, asked(channel, MODERN), MODERN);
      StoreCookie stored =
          nextPacket(channel, ProtocolState.CONFIGURATION, StoreCookie.class, MODERN);

      // The load balancer lands the transfer on a calm peer, which accepts no transfers of its own
      // accord and never issued a token.
      AntiBotService other = service(online(), verificationConfig());
      peer = other;
      EmbeddedChannel back = transferLogin("Newbie", "198.51.100.7", MODERN);
      CookieRequest request = nextPacket(back, ProtocolState.LOGIN, CookieRequest.class, MODERN);
      assertEquals(VerificationTokens.COOKIE_KEY, request.key());
      send(
          back,
          ProtocolState.LOGIN,
          new CookieResponse(VerificationTokens.COOKIE_KEY, stored.payload()),
          MODERN);

      nextPacket(back, ProtocolState.LOGIN, EncryptionRequest.class, MODERN);
      assertEquals(1, other.status().verifications().tokensAccepted());
      assertEquals(0, limits.refusals(Refusal.TRANSFER_REFUSED));
    }

    @Test
    @DisplayName("should refuse a transfer back without a valid token")
    void forgedToken() throws Exception {
      start(true, AntiBotConfig.Verification.DEFAULTS);
      attackWaves();
      EmbeddedChannel channel = login("Newbie", "198.51.100.7", MODERN);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);
      answerAsVanilla(channel, asked(channel, MODERN), MODERN);
      StoreCookie stored =
          nextPacket(channel, ProtocolState.CONFIGURATION, StoreCookie.class, MODERN);
      byte[] forged = stored.payload().clone();
      forged[forged.length - 1] ^= 1;

      // Another name on the same network, with the real token: not its token. The proxy accepts
      // no transfers but its own, which the token was to prove.
      EmbeddedChannel other = transferLogin("Someone", "198.51.100.8", MODERN);
      nextPacket(other, ProtocolState.LOGIN, CookieRequest.class, MODERN);
      send(
          other,
          ProtocolState.LOGIN,
          new CookieResponse(VerificationTokens.COOKIE_KEY, stored.payload()),
          MODERN);
      nextPacket(other, ProtocolState.LOGIN, LoginDisconnect.class, MODERN);

      // The right player with a changed token: refused the same way, known or not.
      EmbeddedChannel back = transferLogin("Newbie", "198.51.100.7", MODERN);
      nextPacket(back, ProtocolState.LOGIN, CookieRequest.class, MODERN);
      send(
          back,
          ProtocolState.LOGIN,
          new CookieResponse(VerificationTokens.COOKIE_KEY, forged),
          MODERN);
      nextPacket(back, ProtocolState.LOGIN, LoginDisconnect.class, MODERN);
      assertEquals(2, bots().status().verifications().tokensRejected());
      assertEquals(2, limits.refusals(Refusal.TRANSFER_REFUSED));
    }

    @Test
    @DisplayName("should ask a client without transfers to reconnect, and know it when it does")
    void reconnectBefore1205() throws Exception {
      start(true, AntiBotConfig.Verification.DEFAULTS);
      attackWaves();
      EmbeddedChannel channel = login("Newbie", "198.51.100.7", FIRST_CONFIGURATION);
      nextPacket(channel, ProtocolState.LOGIN, LoginSuccess.class, FIRST_CONFIGURATION);
      send(channel, ProtocolState.LOGIN, new LoginAcknowledged(), FIRST_CONFIGURATION);
      answerAsVanilla(channel, asked(channel, FIRST_CONFIGURATION), FIRST_CONFIGURATION);

      nextPacket(channel, ProtocolState.CONFIGURATION, ConfigDisconnect.class, FIRST_CONFIGURATION);
      assertEquals(1, limits.refusals(Refusal.VERIFIED_RECONNECT));
      Identity newbie = new Identity.NameAtAddress("Newbie", address("198.51.100.7"));
      assertTrue(waitFor(() -> bots().verified().check(newbie).isPresent()));

      EmbeddedChannel back = login("Newbie", "198.51.100.7", FIRST_CONFIGURATION);
      nextPacket(back, ProtocolState.LOGIN, EncryptionRequest.class, FIRST_CONFIGURATION);
    }

    @Test
    @DisplayName(
        "should ask a transferred client for its token even when it issued none itself, and refuse"
            + " it without one when the proxy accepts no transfers")
    void noTransferExpected() throws Exception {
      start(true, AntiBotConfig.Verification.DEFAULTS);

      EmbeddedChannel channel = transferLogin("Newbie", "198.51.100.7", MODERN);
      CookieRequest request = nextPacket(channel, ProtocolState.LOGIN, CookieRequest.class, MODERN);
      assertEquals(VerificationTokens.COOKIE_KEY, request.key());
      send(
          channel,
          ProtocolState.LOGIN,
          new CookieResponse(VerificationTokens.COOKIE_KEY, null),
          MODERN);

      nextPacket(channel, ProtocolState.LOGIN, LoginDisconnect.class, MODERN);
      assertEquals(1, limits.refusals(Refusal.TRANSFER_REFUSED));
    }
  }

  // ---------------------------------------------------------------------------
  // The queue
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("the queue")
  class Queue {

    @Test
    @DisplayName("should hold the next unknown login until a verification ends, then start its own")
    void waitsForRoom() throws Exception {
      AntiBotConfig.Verification d = AntiBotConfig.Verification.DEFAULTS;
      start(false, withQueue(d, 1, 16));
      EmbeddedChannel first = login("First", "203.0.113.9", MODERN);
      nextPacket(first, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      send(first, ProtocolState.LOGIN, new LoginAcknowledged(), MODERN);
      Asked asked = asked(first, MODERN);

      EmbeddedChannel second = login("Second", "203.0.113.10", MODERN);
      assertNull(second.readOutbound(), "in line: no Login Success yet");
      assertEquals(1, bots().status().verifications().waiting());

      answerAsVanilla(first, asked, MODERN);
      second.runPendingTasks();

      nextPacket(second, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      assertEquals(0, bots().status().verifications().waiting());
      assertEquals(1, bots().status().verifications().grantedFromLine());
    }

    @Test
    @DisplayName("should tell a login the server is busy when the line is full")
    void busy() throws Exception {
      AntiBotConfig.Verification d = AntiBotConfig.Verification.DEFAULTS;
      start(false, withQueue(d, 1, 0));
      EmbeddedChannel first = login("First", "203.0.113.9", MODERN);
      nextPacket(first, ProtocolState.LOGIN, LoginSuccess.class, MODERN);

      EmbeddedChannel second = login("Second", "203.0.113.10", MODERN);

      nextPacket(second, ProtocolState.LOGIN, LoginDisconnect.class, MODERN);
      assertEquals(1, limits.refusals(Refusal.VERIFICATION_BUSY));
    }

    @Test
    @DisplayName("should let a known player in while unknown ones wait")
    void knownNeverQueue() throws Exception {
      AntiBotConfig.Verification d = AntiBotConfig.Verification.DEFAULTS;
      start(false, withQueue(d, 1, 16));
      bots()
          .trustByCommand(new Identity.NameAtAddress("Alex", address("198.51.100.1")))
          .get(5, TimeUnit.SECONDS);
      EmbeddedChannel first = login("First", "203.0.113.9", MODERN);
      nextPacket(first, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
      EmbeddedChannel second = login("Second", "203.0.113.10", MODERN);
      assertNull(second.readOutbound());

      EmbeddedChannel alex = login("Alex", "198.51.100.1", MODERN);

      nextPacket(alex, ProtocolState.LOGIN, LoginSuccess.class, MODERN);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers: the anti-bot
  // ---------------------------------------------------------------------------

  private void start(boolean online, AntiBotConfig.Verification verification) throws IOException {
    this.onlineMode = online;
    this.verification = verification;
    antiBot = service(online, verificationConfig());
    new PluginEventBus(events, "test", null, _ -> {}).register(new FailureListener());
  }

  private boolean online() {
    return onlineMode;
  }

  /** The configuration {@link #start} was given, with its verification settings. */
  private AntiBotConfig verificationConfig() {
    AntiBotConfig d = AntiBotConfig.DEFAULTS;
    AntiBotConfig.Verified v = d.verified();
    return new AntiBotConfig(
        true,
        CidrRanges.none(),
        new AntiBotConfig.Verified(
            v.expireAfterLastJoin(), 1_000, 24, 48, false, v.secretFile(), v.sharedStoreTimeout()),
        d.attack(),
        d.blocklist(),
        d.checkTimeout(),
        Objects.requireNonNull(verification, "start() first"));
  }

  /** An anti-bot on this test's clocks and secrets: the proxy's, or a fleet peer's. */
  private AntiBotService service(boolean online, AntiBotConfig config) throws IOException {
    return new AntiBotService(
        config,
        online,
        limits,
        events,
        LoggingConfig.DEFAULTS,
        directory,
        env,
        millis::get,
        nanos::get);
  }

  /** Hears of failed verifications. */
  public final class FailureListener {
    @Subscribe
    public void failed(VerificationFailedEvent event) {
      failures.add(event);
    }
  }

  private static AntiBotConfig.Verification withModes(
      AntiBotConfig.Verification d, AntiBotConfig.Mode offline, AntiBotConfig.Mode online) {
    return new AntiBotConfig.Verification(
        offline,
        online,
        d.maxConcurrent(),
        d.queueSize(),
        d.queueMaxWait(),
        d.probeTimeout(),
        d.tokenLifetime(),
        d.tokenRotateAfter(),
        d.tokenSecretFile(),
        d.transferHost(),
        d.transferPort());
  }

  private static AntiBotConfig.Verification withQueue(
      AntiBotConfig.Verification d, int maxConcurrent, int queueSize) {
    return new AntiBotConfig.Verification(
        d.offlineMode(),
        d.onlineMode(),
        maxConcurrent,
        queueSize,
        d.queueMaxWait(),
        d.probeTimeout(),
        d.tokenLifetime(),
        d.tokenRotateAfter(),
        d.tokenSecretFile(),
        d.transferHost(),
        d.transferPort());
  }

  private AntiBotService bots() {
    return Objects.requireNonNull(antiBot, "start() first");
  }

  /** Three seconds of 20 unknown logins each, from 20 addresses: an attack. */
  private void attackWaves() {
    for (int second = 0; second < 3; second++) {
      for (int i = 0; i < 20; i++) {
        login("bot_" + second + "_" + i, "203.0.113." + (i + 1), MODERN);
      }
      millis.addAndGet(1_000);
      bots().tick();
    }
    assertEquals(AttackLevel.ATTACK, bots().level());
  }

  /** Moves both clocks and the channel's scheduler forward. */
  private void advance(EmbeddedChannel channel, Duration duration) {
    nanos.addAndGet(duration.toNanos());
    millis.addAndGet(duration.toMillis());
    channel.advanceTimeBy(duration.toMillis(), TimeUnit.MILLISECONDS);
    channel.runScheduledPendingTasks();
    channel.runPendingTasks();
  }

  // ---------------------------------------------------------------------------
  // Helpers: the client
  // ---------------------------------------------------------------------------

  private EmbeddedChannel login(String name, String address, ProtocolVersion version) {
    return login(name, address, version, HandshakeIntent.LOGIN);
  }

  private EmbeddedChannel login(
      String name, String address, ProtocolVersion version, HandshakeIntent intent) {
    EmbeddedChannel channel = connect(address);
    channel.writeInbound(handshake(version, intent.id()), loginStart(name, version));
    channel.runPendingTasks();
    return channel;
  }

  private EmbeddedChannel transferLogin(String name, String address, ProtocolVersion version) {
    return login(name, address, version, HandshakeIntent.TRANSFER);
  }

  private EmbeddedChannel connect(String address) {
    AntiBotService reached = peer != null ? peer : bots();
    ServerLoginContext context =
        TestLoginContexts.withAntiBot(
            onlineMode, compressionThreshold, new MojangSessionService(), events, limits, reached);
    EmbeddedChannel channel =
        new ProxyProtocolTest.PeerChannel(new InetSocketAddress(address(address), 40000));
    channel.pipeline().addLast(new ServerChannelInitializer(context));
    return channel;
  }

  /** What the probe asked, read from the frames the proxy sent on entering configuration. */
  private record Asked(int ping, long keepAlive, byte @Nullable [] cookie) {}

  private Asked asked(EmbeddedChannel channel, ProtocolVersion version) {
    ConfigPing ping = nextPacket(channel, ProtocolState.CONFIGURATION, ConfigPing.class, version);
    KeepAlive keepAlive =
        nextPacket(channel, ProtocolState.CONFIGURATION, KeepAlive.class, version);
    if (!version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
      assertNull(channel.readOutbound(), "nothing more asked before 1.20.5");
      return new Asked(ping.id(), keepAlive.id(), null);
    }
    StoreCookie stored =
        nextPacket(channel, ProtocolState.CONFIGURATION, StoreCookie.class, version);
    assertEquals(Probe.COOKIE_KEY, stored.key());
    CookieRequest request =
        nextPacket(channel, ProtocolState.CONFIGURATION, CookieRequest.class, version);
    assertEquals(Probe.COOKIE_KEY, request.key());
    assertNull(channel.readOutbound(), "nothing more asked");
    return new Asked(ping.id(), keepAlive.id(), stored.payload());
  }

  /** Answers as a vanilla client does: settings and brand first, then each request in order. */
  private void answerAsVanilla(EmbeddedChannel channel, Asked asked, ProtocolVersion version) {
    answerAsVanilla(channel, asked, version, brand("vanilla"));
  }

  private void answerAsVanilla(
      EmbeddedChannel channel, Asked asked, ProtocolVersion version, ConfigPluginMessage brand) {
    send(channel, ProtocolState.CONFIGURATION, SETTINGS, version);
    send(channel, ProtocolState.CONFIGURATION, brand, version);
    send(channel, ProtocolState.CONFIGURATION, new ConfigPong(asked.ping), version);
    send(channel, ProtocolState.CONFIGURATION, new KeepAlive(asked.keepAlive), version);
    if (asked.cookie != null) {
      send(
          channel,
          ProtocolState.CONFIGURATION,
          new CookieResponse(Probe.COOKIE_KEY, asked.cookie),
          version);
    }
  }

  private static ConfigPluginMessage brand(String brand) {
    ByteBuf buf = Unpooled.buffer();
    try {
      McString.write(buf, brand);
      return new ConfigPluginMessage(ConfigPluginMessage.BRAND_CHANNEL, ByteBufUtil.getBytes(buf));
    } finally {
      buf.release();
    }
  }

  /** The verification refused nobody. */
  private void assertNotRefused() {
    assertEquals(0, limits.refusals(Refusal.VERIFICATION_FAILED));
    assertEquals(0, limits.refusals(Refusal.VERIFICATION_TIMEOUT));
    assertEquals(0, bots().status().verifications().failedTotal());
  }

  /**
   * The proxy asked nothing of the client: whatever it wrote after Login Acknowledged (the
   * disconnect of a first server this test has none of, at most) is not the probe's ping.
   */
  private static void assertNotAsked(EmbeddedChannel channel) {
    channel.runPendingTasks();
    ByteBuf frame = channel.readOutbound();
    if (frame == null) {
      return;
    }
    try {
      VarInt.read(frame);
      int id = VarInt.read(frame);
      int ping =
          StateRegistry.get(ProtocolState.CONFIGURATION, PacketDirection.CLIENTBOUND)
              .packetId(MODERN, ConfigPing.class);
      assertTrue(id != ping, "probed");
    } finally {
      frame.release();
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers: frames
  // ---------------------------------------------------------------------------

  private <T extends Packet> void send(
      EmbeddedChannel channel, ProtocolState state, T packet, ProtocolVersion version) {
    channel.writeInbound(frame(body(state, packet, version)));
    channel.runPendingTasks();
  }

  /** A serverbound packet's body: its id and fields, what a frame carries. */
  @SuppressWarnings("unchecked")
  private static <T extends Packet> ByteBuf body(
      ProtocolState state, T packet, ProtocolVersion version) {
    PacketRegistry.Encoding encoding =
        StateRegistry.get(state, PacketDirection.SERVERBOUND).encoding(version, packet.getClass());
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, encoding.packetId());
    ((PacketCodec<T>) encoding.codec()).encode(packet, body, version);
    return body;
  }

  @SuppressWarnings("unchecked")
  private <T extends Packet> T nextPacket(
      EmbeddedChannel channel, ProtocolState state, Class<T> type, ProtocolVersion version) {
    channel.runPendingTasks();
    ByteBuf frame = channel.readOutbound();
    assertNotNull(frame, "expected a " + type.getSimpleName());
    try {
      assertEquals(VarInt.read(frame), frame.readableBytes(), "frame length");
      ByteBuf packet = compressed ? inflated(frame) : frame;
      try {
        PacketRegistry registry = StateRegistry.get(state, PacketDirection.CLIENTBOUND);
        int id = VarInt.read(packet);
        assertEquals(
            registry.packetId(version, type),
            id,
            type.getSimpleName() + " packet id (got " + id + ")");
        return ((PacketCodec<T>) registry.encoding(version, type).codec()).decode(packet, version);
      } finally {
        if (packet != frame) {
          packet.release();
        }
      }
    } finally {
      frame.release();
    }
  }

  /** The packet of a frame in compressed framing: as is below the threshold, inflated above. */
  private static ByteBuf inflated(ByteBuf frame) {
    int dataLength = VarInt.read(frame);
    if (dataLength == 0) {
      return frame;
    }
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(ByteBufUtil.getBytes(frame));
      byte[] data = new byte[dataLength];
      assertEquals(dataLength, inflater.inflate(data), "inflated size");
      frame.skipBytes(frame.readableBytes());
      return Unpooled.wrappedBuffer(data);
    } catch (DataFormatException e) {
      throw new AssertionError(e);
    } finally {
      inflater.end();
    }
  }

  private ByteBuf handshake(ProtocolVersion version, int nextState) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    VarInt.write(body, version.protocol());
    McString.write(body, HOST);
    body.writeShort(PORT);
    VarInt.write(body, nextState);
    return frame(body);
  }

  private ByteBuf loginStart(String name, ProtocolVersion version) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_19_1)) {
      UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
      if (version.isOlderThan(ProtocolVersion.MINECRAFT_1_20_2)) {
        body.writeBoolean(true);
      }
      McUuid.write(body, uuid);
    }
    return frame(body);
  }

  /** Frames a body as the client does: with a zero data length once the connection compresses. */
  private ByteBuf frame(ByteBuf body) {
    ByteBuf frame = Unpooled.buffer();
    VarInt.write(frame, body.readableBytes() + (compressed ? 1 : 0));
    if (compressed) {
      VarInt.write(frame, 0);
    }
    frame.writeBytes(body);
    body.release();
    return frame;
  }

  private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - deadline > 0) {
        return false;
      }
      Thread.sleep(10);
    }
    return true;
  }

  private static InetAddress address(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }
}
