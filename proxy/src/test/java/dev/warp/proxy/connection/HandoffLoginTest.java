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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.warp.api.event.Subscribe;
import dev.warp.api.event.player.GameProfileRequestEvent;
import dev.warp.api.event.player.LoginEvent;
import dev.warp.api.event.player.PlayerChooseInitialServerEvent;
import dev.warp.api.event.player.PreLoginEvent;
import dev.warp.api.player.ClientPlatform;
import dev.warp.api.server.RegisteredServer;
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
import dev.warp.protocol.packet.cookie.CookieRequest;
import dev.warp.protocol.packet.cookie.CookieResponse;
import dev.warp.protocol.packet.cookie.StoreCookie;
import dev.warp.protocol.packet.login.EncryptionRequest;
import dev.warp.protocol.packet.login.EncryptionResponse;
import dev.warp.protocol.packet.login.LoginAcknowledged;
import dev.warp.protocol.packet.login.LoginDisconnect;
import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.protocol.packet.play.Transfer;
import dev.warp.proxy.auth.MojangSessionService;
import dev.warp.proxy.bedrock.BedrockIdentity;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.event.PluginEventBus;
import dev.warp.proxy.event.WarpEventBus;
import dev.warp.proxy.server.ServerRegistry;
import dev.warp.proxy.transfer.TransferHandoff;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The seamless handoff through Warp's pipeline, as a client sees it: the cookies stored before a
 * transfer to a peer, and the login of a transferred client that presents them, or does not.
 */
@DisplayName("seamless handoff at login")
class HandoffLoginTest {

  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;
  private static final String HOST = "play.warp.dev";
  private static final int PORT = 25565;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String CLIENT = "198.51.100.7";
  private static final byte[] SECRET =
      "a-shared-secret-of-the-network".getBytes(StandardCharsets.UTF_8);
  private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
  private static final List<LoginSuccess.Property> TICKET_TEXTURES =
      List.of(new LoginSuccess.Property("textures", "ZnJvbS10aGUtdGlja2V0", "c2lnbmVk"));
  private static final List<LoginSuccess.Property> MOJANG_TEXTURES =
      List.of(new LoginSuccess.Property("textures", "ZnJvbS1tb2phbmc=", "c2lnbmVk"));

  private final AtomicLong millis = new AtomicLong(1_800_000_000_000L);
  private final WarpEventBus events = new WarpEventBus(Runnable::run);
  private final PluginEventBus plugin = new PluginEventBus(events, "test", null, _ -> {});
  private final MojangSessionService sessionService = mock(MojangSessionService.class);
  private final ServerRegistry servers =
      new ServerRegistry(
          Map.of(
              "lobby", new InetSocketAddress(InetAddress.getLoopbackAddress(), 1),
              "survival", new InetSocketAddress(InetAddress.getLoopbackAddress(), 2)),
          "lobby",
          List.of("lobby"));

  /** The proxy players leave, and the one they land on: two proxies sharing a secret. */
  private final TransferHandoff source = handoff(SECRET, true, "warp-1:25565");

  private TransferHandoff target = handoff(SECRET, true, "warp-2:25565");
  private boolean onlineMode = true;

  private final List<EmbeddedChannel> channels = new ArrayList<>();
  private final List<String> fired = new CopyOnWriteArrayList<>();
  private final AtomicReference<@Nullable String> proposed = new AtomicReference<>();
  private @Nullable ServerLoginContext context;

  private TransferHandoff handoff(byte[] secret, boolean bindAddress, String nodeId) {
    return TransferHandoff.of(
        secret,
        Duration.ofSeconds(15),
        bindAddress,
        List.of(new DrainConfig.Target(HOST, PORT)),
        nodeId,
        millis::get);
  }

  @BeforeEach
  void setUp() throws Exception {
    when(sessionService.hasJoined(eq("Steve"), anyString()))
        .thenReturn(new GameProfile(STEVE, "Steve", MOJANG_TEXTURES));
    // Plugins never settle the first server: the test reads what the proxy proposed.
    plugin.subscribeAsync(
        PlayerChooseInitialServerEvent.class,
        Subscribe.NORMAL,
        30,
        event -> {
          proposed.set(event.proposedServer().map(RegisteredServer::name).orElse("none"));
          return new CompletableFuture<>();
        });
  }

  @AfterEach
  void tearDown() {
    for (EmbeddedChannel channel : channels) {
      channel.finishAndReleaseAll();
    }
  }

  // ---------------------------------------------------------------------------
  // The proxy a player lands on
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("a transferred client with a valid ticket")
  class Valid {

    @Test
    @DisplayName("should log in without Mojang, encrypted, as the ticket says, on the same server")
    void skipsMojang() throws Exception {
      TransferHandoff.Issued issued = issue(true, "survival");
      Client client = transferLogin();

      assertEquals(TransferHandoff.TICKET_COOKIE, client.next(CookieRequest.class).key());
      client.answer(TransferHandoff.TICKET_COOKIE, issued.ticket());
      EncryptionRequest request = client.next(EncryptionRequest.class);
      assertFalse(request.shouldAuthenticate(), "the client skips Mojang's join");
      client.encrypt(request);
      // The proof is asked for once encrypted, and never before.
      assertEquals(TransferHandoff.PROOF_COOKIE, client.next(CookieRequest.class).key());
      client.answer(TransferHandoff.PROOF_COOKIE, issued.proof());
      LoginSuccess success = client.next(LoginSuccess.class);

      assertEquals(STEVE, success.uuid());
      assertEquals("Steve", success.username());
      assertEquals(TICKET_TEXTURES, success.properties());
      verify(sessionService, never()).hasJoined(anyString(), anyString());
      client.acknowledge();
      assertEquals("survival", proposed.get());
      ConnectedPlayer player = registered("Steve");
      assertTrue(player.isHandedOff());
      assertTrue(player.api().isHandedOff());
      assertTrue(player.isTransferred());
    }

    @Test
    @DisplayName("should empty both cookies once logged in, so that neither is carried further")
    void clearsCookies() throws Exception {
      Client client = handedOff(issue(true, "survival"));

      client.acknowledge();
      StoreCookie ticket = client.next(ProtocolState.CONFIGURATION, StoreCookie.class);
      StoreCookie proof = client.next(ProtocolState.CONFIGURATION, StoreCookie.class);

      assertEquals(TransferHandoff.TICKET_COOKIE, ticket.key());
      assertEquals(0, ticket.payload().length);
      assertEquals(TransferHandoff.PROOF_COOKIE, proof.key());
      assertEquals(0, proof.payload().length);
    }

    @Test
    @DisplayName("should fire the same events as any login, which can tell the handoff apart")
    void events() throws Exception {
      List<LoginSuccess.Property> seen = new CopyOnWriteArrayList<>();
      plugin.subscribe(
          PreLoginEvent.class,
          Subscribe.NORMAL,
          event -> fired.add("pre-login " + event.connection().isHandedOff()));
      plugin.subscribe(
          GameProfileRequestEvent.class,
          Subscribe.NORMAL,
          event -> {
            fired.add("profile " + event.connection().isHandedOff() + " " + event.isOnlineMode());
            seen.addAll(GameProfile.fromApi(event.originalProfile()).properties());
          });
      plugin.subscribe(
          LoginEvent.class,
          Subscribe.NORMAL,
          event -> fired.add("login " + event.player().isHandedOff()));

      handedOff(issue(true, "survival"));

      assertEquals(List.of("pre-login true", "profile true true", "login true"), fired);
      assertEquals(TICKET_TEXTURES, seen);
    }

    @Test
    @DisplayName("should fall back to the default server when the ticket's is not here")
    void unknownServer() throws Exception {
      Client client = handedOff(issue(true, "skyblock"));

      client.acknowledge();

      assertEquals("lobby", proposed.get());
      assertTrue(registered("Steve").isHandedOff());
      verify(sessionService, never()).hasJoined(anyString(), anyString());
    }

    @Test
    @DisplayName("should find the ticket's server whatever the case of its name")
    void serverNamedExactly() throws Exception {
      Client client = handedOff(issue(true, "SURVIVAL"));

      client.acknowledge();

      assertEquals("survival", proposed.get());
    }

    @Test
    @DisplayName("should go to the default server when the player was on none")
    void noServer() throws Exception {
      Client client = handedOff(issue(true, null));

      client.acknowledge();

      assertEquals("lobby", proposed.get());
    }

    @Test
    @DisplayName("should take a ticket from another address once bind-address is off")
    void unbound() throws Exception {
      target = handoff(SECRET, false, "warp-2:25565");
      TransferHandoff.Issued issued = issue(true, "survival", "203.0.113.200");

      Client client = handedOff(issued);

      client.acknowledge();
      assertEquals("survival", proposed.get());
    }
  }

  @Nested
  @DisplayName("a ticket without its proof")
  class Proof {

    @Test
    @DisplayName(
        "should refuse a client whose proof is wrong: told to skip Mojang, it cannot log in")
    void wrongProof() throws Exception {
      TransferHandoff.Issued issued = issue(true, "survival");
      Client client = untilProof(issued);

      client.answer(TransferHandoff.PROOF_COOKIE, new byte[16]);

      client.next(LoginDisconnect.class);
      assertRefused(client);
    }

    @Test
    @DisplayName("should refuse a client that holds no proof")
    void noProof() throws Exception {
      Client client = untilProof(issue(true, "survival"));

      client.answer(TransferHandoff.PROOF_COOKIE, null);

      client.next(LoginDisconnect.class);
      assertRefused(client);
    }

    @Test
    @DisplayName("should refuse a client that answers with the proof of another ticket")
    void otherProof() throws Exception {
      TransferHandoff.Issued other = issue(true, "survival");
      Client client = untilProof(issue(true, "survival"));

      client.answer(TransferHandoff.PROOF_COOKIE, other.proof());

      client.next(LoginDisconnect.class);
      assertRefused(client);
    }

    @Test
    @DisplayName("should refuse a client that never answers, once the wait is over")
    void silent() throws Exception {
      Client client = untilProof(issue(true, "survival"));

      client.advance(ClientCookies.DEFAULT_TIMEOUT.plusMillis(1));

      client.next(LoginDisconnect.class);
      assertRefused(client);
    }

    private Client untilProof(TransferHandoff.Issued issued) throws Exception {
      Client client = transferLogin();
      client.next(CookieRequest.class);
      client.answer(TransferHandoff.TICKET_COOKIE, issued.ticket());
      client.encrypt(client.next(EncryptionRequest.class));
      assertEquals(TransferHandoff.PROOF_COOKIE, client.next(CookieRequest.class).key());
      return client;
    }

    private void assertRefused(Client client) throws Exception {
      client.channel.runPendingTasks();
      assertNull(context().playerRegistry().get("Steve"), "never registered");
      assertNull(proposed.get());
      verify(sessionService, never()).hasJoined(anyString(), anyString());
    }
  }

  @Nested
  @DisplayName("a transferred client without a valid ticket")
  class FallingBack {

    @Test
    @DisplayName("should authenticate a client that holds no ticket with Mojang")
    void absent() throws Exception {
      Client client = transferLogin();
      client.next(CookieRequest.class);

      client.answer(TransferHandoff.TICKET_COOKIE, null);

      assertFullLogin(client);
    }

    @Test
    @DisplayName("should take an emptied cookie for no ticket")
    void emptied() throws Exception {
      Client client = transferLogin();
      client.next(CookieRequest.class);

      client.answer(TransferHandoff.TICKET_COOKIE, new byte[0]);

      assertFullLogin(client);
    }

    @Test
    @DisplayName("should authenticate a client that never answers, once the wait is over")
    void silent() throws Exception {
      Client client = transferLogin();
      client.next(CookieRequest.class);

      client.advance(ClientCookies.DEFAULT_TIMEOUT.plusMillis(1));

      assertFullLogin(client);
    }

    @Test
    @DisplayName("should authenticate a client whose ticket expired")
    void expired() throws Exception {
      TransferHandoff.Issued issued = issue(true, "survival");
      millis.addAndGet(
          Duration.ofSeconds(15).plus(TransferHandoff.MAX_CLOCK_SKEW).plusMillis(1).toMillis());

      assertFullLogin(presenting(issued.ticket()));
    }

    @Test
    @DisplayName("should authenticate a client that replays a ticket already used")
    void replayed() throws Exception {
      TransferHandoff.Issued issued = issue(true, "survival");
      Client first = handedOff(issued);
      first.channel.close();
      first.channel.runPendingTasks();
      proposed.set(null);

      Client second = presenting(issued.ticket());

      assertFullLogin(second);
    }

    @Test
    @DisplayName("should authenticate a client whose ticket was changed")
    void tampered() throws Exception {
      byte[] ticket = issue(true, "survival").ticket();
      ticket[ticket.length / 2] ^= 0x40;

      assertFullLogin(presenting(ticket));
    }

    @Test
    @DisplayName("should authenticate a client whose ticket was sealed under another key")
    void foreignKey() throws Exception {
      TransferHandoff stranger =
          handoff("the-secret-of-another-network".getBytes(StandardCharsets.UTF_8), true, "evil");
      TransferHandoff.Issued forged = stranger.issue(subject(true, "survival", CLIENT), HOST, PORT);
      assertNotNull(forged);

      assertFullLogin(presenting(forged.ticket()));
    }

    @Test
    @DisplayName("should authenticate a client that sends garbage for a ticket")
    void garbage() throws Exception {
      assertFullLogin(presenting(new byte[5_120]));
    }

    @Test
    @DisplayName("should authenticate a client that presents a ticket from another address")
    void wrongAddress() throws Exception {
      TransferHandoff.Issued issued = issue(true, "survival", "203.0.113.200");

      assertFullLogin(presenting(issued.ticket()));
    }

    @Test
    @DisplayName("should authenticate a client whose ticket was written for another proxy address")
    void wrongTarget() throws Exception {
      TransferHandoff.Issued issued =
          source.issue(subject(true, "survival", CLIENT), "other.warp.dev", PORT);
      assertNotNull(issued);

      assertFullLogin(presenting(issued.ticket()));
    }

    @Test
    @DisplayName("should authenticate a client that presents the ticket of another player")
    void otherPlayer() throws Exception {
      TransferHandoff.Issued issued =
          source.issue(
              new TransferHandoff.Subject(
                  UUID.randomUUID(),
                  "Alex",
                  TICKET_TEXTURES,
                  MODERN.protocol(),
                  address(CLIENT),
                  "survival",
                  true,
                  true),
              HOST,
              PORT);
      assertNotNull(issued);

      assertFullLogin(presenting(issued.ticket()));
    }

    @Test
    @DisplayName("should authenticate a player the other proxy did not, and keep it on its server")
    void unauthenticatedTicket() throws Exception {
      TransferHandoff.Issued issued = issue(false, "survival");
      assertNull(issued.proof());
      Client client = presenting(issued.ticket());

      EncryptionRequest request = client.next(EncryptionRequest.class);
      assertTrue(request.shouldAuthenticate(), "an offline proxy vouches for nobody");
      client.encrypt(request);
      LoginSuccess success = client.await(LoginSuccess.class);

      assertEquals(MOJANG_TEXTURES, success.properties());
      verify(sessionService).hasJoined(eq("Steve"), anyString());
      client.acknowledge();
      assertEquals("survival", proposed.get());
    }

    /** The full login: Mojang asked, its profile used, the default server, no handoff. */
    private void assertFullLogin(Client client) throws Exception {
      EncryptionRequest request = client.next(EncryptionRequest.class);
      assertTrue(request.shouldAuthenticate(), "the client authenticates with Mojang");
      client.encrypt(request);
      LoginSuccess success = client.await(LoginSuccess.class);

      assertEquals(STEVE, success.uuid());
      assertEquals(MOJANG_TEXTURES, success.properties());
      verify(sessionService).hasJoined(eq("Steve"), anyString());
      client.acknowledge();
      assertEquals("lobby", proposed.get());
      ConnectedPlayer player = registered("Steve");
      assertFalse(player.isHandedOff());
      assertFalse(player.api().isHandedOff());
    }
  }

  @Nested
  @DisplayName("a login the handoff does not apply to")
  class NotApplicable {

    @Test
    @DisplayName("should not ask a client that connected directly for a ticket")
    void directLogin() throws Exception {
      Client client = login(HandshakeIntent.LOGIN, MODERN);

      EncryptionRequest request = client.next(EncryptionRequest.class);

      assertTrue(request.shouldAuthenticate());
    }

    @Test
    @DisplayName(
        "should not ask a client older than 1.20.5, which has neither cookies nor transfers")
    void oldClient() throws Exception {
      Client client = login(HandshakeIntent.LOGIN, ProtocolVersion.MINECRAFT_1_20_4);

      client.next(EncryptionRequest.class);
      client.channel.runPendingTasks();

      assertNull(client.channel.readOutbound(), "nothing else asked");
    }

    @Test
    @DisplayName("should not ask for a ticket while the handoff is off")
    void disabled() throws Exception {
      target = TransferHandoff.disabled();
      Client client = transferLogin();

      EncryptionRequest request = client.next(EncryptionRequest.class);

      assertTrue(request.shouldAuthenticate());
    }

    @Test
    @DisplayName("should not log an offline-mode proxy's player in as the ticket says")
    void offlineTarget() throws Exception {
      onlineMode = false;
      TransferHandoff.Issued issued = issue(true, "survival");
      Client client = presenting(issued.ticket());

      LoginSuccess success = client.next(LoginSuccess.class);

      // The offline identity, as for any login of this proxy: the ticket only names the server.
      assertEquals(
          UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(StandardCharsets.UTF_8)),
          success.uuid());
      assertEquals(List.of(), success.properties());
      client.acknowledge();
      assertEquals("survival", proposed.get());
      verify(sessionService, never()).hasJoined(anyString(), anyString());
    }
  }

  // ---------------------------------------------------------------------------
  // The proxy a player leaves
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("a transfer to a peer")
  class Leaving {

    private final List<SwitchHarness> harnesses = new ArrayList<>();

    @AfterEach
    void close() {
      harnesses.forEach(SwitchHarness::close);
    }

    @Test
    @DisplayName("should store the ticket and its proof ahead of the Transfer packet")
    void storesCookies() {
      SwitchHarness setup = playingOnLobby(true);

      setup.player.api().transfer(HOST, PORT);
      setup.runPendingTasks();

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(
          List.of(StoreCookie.class, StoreCookie.class, Transfer.class), SwitchHarness.types(sent));
      StoreCookie ticket = sent.get(0).as(StoreCookie.class);
      StoreCookie proof = sent.get(1).as(StoreCookie.class);
      assertEquals(TransferHandoff.TICKET_COOKIE, ticket.key());
      assertEquals(TransferHandoff.PROOF_COOKIE, proof.key());
      assertTrue(ticket.payload().length <= 5_120);
      TransferHandoff.Opening opening =
          target.open(
              ticket.payload(),
              new TransferHandoff.Arrival(
                  "Steve",
                  setup.player.uuid(),
                  MODERN.protocol(),
                  InetAddress.getLoopbackAddress(),
                  HOST,
                  PORT,
                  null));
      TransferHandoff.Opening.Accepted accepted = (TransferHandoff.Opening.Accepted) opening;
      assertEquals("lobby", accepted.ticket().server());
      assertEquals(setup.player.uuid(), accepted.ticket().uuid());
      assertTrue(accepted.ticket().authenticated());
      assertFalse(accepted.ticket().trusted(), "the anti-bot is off: it trusts nobody");
      assertTrue(target.proves(accepted.ticket(), proof.payload()));
    }

    @Test
    @DisplayName("should store nothing before a transfer to a server that is not a peer")
    void notAPeer() {
      SwitchHarness setup = playingOnLobby(true);

      setup.player.api().transfer("play.elsewhere.net", PORT);
      setup.player.api().transfer(HOST, PORT + 1);
      setup.runPendingTasks();

      assertEquals(
          List.of(Transfer.class, Transfer.class), SwitchHarness.types(setup.sentToClient()));
    }

    @Test
    @DisplayName("should store nothing while the handoff is off")
    void off() {
      SwitchHarness setup = playingOnLobby(true);
      when(setup.loginContext.handoff()).thenReturn(TransferHandoff.disabled());

      setup.player.api().transfer(HOST, PORT);
      setup.runPendingTasks();

      assertEquals(List.of(Transfer.class), SwitchHarness.types(setup.sentToClient()));
    }

    @Test
    @DisplayName("should vouch for no player it did not authenticate: a ticket without a proof")
    void offlinePlayer() {
      SwitchHarness setup = playingOnLobby(false);

      setup.player.api().transfer(HOST, PORT);
      setup.runPendingTasks();

      List<SwitchHarness.Sent> sent = setup.sentToClient();
      assertEquals(List.of(StoreCookie.class, Transfer.class), SwitchHarness.types(sent));
      TransferHandoff.Opening.Accepted accepted =
          (TransferHandoff.Opening.Accepted)
              target.open(
                  sent.get(0).as(StoreCookie.class).payload(),
                  new TransferHandoff.Arrival(
                      "Steve",
                      null,
                      MODERN.protocol(),
                      InetAddress.getLoopbackAddress(),
                      HOST,
                      PORT,
                      null));
      assertFalse(accepted.ticket().authenticated());
      assertFalse(accepted.ticket().trusted());
    }

    @Test
    @DisplayName("should store nothing for a Bedrock player, who logs in again through Floodgate")
    void bedrockPlayer() {
      SwitchHarness setup = playingOnLobby(false);
      BedrockIdentity identity = mock(BedrockIdentity.class);
      when(identity.platform()).thenReturn(mock(ClientPlatform.Bedrock.class));
      setup.client.setBedrock(identity);

      setup.player.api().transfer(HOST, PORT);
      setup.runPendingTasks();

      assertEquals(List.of(Transfer.class), SwitchHarness.types(setup.sentToClient()));
    }

    @Test
    @DisplayName("should write the profile the login authenticated, not what plugins made of it")
    void authenticatedProfile() {
      SwitchHarness setup = playingOnLobby(true);
      setup.player.authenticatedAs(new GameProfile(STEVE, "Steve", MOJANG_TEXTURES));

      setup.player.api().transfer(HOST, PORT);
      setup.runPendingTasks();

      TransferHandoff.Opening.Accepted accepted =
          (TransferHandoff.Opening.Accepted)
              target.open(
                  setup.sentToClient().get(0).as(StoreCookie.class).payload(),
                  new TransferHandoff.Arrival(
                      "Steve",
                      STEVE,
                      MODERN.protocol(),
                      InetAddress.getLoopbackAddress(),
                      HOST,
                      PORT,
                      null));
      assertEquals(STEVE, accepted.ticket().uuid());
      assertEquals(MOJANG_TEXTURES, accepted.ticket().properties());
    }

    @Test
    @DisplayName("should land the player on the peer with the cookies it stored, without Mojang")
    void endToEnd() throws Exception {
      SwitchHarness setup = playingOnLobby(true);
      setup.player.setCurrentServerName("survival");
      setup.player.authenticatedAs(new GameProfile(STEVE, "Steve", TICKET_TEXTURES));
      setup.player.api().transfer(HOST, PORT);
      setup.runPendingTasks();
      List<SwitchHarness.Sent> sent = setup.sentToClient();

      Client client = login(HandshakeIntent.TRANSFER, MODERN, "127.0.0.1");
      client.next(CookieRequest.class);
      client.answer(TransferHandoff.TICKET_COOKIE, sent.get(0).as(StoreCookie.class).payload());
      client.encrypt(client.next(EncryptionRequest.class));
      client.next(CookieRequest.class);
      client.answer(TransferHandoff.PROOF_COOKIE, sent.get(1).as(StoreCookie.class).payload());
      LoginSuccess success = client.next(LoginSuccess.class);

      assertEquals(STEVE, success.uuid());
      assertEquals(TICKET_TEXTURES, success.properties());
      client.acknowledge();
      assertEquals("survival", proposed.get());
      verify(sessionService, never()).hasJoined(anyString(), anyString());
    }

    /** A player of the leaving proxy, playing on its lobby. */
    private SwitchHarness playingOnLobby(boolean authenticated) {
      SwitchHarness setup = new SwitchHarness(MODERN, List.of(), ProtocolState.PLAY);
      harnesses.add(setup);
      when(setup.loginContext.handoff()).thenReturn(source);
      setup.player.setOnlineMode(authenticated);
      var lobby = setup.backend(ProtocolState.PLAY);
      setup.player.setBackendConnection(new BackendConnection(lobby, SwitchHarness.LOBBY));
      setup.player.setCurrentServerName(SwitchHarness.LOBBY.name());
      lobby.setSessionHandler(new BackendPlaySessionHandler(setup.player, lobby));
      setup.client.setSessionHandler(new ClientPlaySessionHandler(setup.player));
      return setup;
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers: tickets
  // ---------------------------------------------------------------------------

  private TransferHandoff.Issued issue(boolean authenticated, @Nullable String server) {
    return issue(authenticated, server, CLIENT);
  }

  private TransferHandoff.Issued issue(
      boolean authenticated, @Nullable String server, String from) {
    return Objects.requireNonNull(
        source.issue(subject(authenticated, server, from), HOST, PORT), "a ticket");
  }

  private static TransferHandoff.Subject subject(
      boolean authenticated, @Nullable String server, String from) {
    return new TransferHandoff.Subject(
        STEVE,
        "Steve",
        TICKET_TEXTURES,
        MODERN.protocol(),
        address(from),
        server,
        authenticated,
        authenticated);
  }

  // ---------------------------------------------------------------------------
  // Helpers: the client
  // ---------------------------------------------------------------------------

  private ServerLoginContext context() {
    return Objects.requireNonNull(context, "log in first");
  }

  private ConnectedPlayer registered(String name) {
    return Objects.requireNonNull(context().playerRegistry().get(name), name + " registered");
  }

  private Client transferLogin() {
    return login(HandshakeIntent.TRANSFER, MODERN);
  }

  private Client login(HandshakeIntent intent, ProtocolVersion version) {
    return login(intent, version, CLIENT);
  }

  private Client login(HandshakeIntent intent, ProtocolVersion version, String from) {
    ServerLoginContext reached =
        TestLoginContexts.acceptingHandoffs(onlineMode, sessionService, events, servers, target);
    this.context = reached;
    EmbeddedChannel channel =
        new ProxyProtocolTest.PeerChannel(new InetSocketAddress(address(from), 40000));
    channels.add(channel);
    channel.pipeline().addLast(new ServerChannelInitializer(reached));
    Client client = new Client(channel, version);
    client.write(handshake(version, intent));
    client.write(loginStart("Steve", STEVE));
    return client;
  }

  /** A transferred client that answers the ticket request with {@code ticket}. */
  private Client presenting(byte[] ticket) {
    Client client = transferLogin();
    assertEquals(TransferHandoff.TICKET_COOKIE, client.next(CookieRequest.class).key());
    client.answer(TransferHandoff.TICKET_COOKIE, ticket);
    return client;
  }

  /** A transferred client logged in with {@code issued}, up to its Login Success. */
  private Client handedOff(TransferHandoff.Issued issued) throws Exception {
    Client client = presenting(issued.ticket());
    EncryptionRequest request = client.next(EncryptionRequest.class);
    assertFalse(request.shouldAuthenticate());
    client.encrypt(request);
    client.next(CookieRequest.class);
    client.answer(TransferHandoff.PROOF_COOKIE, issued.proof());
    client.next(LoginSuccess.class);
    return client;
  }

  static ByteBuf handshake(ProtocolVersion version, HandshakeIntent intent) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    VarInt.write(body, version.protocol());
    McString.write(body, HOST);
    body.writeShort(PORT);
    VarInt.write(body, intent.id());
    return body;
  }

  static ByteBuf loginStart(String name, UUID uuid) {
    ByteBuf body = Unpooled.buffer();
    VarInt.write(body, 0x00);
    McString.write(body, name);
    McUuid.write(body, uuid);
    return body;
  }

  private static InetAddress address(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }

  /** A client of the proxy: frames what it sends, and encrypts both ways once told to. */
  static final class Client {

    final EmbeddedChannel channel;
    private final ProtocolVersion version;
    private final ByteBuf received = Unpooled.buffer();
    private @Nullable Cipher encryption;
    private @Nullable Cipher decryption;

    Client(EmbeddedChannel channel, ProtocolVersion version) {
      this.channel = channel;
      this.version = version;
    }

    /** Sends a packet's body, framed, encrypted once the connection is. */
    void write(ByteBuf body) {
      ByteBuf frame = Unpooled.buffer();
      VarInt.write(frame, body.readableBytes());
      frame.writeBytes(body);
      body.release();
      byte[] bytes = ByteBufUtil.getBytes(frame);
      frame.release();
      Cipher cipher = encryption;
      channel.writeInbound(Unpooled.wrappedBuffer(cipher == null ? bytes : cipher.update(bytes)));
      channel.runPendingTasks();
    }

    @SuppressWarnings("unchecked")
    <T extends Packet> void send(ProtocolState state, T packet) {
      PacketRegistry.Encoding encoding =
          StateRegistry.get(state, PacketDirection.SERVERBOUND)
              .encoding(version, packet.getClass());
      ByteBuf body = Unpooled.buffer();
      VarInt.write(body, encoding.packetId());
      ((PacketCodec<T>) encoding.codec()).encode(packet, body, version);
      write(body);
    }

    /** Answers a cookie request of the login state. */
    void answer(String key, byte @Nullable [] payload) {
      send(ProtocolState.LOGIN, new CookieResponse(key, payload));
    }

    /** Answers the encryption request as a vanilla client does, and encrypts from then on. */
    void encrypt(EncryptionRequest request) throws GeneralSecurityException {
      byte[] secret = new byte[16];
      RANDOM.nextBytes(secret);
      send(
          ProtocolState.LOGIN,
          new EncryptionResponse(
              ConnectionLimitsTest.rsaEncrypt(request, secret),
              new EncryptionResponse.EncryptedToken(
                  ConnectionLimitsTest.rsaEncrypt(request, request.verifyToken()))));
      SecretKeySpec key = new SecretKeySpec(secret, "AES");
      Cipher out = Cipher.getInstance("AES/CFB8/NoPadding");
      out.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(secret));
      Cipher in = Cipher.getInstance("AES/CFB8/NoPadding");
      in.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(secret));
      this.encryption = out;
      this.decryption = in;
    }

    /** Acknowledges Login Success: the client reads configuration packets from then on. */
    void acknowledge() {
      send(ProtocolState.LOGIN, new LoginAcknowledged());
    }

    void advance(Duration duration) {
      channel.advanceTimeBy(duration.toMillis(), TimeUnit.MILLISECONDS);
      channel.runScheduledPendingTasks();
      channel.runPendingTasks();
    }

    /** The next packet the proxy sent in the login state, which must be a {@code type}. */
    <T extends Packet> T next(Class<T> type) {
      return next(ProtocolState.LOGIN, type);
    }

    <T extends Packet> T next(ProtocolState state, Class<T> type) {
      T packet = poll(state, type);
      assertNotNull(packet, "expected a " + type.getSimpleName());
      return packet;
    }

    /** Waits for what a virtual thread hands back to the event loop: Mojang's answer. */
    <T extends Packet> T await(Class<T> type) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      for (; ; ) {
        T packet = poll(ProtocolState.LOGIN, type);
        if (packet != null) {
          return packet;
        }
        assertTrue(System.nanoTime() < deadline, "no " + type.getSimpleName() + " in time");
        Thread.sleep(5);
      }
    }

    @SuppressWarnings("unchecked")
    private <T extends Packet> @Nullable T poll(ProtocolState state, Class<T> type) {
      channel.runPendingTasks();
      for (ByteBuf out; (out = channel.readOutbound()) != null; ) {
        byte[] bytes = ByteBufUtil.getBytes(out);
        out.release();
        Cipher cipher = decryption;
        received.writeBytes(cipher == null ? bytes : cipher.update(bytes));
      }
      if (!received.isReadable()) {
        return null;
      }
      int length = VarInt.read(received);
      ByteBuf packet = received.readRetainedSlice(length);
      try {
        PacketRegistry registry = StateRegistry.get(state, PacketDirection.CLIENTBOUND);
        int id = VarInt.read(packet);
        assertEquals(
            registry.packetId(version, type),
            id,
            type.getSimpleName() + " packet id (got " + id + ")");
        T decoded =
            ((PacketCodec<T>) registry.encoding(version, type).codec()).decode(packet, version);
        assertEquals(0, packet.readableBytes(), "bytes left after " + type.getSimpleName());
        return decoded;
      } finally {
        packet.release();
      }
    }
  }

  @Test
  @DisplayName("should keep the two cookies under names of the proxy's own")
  void cookieNames() {
    assertEquals("warp:handoff", TransferHandoff.TICKET_COOKIE);
    assertEquals("warp:handoff_proof", TransferHandoff.PROOF_COOKIE);
  }
}
