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
package dev.warp.proxy.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.config.HandoffConfig;
import dev.warp.proxy.transfer.TransferHandoff.Arrival;
import dev.warp.proxy.transfer.TransferHandoff.Issued;
import dev.warp.proxy.transfer.TransferHandoff.Subject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("TransferHandoff")
class TransferHandoffTest {

  private static final byte[] SECRET =
      "a-shared-secret-of-the-network".getBytes(StandardCharsets.UTF_8);
  private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
  private static final UUID ALEX = UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce");
  private static final String HOST = "play.warp.dev";
  private static final int PORT = 25565;
  private static final int PROTOCOL = 769;
  private static final Duration MAX_AGE = Duration.ofSeconds(15);
  private static final long SKEW = TransferHandoff.MAX_CLOCK_SKEW.toMillis();
  private static final List<LoginSuccess.Property> TEXTURES =
      List.of(new LoginSuccess.Property("textures", "dmFsdWU=", "c2lnbmF0dXJl"));

  private final AtomicLong millis = new AtomicLong(1_800_000_000_000L);
  private final TransferHandoff source = handoff(true, "warp-1:25565");
  private final TransferHandoff target = handoff(true, "warp-2:25565");

  private TransferHandoff handoff(boolean bindAddress, String nodeId) {
    return TransferHandoff.of(
        SECRET, MAX_AGE, bindAddress, List.of(HOST + ":" + PORT), nodeId, millis::get);
  }

  private static InetAddress address(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }

  private static Subject steve(boolean authenticated) {
    return new Subject(
        STEVE,
        "Steve",
        TEXTURES,
        PROTOCOL,
        address("203.0.113.9"),
        "survival",
        authenticated,
        authenticated);
  }

  private static Arrival arrival() {
    return arrival("Steve", STEVE, PROTOCOL, "203.0.113.9", HOST, PORT);
  }

  private static Arrival arrival(
      String name,
      @Nullable UUID uuid,
      int protocol,
      @Nullable String from,
      @Nullable String host,
      int port) {
    return new Arrival(
        name,
        uuid,
        protocol,
        from == null ? null : address(from),
        host,
        port,
        new InetSocketAddress(address("192.0.2.1"), 25565));
  }

  private Issued issued() {
    Issued issued = source.issue(steve(true), HOST, PORT);
    assertNotNull(issued);
    return issued;
  }

  private static HandoffTicket accepted(TransferHandoff.Opening opening) {
    return assertInstanceOf(TransferHandoff.Opening.Accepted.class, opening).ticket();
  }

  private static HandoffRejection rejected(TransferHandoff.Opening opening) {
    return assertInstanceOf(TransferHandoff.Opening.Rejected.class, opening).reason();
  }

  /** A ticket sealed under the network's key with whatever a proxy would never write. */
  private byte[] sealed(long issuedAt, long expiresAt, long nonce) {
    HandoffTicket ticket =
        new HandoffTicket(
            STEVE,
            "Steve",
            TEXTURES,
            PROTOCOL,
            address("203.0.113.9"),
            "survival",
            "warp-1:25565",
            issuedAt,
            expiresAt,
            true,
            true,
            HOST,
            PORT,
            nonce,
            nonce,
            new byte[16]);
    return HandoffSeal.of(SECRET).seal(ticket.encode());
  }

  @Nested
  @DisplayName("turned off")
  class Disabled {

    @Test
    @DisplayName("should write no ticket, know no peer, and refuse to open one")
    void disabled() {
      TransferHandoff off = TransferHandoff.disabled();

      assertFalse(off.enabled());
      assertFalse(off.isPeer(HOST, PORT));
      assertNull(off.issue(steve(true), HOST, PORT));
      assertThrows(IllegalStateException.class, () -> off.open(issued().ticket(), arrival()));
    }
  }

  @Nested
  @DisplayName("knowing its peers")
  class Peers {

    @Test
    @DisplayName("should know a peer by its host and port, as handshakes name hosts")
    void peers() {
      assertTrue(source.enabled());
      assertTrue(source.isPeer(HOST, PORT));
      assertTrue(source.isPeer("PLAY.Warp.dev.", PORT));
      assertFalse(source.isPeer(HOST, PORT + 1));
      assertFalse(source.isPeer("evil.example.com", PORT));
      assertFalse(source.isPeer("play.warp.dev.evil.example.com", PORT));
    }

    @Test
    @DisplayName("should not take a host and port that only concatenate like a peer's")
    void noConcatenation() {
      TransferHandoff handoff =
          TransferHandoff.of(SECRET, MAX_AGE, true, List.of("host:12"), "n", millis::get);

      assertTrue(handoff.isPeer("host", 12));
      assertFalse(handoff.isPeer("host1", 2));
      assertFalse(handoff.isPeer("host|12", 12));
    }
  }

  @Nested
  @DisplayName("loading as configured")
  class Loading {

    @TempDir private Path directory;

    @Test
    @DisplayName("should stay off, and create no key file, unless turned on")
    void offByDefault() throws Exception {
      TransferHandoff loaded =
          TransferHandoff.load(
              HandoffConfig.DEFAULTS,
              new DrainConfig.Target(HOST, PORT),
              directory,
              _ -> "a-shared-secret-of-the-network",
              new byte[0],
              "n",
              millis::get);

      assertFalse(loaded.enabled());
      assertFalse(loaded.isPeer(HOST, PORT));
      try (var files = Files.list(directory)) {
        assertEquals(0, files.count());
      }
    }

    @Test
    @DisplayName("should take the drain's target and the listed addresses for peers")
    void peers() throws Exception {
      HandoffConfig config =
          new HandoffConfig(
              true,
              "keys/transfer.secret",
              MAX_AGE,
              true,
              List.of(new DrainConfig.Target("eu.warp.dev", 25570)));

      TransferHandoff loaded =
          TransferHandoff.load(
              config,
              new DrainConfig.Target(HOST, PORT),
              directory,
              _ -> null,
              new byte[0],
              "n",
              millis::get);

      assertTrue(loaded.enabled());
      assertTrue(loaded.isPeer(HOST, PORT));
      assertTrue(loaded.isPeer("eu.warp.dev", 25570));
      assertFalse(loaded.isPeer("eu.warp.dev", PORT));
      assertTrue(Files.exists(directory.resolve("keys").resolve("transfer.secret")));
    }

    @Test
    @DisplayName("should have no peer without a drain target nor a list, and still read tickets")
    void noPeers() throws Exception {
      HandoffConfig config = new HandoffConfig(true, "transfer.secret", MAX_AGE, true, List.of());

      TransferHandoff loaded =
          TransferHandoff.load(
              config,
              null,
              directory,
              name ->
                  name.equals(HandoffConfig.ENV_VARIABLE) ? "a-shared-secret-of-the-network" : null,
              new byte[0],
              "n",
              millis::get);
      millis.addAndGet(SKEW);

      assertFalse(loaded.isPeer(HOST, PORT));
      assertFalse(Files.exists(directory.resolve("transfer.secret")));
      assertEquals("survival", accepted(loaded.open(issued().ticket(), arrival())).server());
    }

    @Test
    @DisplayName("should apply the configured max-age and address binding")
    void settings() throws Exception {
      HandoffConfig config =
          new HandoffConfig(true, "transfer.secret", Duration.ofSeconds(2), false, List.of());
      TransferHandoff loaded =
          TransferHandoff.load(
              config,
              new DrainConfig.Target(HOST, PORT),
              directory,
              _ -> "a-shared-secret-of-the-network",
              "a-forwarding-secret".getBytes(StandardCharsets.UTF_8),
              "warp-3",
              millis::get);
      millis.addAndGet(SKEW);

      Issued issued = loaded.issue(steve(true), HOST, PORT);
      assertNotNull(issued);
      HandoffTicket ticket =
          accepted(
              loaded.open(
                  issued.ticket(), arrival("Steve", STEVE, PROTOCOL, "198.51.100.1", HOST, PORT)));

      assertEquals(2_000, ticket.expiresAtMillis() - ticket.issuedAtMillis());
      assertEquals("warp-3", ticket.sourceNode());
    }

    private TransferHandoff loaded(@Nullable String secret, String forwardingSecret)
        throws IOException {
      return TransferHandoff.load(
          new HandoffConfig(true, "transfer.secret", MAX_AGE, true, List.of()),
          new DrainConfig.Target(HOST, PORT),
          directory,
          _ -> secret,
          forwardingSecret.getBytes(StandardCharsets.UTF_8),
          "warp-2",
          millis::get);
    }

    @Test
    @DisplayName("should refuse a ticket written before it started: it may have been used before")
    void predatesStart() throws Exception {
      byte[] before = issued().ticket();
      TransferHandoff restarted = loaded(new String(SECRET, StandardCharsets.UTF_8), "");

      millis.addAndGet(SKEW - 1);
      byte[] withinSkew = issued().ticket();
      millis.addAndGet(1);
      byte[] after = issued().ticket();

      assertEquals(HandoffRejection.PREDATES_START, rejected(restarted.open(before, arrival())));
      assertEquals(
          HandoffRejection.PREDATES_START, rejected(restarted.open(withinSkew, arrival())));
      assertEquals("survival", accepted(restarted.open(after, arrival())).server());
    }

    @Test
    @DisplayName("should refuse to seal under the forwarding secret, from the environment")
    void forwardingSecretFromEnvironment() {
      IOException refused =
          assertThrows(
              IOException.class,
              () -> loaded("the-secret-backends-hold", "the-secret-backends-hold"));

      String message = String.valueOf(refused.getMessage());
      assertTrue(message.contains("forwarding secret"), message);
      assertFalse(message.contains("the-secret-backends-hold"), message);
    }

    @Test
    @DisplayName("should refuse to seal under the forwarding secret, from the key file")
    void forwardingSecretFromFile() throws Exception {
      byte[] forwarding = "the-secret-backends-hold".getBytes(StandardCharsets.UTF_8);
      Files.writeString(
          directory.resolve("transfer.secret"), Base64.getEncoder().encodeToString(forwarding));

      assertThrows(IOException.class, () -> loaded(null, "the-secret-backends-hold"));
    }

    @Test
    @DisplayName("should seal under a secret that is not the forwarding secret")
    void anotherSecret() throws Exception {
      assertTrue(loaded("a-shared-secret-of-the-network", "the-secret-backends-hold").enabled());
      assertTrue(loaded("a-shared-secret-of-the-network", "").enabled());
    }
  }

  @Nested
  @DisplayName("writing a ticket")
  class Issuing {

    @Test
    @DisplayName("should write what the player is, where it plays and what the transfer targets")
    void content() {
      Issued issued = issued();

      HandoffTicket ticket = accepted(target.open(issued.ticket(), arrival()));

      assertEquals(STEVE, ticket.uuid());
      assertEquals("Steve", ticket.name());
      assertEquals(TEXTURES, ticket.properties());
      assertEquals(PROTOCOL, ticket.protocol());
      assertEquals(address("203.0.113.9"), ticket.clientAddress());
      assertEquals("survival", ticket.server());
      assertEquals("warp-1:25565", ticket.sourceNode());
      assertEquals(millis.get(), ticket.issuedAtMillis());
      assertEquals(millis.get() + MAX_AGE.toMillis(), ticket.expiresAtMillis());
      assertTrue(ticket.authenticated());
      assertTrue(ticket.trusted());
      assertEquals(HOST, ticket.targetHost());
      assertEquals(PORT, ticket.targetPort());
    }

    @Test
    @DisplayName(
        "should give an authenticated player a proof of 16 random bytes, in the ticket too")
    void proof() {
      Issued first = issued();
      Issued second = issued();
      byte[] proof = first.proof();

      assertNotNull(proof);
      assertEquals(TransferHandoff.PROOF_LENGTH, proof.length);
      assertFalse(Arrays.equals(proof, second.proof()));
      HandoffTicket ticket = accepted(target.open(first.ticket(), arrival()));
      assertTrue(target.proves(ticket, proof));
      assertFalse(target.proves(ticket, second.proof()));
      assertFalse(target.proves(ticket, null));
      assertFalse(target.proves(ticket, new byte[TransferHandoff.PROOF_LENGTH]));
    }

    @Test
    @DisplayName("should give a player it did not authenticate no proof, and say so in the ticket")
    void unauthenticated() {
      Issued issued = source.issue(steve(false), HOST, PORT);

      assertNotNull(issued);
      assertNull(issued.proof());
      HandoffTicket ticket = accepted(target.open(issued.ticket(), arrival()));
      assertFalse(ticket.authenticated());
      assertFalse(ticket.trusted());
      assertEquals(0, ticket.proof().length);
      assertFalse(target.proves(ticket, new byte[0]));
    }

    @Test
    @DisplayName("should give every ticket a nonce of its own")
    void nonces() {
      HandoffTicket first = accepted(target.open(issued().ticket(), arrival()));
      HandoffTicket second = accepted(target.open(issued().ticket(), arrival()));

      assertFalse(first.nonceHigh() == second.nonceHigh() && first.nonceLow() == second.nonceLow());
      assertFalse(first.nonceHigh() == first.nonceLow());
    }

    @Test
    @DisplayName("should write the target as handshakes name it: lowercase, without a final dot")
    void cleansTarget() {
      Issued issued = source.issue(steve(true), "Play.WARP.dev.", PORT);

      assertNotNull(issued);
      assertEquals(HOST, accepted(target.open(issued.ticket(), arrival())).targetHost());
    }

    @Test
    @DisplayName("should fit Mojang's signed textures in a cookie")
    void signedTextures() {
      List<LoginSuccess.Property> textures =
          List.of(new LoginSuccess.Property("textures", "v".repeat(2_500), "s".repeat(684)));

      Issued issued =
          source.issue(
              new Subject(STEVE, "Steve", textures, PROTOCOL, null, "survival", true, true),
              HOST,
              PORT);

      assertNotNull(issued);
      assertTrue(issued.ticket().length <= TransferHandoff.MAX_COOKIE);
    }

    @Test
    @DisplayName("should write no ticket rather than one a cookie cannot hold")
    void tooLarge() {
      Subject exact = sized(TransferHandoff.MAX_COOKIE);
      Subject over = sized(TransferHandoff.MAX_COOKIE + 1);

      Issued fits = source.issue(exact, HOST, PORT);

      assertNotNull(fits);
      assertEquals(TransferHandoff.MAX_COOKIE, fits.ticket().length);
      assertNull(source.issue(over, HOST, PORT));
      assertNull(
          source.issue(
              new Subject(
                  STEVE,
                  "Steve",
                  List.of(new LoginSuccess.Property("textures", "v".repeat(70_000), null)),
                  PROTOCOL,
                  null,
                  null,
                  true,
                  true),
              HOST,
              PORT));
    }

    /** A subject whose sealed ticket is exactly {@code size} bytes. */
    private Subject sized(int size) {
      Subject empty = withValue("");
      Issued issued = source.issue(empty, HOST, PORT);
      assertNotNull(issued);
      return withValue("v".repeat(size - issued.ticket().length));
    }

    private Subject withValue(String value) {
      return new Subject(
          STEVE,
          "Steve",
          List.of(new LoginSuccess.Property("textures", value, null)),
          PROTOCOL,
          null,
          null,
          true,
          true);
    }

    @Test
    @DisplayName("should write no ticket for more properties than one carries")
    void tooManyProperties() {
      List<LoginSuccess.Property> properties = new ArrayList<>();
      for (int i = 0; i < HandoffTicket.MAX_PROPERTIES; i++) {
        properties.add(new LoginSuccess.Property("p" + i, "v", null));
      }
      Subject most = new Subject(STEVE, "Steve", properties, PROTOCOL, null, null, true, true);
      assertNotNull(source.issue(most, HOST, PORT));

      properties.add(new LoginSuccess.Property("one-more", "v", null));
      Subject over = new Subject(STEVE, "Steve", properties, PROTOCOL, null, null, true, true);

      assertNull(source.issue(over, HOST, PORT));
    }
  }

  @Nested
  @DisplayName("reading a ticket")
  class Opening {

    @Test
    @DisplayName("should refuse a ticket sealed under another key")
    void foreignKey() {
      TransferHandoff stranger =
          TransferHandoff.of(
              "the-secret-of-another-network".getBytes(StandardCharsets.UTF_8),
              MAX_AGE,
              true,
              List.of(HOST + ":" + PORT),
              "n",
              millis::get);
      Issued forged = stranger.issue(steve(true), HOST, PORT);
      assertNotNull(forged);

      assertEquals(HandoffRejection.FOREIGN_KEY, rejected(target.open(forged.ticket(), arrival())));
    }

    @Test
    @DisplayName("should refuse a ticket changed since it was sealed")
    void tampered() {
      byte[] ticket = issued().ticket();
      ticket[ticket.length - 20] ^= 1;

      assertEquals(HandoffRejection.TAMPERED, rejected(target.open(ticket, arrival())));
    }

    @Test
    @DisplayName("should refuse what is not a ticket: too short, another format, or sealed garbage")
    void malformed() {
      byte[] newer = issued().ticket();
      newer[0] = 2;
      byte[] garbage = HandoffSeal.of(SECRET).seal(new byte[] {1, 2, 3});

      assertEquals(HandoffRejection.MALFORMED, rejected(target.open(new byte[5], arrival())));
      assertEquals(HandoffRejection.UNKNOWN_VERSION, rejected(target.open(newer, arrival())));
      assertEquals(HandoffRejection.MALFORMED, rejected(target.open(garbage, arrival())));
    }

    @Test
    @DisplayName("should take a ticket until its expiry plus the clock skew, and not after")
    void expiry() {
      byte[] first = issued().ticket();
      byte[] second = issued().ticket();

      millis.addAndGet(MAX_AGE.toMillis() + SKEW);
      assertEquals("survival", accepted(target.open(first, arrival())).server());
      millis.addAndGet(1);
      assertEquals(HandoffRejection.EXPIRED, rejected(target.open(second, arrival())));
    }

    @Test
    @DisplayName("should cap the lifetime a ticket claims at the longest max-age")
    void lifetimeCapped() {
      long now = millis.get();
      long longest = HandoffConfig.MAX_MAX_AGE.toMillis();
      byte[] eternal = sealed(now, Long.MAX_VALUE, 1);
      byte[] other = sealed(now, Long.MAX_VALUE, 2);
      byte[] shortLived = sealed(now, now + 1_000, 3);

      millis.set(now + longest + SKEW);
      assertEquals("survival", accepted(target.open(eternal, arrival())).server());
      millis.set(now + longest + SKEW + 1);
      assertEquals(HandoffRejection.EXPIRED, rejected(target.open(other, arrival())));
      millis.set(now + 1_000 + SKEW + 1);
      assertEquals(HandoffRejection.EXPIRED, rejected(target.open(shortLived, arrival())));
    }

    @Test
    @DisplayName("should take a ticket written by a clock ahead within the skew, and not beyond")
    void clockAhead() {
      long now = millis.get();
      byte[] within = sealed(now + SKEW, now + SKEW + 15_000, 1);
      byte[] beyond = sealed(now + SKEW + 1, now + SKEW + 15_000, 2);

      assertEquals("survival", accepted(target.open(within, arrival())).server());
      assertEquals(HandoffRejection.CLOCK, rejected(target.open(beyond, arrival())));
    }

    @Test
    @DisplayName("should refuse a ticket written for another host or port than the handshake's")
    void wrongTarget() {
      byte[] ticket = issued().ticket();

      assertEquals(
          HandoffRejection.WRONG_TARGET,
          rejected(
              target.open(
                  ticket, arrival("Steve", STEVE, PROTOCOL, "203.0.113.9", "other.dev", PORT))));
      assertEquals(
          HandoffRejection.WRONG_TARGET,
          rejected(
              target.open(ticket, arrival("Steve", STEVE, PROTOCOL, "203.0.113.9", HOST, 25566))));
      assertEquals(
          HandoffRejection.WRONG_TARGET,
          rejected(
              target.open(ticket, arrival("Steve", STEVE, PROTOCOL, "203.0.113.9", null, PORT))));
      assertEquals("survival", accepted(target.open(ticket, arrival())).server());
    }

    @Test
    @DisplayName("should take a transfer to an IP address by the address the client reached")
    void literalTarget() {
      TransferHandoff toAddress =
          TransferHandoff.of(SECRET, MAX_AGE, true, List.of("192.0.2.1:25565"), "n", millis::get);
      Issued first = toAddress.issue(steve(true), "192.0.2.1", 25565);
      Issued second = toAddress.issue(steve(true), "192.0.2.1", 25565);
      Issued third = toAddress.issue(steve(true), "192.0.2.2", 25565);
      Issued fourth = toAddress.issue(steve(true), "192.0.2.1", 25570);
      assertNotNull(first);
      assertNotNull(second);
      assertNotNull(third);
      assertNotNull(fourth);
      // A vanilla client names the host it looked up from the address: not the address.
      Arrival named = arrival("Steve", STEVE, PROTOCOL, "203.0.113.9", "some.name", 25565);
      Arrival nowhere =
          new Arrival("Steve", STEVE, PROTOCOL, address("203.0.113.9"), "some.name", 25565, null);

      assertEquals(HandoffRejection.WRONG_TARGET, rejected(target.open(first.ticket(), nowhere)));
      assertEquals("survival", accepted(target.open(first.ticket(), named)).server());
      assertEquals(
          HandoffRejection.WRONG_TARGET,
          rejected(
              target.open(
                  second.ticket(),
                  new Arrival(
                      "Steve",
                      STEVE,
                      PROTOCOL,
                      address("203.0.113.9"),
                      "some.name",
                      25565,
                      new InetSocketAddress(address("192.0.2.1"), 25570)))));
      assertEquals(HandoffRejection.WRONG_TARGET, rejected(target.open(third.ticket(), named)));
      assertEquals(HandoffRejection.WRONG_TARGET, rejected(target.open(fourth.ticket(), named)));
    }

    @Test
    @DisplayName("should refuse a ticket written for a client of another protocol version")
    void wrongProtocol() {
      assertEquals(
          HandoffRejection.WRONG_PROTOCOL,
          rejected(
              target.open(
                  issued().ticket(), arrival("Steve", STEVE, 770, "203.0.113.9", HOST, PORT))));
    }

    @Test
    @DisplayName("should refuse a ticket presented from another address than it was written for")
    void wrongAddress() {
      assertEquals(
          HandoffRejection.ADDRESS_MISMATCH,
          rejected(
              target.open(
                  issued().ticket(),
                  arrival("Steve", STEVE, PROTOCOL, "198.51.100.66", HOST, PORT))));
      assertEquals(
          HandoffRejection.ADDRESS_MISMATCH,
          rejected(
              target.open(issued().ticket(), arrival("Steve", STEVE, PROTOCOL, null, HOST, PORT))));
    }

    @Test
    @DisplayName("should refuse a ticket written without an address, when bound to addresses")
    void noAddressWritten() {
      Issued issued =
          source.issue(
              new Subject(STEVE, "Steve", TEXTURES, PROTOCOL, null, "survival", true, true),
              HOST,
              PORT);
      assertNotNull(issued);

      assertEquals(
          HandoffRejection.ADDRESS_MISMATCH, rejected(target.open(issued.ticket(), arrival())));
      assertEquals(
          HandoffRejection.ADDRESS_MISMATCH,
          rejected(
              target.open(issued.ticket(), arrival("Steve", STEVE, PROTOCOL, null, HOST, PORT))));
    }

    @Test
    @DisplayName("should take a ticket from any address once bind-address is off")
    void unbound() {
      TransferHandoff unbound = handoff(false, "warp-3:25565");

      assertEquals(
          "survival",
          accepted(
                  unbound.open(
                      issued().ticket(),
                      arrival("Steve", STEVE, PROTOCOL, "198.51.100.66", HOST, PORT)))
              .server());
      assertEquals(
          "survival",
          accepted(
                  unbound.open(
                      issued().ticket(), arrival("Steve", STEVE, PROTOCOL, null, HOST, PORT)))
              .server());
    }

    @Test
    @DisplayName("should refuse a ticket written for another name, even in another case")
    void wrongName() {
      assertEquals(
          HandoffRejection.NAME_MISMATCH,
          rejected(
              target.open(
                  issued().ticket(), arrival("Alex", STEVE, PROTOCOL, "203.0.113.9", HOST, PORT))));
      assertEquals(
          HandoffRejection.NAME_MISMATCH,
          rejected(
              target.open(
                  issued().ticket(),
                  arrival("steve", STEVE, PROTOCOL, "203.0.113.9", HOST, PORT))));
    }

    @Test
    @DisplayName("should refuse an authenticated ticket presented under another UUID")
    void wrongUuid() {
      assertEquals(
          HandoffRejection.UUID_MISMATCH,
          rejected(
              target.open(
                  issued().ticket(), arrival("Steve", ALEX, PROTOCOL, "203.0.113.9", HOST, PORT))));
    }

    @Test
    @DisplayName("should not compare UUIDs for a login that sent none, nor for an offline ticket")
    void uuidNotCompared() {
      Issued offline = source.issue(steve(false), HOST, PORT);
      assertNotNull(offline);

      assertEquals(
          STEVE,
          accepted(
                  target.open(
                      issued().ticket(),
                      arrival("Steve", null, PROTOCOL, "203.0.113.9", HOST, PORT)))
              .uuid());
      assertEquals(
          STEVE,
          accepted(
                  target.open(
                      offline.ticket(),
                      arrival("Steve", ALEX, PROTOCOL, "203.0.113.9", HOST, PORT)))
              .uuid());
    }

    @Test
    @DisplayName("should take a ticket once: its second use is a replay")
    void replayed() {
      byte[] ticket = issued().ticket();

      assertEquals("survival", accepted(target.open(ticket, arrival())).server());
      assertEquals(HandoffRejection.REPLAYED, rejected(target.open(ticket, arrival())));
      assertEquals(HandoffRejection.REPLAYED, rejected(target.open(ticket.clone(), arrival())));
    }

    @Test
    @DisplayName("should remember a used ticket until it could no longer be used anyway")
    void rememberedUntilExpired() {
      byte[] ticket = issued().ticket();
      target.open(ticket, arrival());

      millis.addAndGet(MAX_AGE.toMillis() + SKEW);
      assertEquals(HandoffRejection.REPLAYED, rejected(target.open(ticket, arrival())));
      millis.addAndGet(1);
      assertEquals(HandoffRejection.EXPIRED, rejected(target.open(ticket, arrival())));
    }

    @Test
    @DisplayName("should not spend a ticket it refused: its player can still use it")
    void refusalDoesNotSpend() {
      byte[] ticket = issued().ticket();

      rejected(target.open(ticket, arrival("Steve", STEVE, PROTOCOL, "198.51.100.66", HOST, PORT)));
      rejected(target.open(ticket, arrival("Alex", STEVE, PROTOCOL, "203.0.113.9", HOST, PORT)));
      rejected(target.open(ticket, arrival("Steve", ALEX, PROTOCOL, "203.0.113.9", HOST, PORT)));
      rejected(target.open(ticket, arrival("Steve", STEVE, 770, "203.0.113.9", HOST, PORT)));
      rejected(target.open(ticket, arrival("Steve", STEVE, PROTOCOL, "203.0.113.9", "x", PORT)));

      assertEquals("survival", accepted(target.open(ticket, arrival())).server());
    }

    @Test
    @DisplayName("should let each proxy spend a ticket of its own accord")
    void perProxy() {
      byte[] ticket = issued().ticket();
      TransferHandoff other = handoff(true, "warp-3:25565");

      assertEquals("survival", accepted(target.open(ticket, arrival())).server());
      assertEquals("survival", accepted(other.open(ticket, arrival())).server());
    }

    @Test
    @DisplayName("should check the proof of the ticket, and of no other")
    void proves() {
      Issued issued = issued();
      HandoffTicket ticket = accepted(target.open(issued.ticket(), arrival()));

      assertTrue(target.proves(ticket, issued.proof()));
      assertFalse(target.proves(ticket, null));
      assertFalse(target.proves(ticket, new byte[0]));
      assertArrayEquals(issued.proof(), ticket.proof());
    }
  }
}
