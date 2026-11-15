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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.warp.api.server.ServerInfo;
import dev.warp.protocol.ProtocolState;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.config.AcknowledgeFinishConfiguration;
import dev.warp.protocol.packet.config.FinishConfiguration;
import dev.warp.protocol.packet.config.KnownPacks;
import dev.warp.protocol.packet.config.RegistryData;
import dev.warp.protocol.packet.cookie.CookieRequest;
import dev.warp.protocol.packet.cookie.StoreCookie;
import dev.warp.protocol.packet.login.EncryptionRequest;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.antibot.AntiBotStatus;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.config.AntiBotConfig;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.config.DuplicateLoginPolicy;
import dev.warp.proxy.config.LoggingConfig;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.fleet.FleetService;
import dev.warp.proxy.i18n.Message;
import dev.warp.proxy.network.CidrRanges;
import dev.warp.proxy.server.ServerRegistry;
import dev.warp.proxy.transfer.TransferHandoff;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The seamless handoff beside the features that share its login: the fleet, which settles who owns
 * an account across proxies, and the virtual world, where the anti-bot checks players and where
 * those without a server wait.
 */
@DisplayName("seamless handoff with the fleet and the virtual world")
class HandoffInteractionsTest {

  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;
  private static final String HOST = "play.warp.dev";
  private static final int PORT = 25565;
  private static final String CLIENT = "198.51.100.7";
  private static final byte[] SECRET =
      "a-shared-secret-of-the-network".getBytes(StandardCharsets.UTF_8);
  private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

  @TempDir private Path directory;

  private final AtomicLong millis = new AtomicLong(1_800_000_000_000L);
  private final AtomicLong nanos = new AtomicLong(TimeUnit.HOURS.toNanos(1));
  private final WarpEventBus events = new WarpEventBus(Runnable::run);
  private final MojangSessionService sessionService = mock(MojangSessionService.class);
  private final TransferHandoff source = handoff("warp-1:25565");
  private final TransferHandoff target = handoff("warp-2:25565");
  private final List<EmbeddedChannel> channels = new ArrayList<>();
  private @Nullable ServerLoginContext context;

  private TransferHandoff handoff(String nodeId) {
    return TransferHandoff.of(
        SECRET,
        Duration.ofSeconds(15),
        true,
        List.of(new DrainConfig.Target(HOST, PORT)),
        nodeId,
        millis::get);
  }

  @BeforeEach
  void setUp() throws Exception {
    when(sessionService.hasJoined(eq("Steve"), anyString()))
        .thenReturn(new GameProfile(STEVE, "Steve", List.of()));
  }

  @AfterEach
  void tearDown() {
    for (EmbeddedChannel channel : channels) {
      channel.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // The fleet
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("on a proxy of a fleet")
  class Fleet {

    private final FleetService fleet = mock(FleetService.class);

    private void fleetAnswers(FleetService.Claim claim) {
      when(fleet.claim(any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(claim));
    }

    private ServerLoginContext proxy(DuplicateLoginPolicy policy) {
      return TestLoginContexts.withFleet(
          TestLoginContexts.acceptingHandoffs(true, sessionService, events, lobbyOnly(), target),
          policy,
          fleet);
    }

    @Test
    @DisplayName("should claim the account as a handoff, which the proxy it left yields to")
    void claimsAsHandoff() throws Exception {
      fleetAnswers(new FleetService.Claim.Allowed());
      ServerLoginContext proxy = proxy(DuplicateLoginPolicy.REFUSE_NEW);

      arrive(proxy, issue(true));

      verify(fleet).claim(registered("Steve"), true);
    }

    @Test
    @DisplayName("should claim like any login when the ticket is not used")
    void claimsAsAnyLogin() throws Exception {
      fleetAnswers(new FleetService.Claim.Allowed());
      ServerLoginContext proxy = proxy(DuplicateLoginPolicy.REFUSE_NEW);
      HandoffLoginTest.Client client = connect(proxy);

      client.next(CookieRequest.class);
      client.answer(TransferHandoff.TICKET_COOKIE, new byte[] {1, 2, 3});
      EncryptionRequest request = client.next(EncryptionRequest.class);
      assertTrue(request.shouldAuthenticate(), "authenticated with Mojang");
      client.encrypt(request);
      client.await(LoginSuccess.class);

      verify(fleet).claim(registered("Steve"), false);
    }

    @Test
    @DisplayName("should refuse a handed-off player the fleet refuses: never around its claim")
    void fleetRefuses() throws Exception {
      fleetAnswers(new FleetService.Claim.Refused(Message.FLEET_UNAVAILABLE));
      ServerLoginContext proxy = proxy(DuplicateLoginPolicy.AUTO);
      TransferHandoff.Issued issued = issue(true);
      HandoffLoginTest.Client client = present(proxy, issued);

      client.next(CookieRequest.class);
      client.answer(TransferHandoff.PROOF_COOKIE, issued.proof());

      client.next(LoginDisconnect.class);
      assertEquals(0, proxy.playerRegistry().count());
    }

    @Test
    @DisplayName("should replace what is left of the session on this proxy, whatever the policy")
    void replacesTheSessionLeftHere() throws Exception {
      fleetAnswers(new FleetService.Claim.Allowed());
      ServerLoginContext proxy = proxy(DuplicateLoginPolicy.REFUSE_NEW);
      ConnectedPlayer leaving = session(proxy);

      arrive(proxy, issue(true));

      verify(leaving).disconnectDuplicate();
      assertFalse(registered("Steve") == leaving, "the arrival holds the account");
    }

    @Test
    @DisplayName("should refuse a second login without a ticket where the policy keeps the first")
    void refusesARivalLogin() throws Exception {
      fleetAnswers(new FleetService.Claim.Allowed());
      ServerLoginContext proxy = proxy(DuplicateLoginPolicy.REFUSE_NEW);
      ConnectedPlayer playing = session(proxy);
      HandoffLoginTest.Client client = connect(proxy);

      client.next(CookieRequest.class);
      client.answer(TransferHandoff.TICKET_COOKIE, null);
      client.encrypt(client.next(EncryptionRequest.class));
      client.await(LoginDisconnect.class);

      verify(playing, never()).disconnectDuplicate();
      assertSame(playing, proxy.playerRegistry().get(STEVE));
      verify(fleet, never()).claim(any(), anyBoolean());
    }

    /** A session of Steve this proxy still holds. */
    private ConnectedPlayer session(ServerLoginContext proxy) {
      ConnectedPlayer player = mock(ConnectedPlayer.class);
      when(player.uuid()).thenReturn(STEVE);
      when(player.username()).thenReturn("Steve");
      proxy.playerRegistry().register(player, true);
      return player;
    }
  }

  // ---------------------------------------------------------------------------
  // The virtual world
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("on a proxy with the world check and the hold")
  class VirtualWorld {

    /** No server yet: the one the ticket names is not here, and the default is down. */
    private final ServerRegistry servers = new ServerRegistry(Map.of(), "lobby", List.of("lobby"));

    private @Nullable AntiBotService antiBot;

    private ServerLoginContext proxy() throws IOException {
      ServerLoginContext base =
          TestLoginContexts.acceptingHandoffs(true, sessionService, events, servers, target);
      AntiBotConfig d = AntiBotConfig.DEFAULTS;
      AntiBotConfig.Verified v = d.verified();
      AntiBotConfig config =
          new AntiBotConfig(
              true,
              CidrRanges.none(),
              new AntiBotConfig.Verified(
                  v.expireAfterLastJoin(),
                  1_000,
                  24,
                  48,
                  false,
                  v.secretFile(),
                  v.sharedStoreTimeout()),
              d.attack(),
              d.blocklist(),
              d.checkTimeout(),
              AntiBotConfig.Verification.DEFAULTS,
              new AntiBotConfig.World(true, true, Duration.ofSeconds(5), Duration.ofMinutes(5)));
      AntiBotService service =
          new AntiBotService(
              config,
              true,
              base.limits(),
              events,
              LoggingConfig.DEFAULTS,
              directory,
              _ -> null,
              millis::get,
              nanos::get);
      antiBot = service;
      return TestLoginContexts.withAntiBot(base, service);
    }

    private AntiBotStatus status() {
      return Objects.requireNonNull(antiBot, "proxy() first").status();
    }

    @Test
    @DisplayName("should spare a player its sibling trusts the probe and the world check")
    void skipsTheWorldCheck() throws Exception {
      ServerLoginContext proxy = proxy();

      HandoffLoginTest.Client client = arrive(proxy, issue(true));
      client.acknowledge();

      assertEquals(0, status().verifications().running(), "no probe");
      assertEquals(0, status().world().checks(), "no world check");
      assertTrue(client.channel.isActive());
    }

    @Test
    @DisplayName("should verify a player its sibling did not trust like any other login")
    void verifiesAnUntrustedPlayer() throws Exception {
      ServerLoginContext proxy = proxy();

      HandoffLoginTest.Client client = arrive(proxy, issue(false));
      client.acknowledge();

      assertEquals(1, status().verifications().running(), "probed, then checked in the world");
      assertEquals(0, status().world().holds());
    }

    @Test
    @DisplayName("should hold the player in the world while no server is up, then try the default")
    void heldThenRouted() throws Exception {
      ServerLoginContext proxy = proxy();

      HandoffLoginTest.Client client = arrive(proxy, issue(true));
      client.acknowledge();

      assertTrue(client.channel.isActive(), "held, not disconnected");
      assertEquals(1, status().world().holds());
      assertEquals(0, status().world().checks(), "the hold is not a check");
      enterWorld(client);
      ConnectedPlayer player = registered("Steve");
      assertTrue(player.isInWorld());
      assertEquals(1, status().world().holding());

      // The default server comes back: the hold's next retry sends the player there, as it would
      // any player; the server the ticket named plays no part once the player waits.
      assertEquals(0, servers.health().status("lobby").failures());
      servers.register(
          new ServerInfo("lobby", new InetSocketAddress(InetAddress.getLoopbackAddress(), 1)));
      advance(client, Duration.ofSeconds(6));

      // Nothing listens there: the attempt fails, which is how the test sees it was made, and the
      // player goes on waiting rather than being disconnected.
      assertEquals(1, servers.health().status("lobby").failures(), "tried the default server");
      assertEquals(1, status().world().holding());
      assertTrue(client.channel.isActive());
    }

    /** Answers the world's configuration as a vanilla client does, up to its play state. */
    private void enterWorld(HandoffLoginTest.Client client) {
      // The emptied handoff cookies come first: written once the client reads configuration.
      assertEquals(
          TransferHandoff.TICKET_COOKIE,
          client.next(ProtocolState.CONFIGURATION, StoreCookie.class).key());
      assertEquals(
          TransferHandoff.PROOF_COOKIE,
          client.next(ProtocolState.CONFIGURATION, StoreCookie.class).key());
      KnownPacks.Pack core = new KnownPacks.Pack("minecraft", "core", MODERN.name());
      KnownPacks offer = client.next(ProtocolState.CONFIGURATION, KnownPacks.class);
      assertTrue(offer.packs().contains(core), offer.toString());
      client.send(ProtocolState.CONFIGURATION, new KnownPacks(List.of(core)));
      for (int i = 0; i < 3; i++) {
        assertNotNull(client.next(ProtocolState.CONFIGURATION, RegistryData.class));
      }
      client.next(ProtocolState.CONFIGURATION, FinishConfiguration.class);
      client.send(ProtocolState.CONFIGURATION, new AcknowledgeFinishConfiguration());
    }

    private void advance(HandoffLoginTest.Client client, Duration duration) {
      nanos.addAndGet(duration.toNanos());
      millis.addAndGet(duration.toMillis());
      client.advance(duration);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static ServerRegistry lobbyOnly() {
    return new ServerRegistry(
        Map.of("lobby", new InetSocketAddress(InetAddress.getLoopbackAddress(), 1)),
        "lobby",
        List.of("lobby"));
  }

  /** A ticket of Steve, authenticated by the proxy it leaves, which was on {@code survival}. */
  private TransferHandoff.Issued issue(boolean trusted) {
    return Objects.requireNonNull(
        source.issue(
            new TransferHandoff.Subject(
                STEVE,
                "Steve",
                List.of(),
                MODERN.protocol(),
                InetAddress.ofLiteral(CLIENT),
                "survival",
                true,
                trusted),
            HOST,
            PORT),
        "a ticket");
  }

  private ConnectedPlayer registered(String name) {
    ServerLoginContext reached = Objects.requireNonNull(context, "connect first");
    return Objects.requireNonNull(reached.playerRegistry().get(name), name + " registered");
  }

  /** A transferred client of {@code proxy}, up to its Login Start. */
  private HandoffLoginTest.Client connect(ServerLoginContext proxy) {
    this.context = proxy;
    EmbeddedChannel channel =
        new ProxyProtocolTest.PeerChannel(
            new InetSocketAddress(InetAddress.ofLiteral(CLIENT), 40000));
    channels.add(channel);
    channel.pipeline().addLast(new ServerChannelInitializer(proxy));
    HandoffLoginTest.Client client = new HandoffLoginTest.Client(channel, MODERN);
    client.write(HandoffLoginTest.handshake(MODERN, HandshakeIntent.TRANSFER));
    client.write(HandoffLoginTest.loginStart("Steve", STEVE));
    return client;
  }

  /** A transferred client that presented {@code issued} and encrypted, told to skip Mojang. */
  private HandoffLoginTest.Client present(ServerLoginContext proxy, TransferHandoff.Issued issued)
      throws Exception {
    HandoffLoginTest.Client client = connect(proxy);
    assertEquals(TransferHandoff.TICKET_COOKIE, client.next(CookieRequest.class).key());
    client.answer(TransferHandoff.TICKET_COOKIE, issued.ticket());
    EncryptionRequest request = client.next(EncryptionRequest.class);
    assertFalse(request.shouldAuthenticate(), "the client skips Mojang's join");
    client.encrypt(request);
    return client;
  }

  /** A transferred client logged in with {@code issued}, up to its Login Success. */
  private HandoffLoginTest.Client arrive(ServerLoginContext proxy, TransferHandoff.Issued issued)
      throws Exception {
    HandoffLoginTest.Client client = present(proxy, issued);
    assertEquals(TransferHandoff.PROOF_COOKIE, client.next(CookieRequest.class).key());
    client.answer(TransferHandoff.PROOF_COOKIE, issued.proof());
    client.next(LoginSuccess.class);
    verify(sessionService, never()).hasJoined(anyString(), anyString());
    return client;
  }
}
