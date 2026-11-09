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

import dev.warp.protocol.packet.login.LoginSuccess;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.config.HandoffConfig;
import dev.warp.proxy.connection.LogThrottle;
import dev.warp.proxy.logging.LogCode;
import dev.warp.proxy.server.HostRoutes;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The seamless handoff of a player between Warp proxies.
 *
 * <p>Before a transfer to a peer, the proxy stores two cookies in the player's client (1.20.5+):
 * the sealed {@value #TICKET_COOKIE} ticket ({@link HandoffTicket}: the player's Mojang profile,
 * the server it is on, whether the anti-bot trusts it, the transfer's address, a single-use nonce,
 * an expiry), and the {@value #PROOF_COOKIE} proof, a random secret the ticket also holds. The
 * proxy the client lands on asks for the ticket before encryption, as it must to decide whether the
 * client authenticates with Mojang; if the ticket opens and fits the login, it tells the client not
 * to ({@code shouldAuthenticate = false}), still sets up encryption, then asks for the proof over
 * the encrypted connection and logs the player in with the ticket's profile, skipping both Mojang
 * round trips. Either way the player goes to the ticket's server first.
 *
 * <p><b>Security.</b> The ticket travels in clear on the second hop, so it alone grants nothing:
 * the proof never does (stored and read over encrypted connections), and a ticket without its proof
 * is refused. A ticket is single-use on each proxy, expires within {@code max-age}, and is bound to
 * the transfer's address. It is written only for transfers to peers ({@code drain.transfer-to},
 * {@code transfers.handoff.peers}): any other server a player is sent to never holds one. What
 * skipping Mojang gives up is Mojang's protection against an active man in the middle on that one
 * hop, the trade-off {@code shouldAuthenticate} exists for; {@code transfers.handoff.enabled =
 * false} restores full authentication.
 *
 * <p>Thread-safe: tickets are written and read on every event loop.
 */
public final class TransferHandoff {

  private static final Logger logger = LoggerFactory.getLogger(TransferHandoff.class);

  /** The cookie the sealed ticket travels in. */
  public static final String TICKET_COOKIE = "warp:handoff";

  /** The cookie the proof travels in, stored and read over encrypted connections only. */
  public static final String PROOF_COOKIE = "warp:handoff_proof";

  /** How far a ticket's times may be off this proxy's clock: the skew tolerated between proxies. */
  public static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(5);

  /** The largest cookie a client keeps: 5 KiB. */
  static final int MAX_COOKIE = 5 * 1_024;

  /** How many tickets the proxy remembers as used at once. */
  static final int SINGLE_USE_CAPACITY = 100_000;

  static final int PROOF_LENGTH = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  /** Warnings that peers seal with another key, or transfer to another address: once a minute. */
  private static final LogThrottle MISMATCH_WARNINGS = new LogThrottle(Duration.ofMinutes(1));

  private final @Nullable HandoffSeal seal;
  private final Duration maxAge;
  private final Set<String> peers;
  private final String nodeId;
  private final LongSupplier millisClock;
  private final SingleUse used = new SingleUse(SINGLE_USE_CAPACITY);

  private TransferHandoff(
      @Nullable HandoffSeal seal,
      Duration maxAge,
      Set<String> peers,
      String nodeId,
      LongSupplier millisClock) {
    this.seal = seal;
    this.maxAge = maxAge;
    this.peers = Set.copyOf(peers);
    this.nodeId = nodeId;
    this.millisClock = millisClock;
  }

  /**
   * The handoff turned off: no ticket written, none read.
   *
   * @return the disabled handoff
   */
  public static TransferHandoff disabled() {
    return new TransferHandoff(null, HandoffConfig.DEFAULTS.maxAge(), Set.of(), "", () -> 0L);
  }

  /**
   * Loads the handoff as configured: its key from {@value HandoffConfig#ENV_SECRET} or the key file
   * (generated when absent, logged once at INFO), and its peers.
   *
   * @param config the {@code transfers.handoff} section
   * @param drainTarget {@code drain.transfer-to}, a peer, or {@code null}
   * @param directory the proxy's directory, which a relative {@code secret-file} is resolved in
   * @param env the environment
   * @param nodeId what names this proxy in the tickets it writes, for the logs of their readers
   * @param millisClock the wall clock
   * @return the handoff, disabled if the configuration says so
   * @throws IOException if the key file cannot be read or created
   */
  public static TransferHandoff load(
      HandoffConfig config,
      DrainConfig.@Nullable Target drainTarget,
      Path directory,
      UnaryOperator<@Nullable String> env,
      String nodeId,
      LongSupplier millisClock)
      throws IOException {
    if (!config.enabled()) {
      return disabled();
    }
    Path file = directory.resolve(config.secretFile());
    HandoffSeal seal = HandoffSeal.load(file, env.apply(HandoffConfig.ENV_SECRET));
    if (seal.generated()) {
      logger.info(
          "Transfers: generated the handoff key in {}. Only this proxy accepts its tickets until"
              + " the others share it (copy the file, or set {} on each)",
          file,
          HandoffConfig.ENV_SECRET);
    }
    Set<String> peers = new HashSet<>();
    if (drainTarget != null) {
      peers.add(peerKey(drainTarget.host(), drainTarget.port()));
    }
    for (DrainConfig.Target peer : config.peers()) {
      peers.add(peerKey(peer.host(), peer.port()));
    }
    return new TransferHandoff(seal, config.maxAge(), peers, nodeId, millisClock);
  }

  /**
   * A handoff under {@code secret}, for tests.
   *
   * @param secret the shared secret
   * @param maxAge how long tickets stay valid
   * @param peers the peers, as {@code host:port}
   * @param nodeId this proxy's name in the tickets
   * @param millisClock the wall clock
   * @return the handoff
   */
  public static TransferHandoff of(
      byte[] secret, Duration maxAge, List<String> peers, String nodeId, LongSupplier millisClock) {
    Set<String> keys = new HashSet<>();
    for (String peer : peers) {
      int colon = peer.lastIndexOf(':');
      keys.add(peerKey(peer.substring(0, colon), Integer.parseInt(peer.substring(colon + 1))));
    }
    return new TransferHandoff(HandoffSeal.of(secret), maxAge, keys, nodeId, millisClock);
  }

  /**
   * Returns whether tickets are written and read.
   *
   * @return {@code false} when {@code transfers.handoff.enabled} is off
   */
  public boolean enabled() {
    return seal != null;
  }

  /**
   * Returns whether a transfer to {@code host:port} carries a ticket: the address is a peer's.
   *
   * @param host the transfer's host, as the Transfer packet carries it
   * @param port the transfer's port
   * @return {@code true} for {@code drain.transfer-to} and the listed peers
   */
  public boolean isPeer(String host, int port) {
    return enabled() && peers.contains(peerKey(host, port));
  }

  private static String peerKey(String host, int port) {
    return HostRoutes.clean(host) + "|" + port;
  }

  // ---------------------------------------------------------------------------
  // The source: writing a ticket
  // ---------------------------------------------------------------------------

  /**
   * Who a ticket is written for.
   *
   * @param uuid the player's UUID
   * @param name the player's name
   * @param properties the profile's properties, as Mojang signed them
   * @param server the server the player is on, or {@code null}
   * @param authenticated whether the player was authenticated with Mojang, here or by the handoff
   *     it arrived with; also whether its connection is encrypted, which the proof requires
   * @param trusted whether the anti-bot trusts the account
   */
  public record Subject(
      UUID uuid,
      String name,
      List<LoginSuccess.Property> properties,
      @Nullable String server,
      boolean authenticated,
      boolean trusted) {}

  /**
   * The cookies to store before a transfer.
   *
   * @param ticket the sealed ticket, for {@value #TICKET_COOKIE}
   * @param proof the proof, for {@value #PROOF_COOKIE}; {@code null} for a player not
   *     authenticated, whose connection is not encrypted and whose ticket skips nothing
   */
  @SuppressWarnings("ArrayRecordComponent") // fresh arrays, handed over once
  public record Issued(byte[] ticket, byte @Nullable [] proof) {}

  /**
   * Writes a ticket for a transfer to {@code host:port}. Callable for any target: deciding which
   * targets get one is {@link #isPeer}'s business, and the caller's.
   *
   * @param subject the player
   * @param host the transfer's host
   * @param port the transfer's port
   * @return the cookies, or {@code null} if the handoff is off or the ticket would not fit in a
   *     cookie
   */
  public @Nullable Issued issue(Subject subject, String host, int port) {
    HandoffSeal sealing = seal;
    if (sealing == null) {
      return null;
    }
    long now = millisClock.getAsLong();
    byte[] nonce = new byte[16];
    RANDOM.nextBytes(nonce);
    ByteBuffer halves = ByteBuffer.wrap(nonce);
    byte[] proof = new byte[subject.authenticated() ? PROOF_LENGTH : 0];
    RANDOM.nextBytes(proof);
    List<LoginSuccess.Property> properties = subject.properties();
    if (properties.size() > HandoffTicket.MAX_PROPERTIES) {
      return null;
    }
    HandoffTicket ticket =
        new HandoffTicket(
            subject.uuid(),
            subject.name(),
            properties,
            subject.server(),
            nodeId,
            now,
            now + maxAge.toMillis(),
            subject.authenticated(),
            subject.trusted(),
            HostRoutes.clean(host),
            port,
            halves.getLong(),
            halves.getLong(),
            proof);
    byte[] sealed;
    try {
      sealed = sealing.seal(ticket.encode());
    } catch (java.io.UncheckedIOException | IllegalArgumentException tooLong) {
      return null; // a string past modified UTF-8's 64 KiB: no ticket rather than a broken one
    }
    if (sealed.length > MAX_COOKIE) {
      logger.debug(
          "No handoff for {}: its ticket ({} bytes) would not fit in a cookie",
          subject.name(),
          sealed.length);
      return null;
    }
    return new Issued(sealed, proof.length == 0 ? null : proof);
  }

  // ---------------------------------------------------------------------------
  // The target: reading a ticket
  // ---------------------------------------------------------------------------

  /**
   * The login a ticket is checked against.
   *
   * @param name the name of its Login Start
   * @param uuid the UUID of its Login Start, or {@code null} if it carried none
   * @param handshakeHost the host its handshake named, cleaned, or {@code null}
   * @param handshakePort the port its handshake named
   * @param localAddress the address the client reached, which a transfer to an IP address matches
   *     when the client's handshake names the host differently (vanilla clients send a name looked
   *     up from the address), or {@code null}
   */
  public record Arrival(
      String name,
      @Nullable UUID uuid,
      @Nullable String handshakeHost,
      int handshakePort,
      @Nullable InetSocketAddress localAddress) {}

  /** What a ticket gave. */
  public sealed interface Opening {
    /**
     * A valid ticket, now spent.
     *
     * @param ticket what it says
     */
    record Accepted(HandoffTicket ticket) implements Opening {}

    /**
     * A ticket not used.
     *
     * @param reason why
     */
    record Rejected(HandoffRejection reason) implements Opening {}
  }

  /**
   * Opens a transferred client's ticket and checks it against its login, then spends it: a ticket
   * is used once on this proxy. Checked in that order, so that a ticket refused for another reason
   * is not spent: version, key, seal, content, times, address, name, UUID (for an authenticated
   * ticket: offline, the client's UUID is its own), single use.
   *
   * @param payload the cookie's payload
   * @param arrival the login
   * @return the ticket, or why it is not used
   */
  public Opening open(byte[] payload, Arrival arrival) {
    HandoffSeal sealing = seal;
    if (sealing == null) {
      throw new IllegalStateException("the handoff is off (transfers.handoff.enabled = false)");
    }
    HandoffTicket ticket;
    switch (sealing.open(payload)) {
      case HandoffSeal.Opened.Refused(HandoffRejection reason) -> {
        return rejected(reason, arrival);
      }
      case HandoffSeal.Opened.Body(byte[] plaintext) -> {
        HandoffTicket decoded = HandoffTicket.decode(plaintext);
        if (decoded == null) {
          return rejected(HandoffRejection.MALFORMED, arrival);
        }
        ticket = decoded;
      }
    }
    long now = millisClock.getAsLong();
    long skew = MAX_CLOCK_SKEW.toMillis();
    long expiresAt =
        Math.min(
            ticket.expiresAtMillis(),
            ticket.issuedAtMillis() + HandoffConfig.MAX_MAX_AGE.toMillis());
    if (now < ticket.issuedAtMillis() - skew) {
      return rejected(HandoffRejection.CLOCK, arrival);
    }
    if (now > expiresAt + skew) {
      return rejected(HandoffRejection.EXPIRED, arrival);
    }
    if (!targets(ticket, arrival)) {
      return rejected(HandoffRejection.WRONG_TARGET, arrival);
    }
    if (!ticket.name().equals(arrival.name())) {
      return rejected(HandoffRejection.NAME_MISMATCH, arrival);
    }
    UUID claimed = arrival.uuid();
    if (ticket.authenticated() && claimed != null && !claimed.equals(ticket.uuid())) {
      return rejected(HandoffRejection.UUID_MISMATCH, arrival);
    }
    return switch (used.use(ticket.nonceHigh(), ticket.nonceLow(), expiresAt + skew, now)) {
      case FIRST -> new Opening.Accepted(ticket);
      case REPLAYED -> rejected(HandoffRejection.REPLAYED, arrival);
      case FULL -> rejected(HandoffRejection.REPLAY_CACHE_FULL, arrival);
    };
  }

  /**
   * Checks the proof a client returned over its encrypted connection, in constant time.
   *
   * @param ticket the ticket it presented
   * @param proof the {@value #PROOF_COOKIE} cookie's payload, or {@code null} if it held none
   * @return {@code true} if the proof is the ticket's
   */
  public boolean proves(HandoffTicket ticket, byte @Nullable [] proof) {
    return proof != null && ticket.proofIs(proof);
  }

  /**
   * Whether the ticket names the address the client connected to: its handshake's host and port,
   * or, for a transfer to an IP address, the address the connection reached.
   */
  private static boolean targets(HandoffTicket ticket, Arrival arrival) {
    if (ticket.targetPort() == arrival.handshakePort()
        && ticket.targetHost().equals(arrival.handshakeHost())) {
      return true;
    }
    InetSocketAddress local = arrival.localAddress();
    if (local == null || local.getPort() != ticket.targetPort()) {
      return false;
    }
    try {
      return InetAddress.ofLiteral(ticket.targetHost()).equals(local.getAddress());
    } catch (IllegalArgumentException notAnAddress) {
      return false;
    }
  }

  private Opening rejected(HandoffRejection reason, Arrival arrival) {
    if (reason == HandoffRejection.FOREIGN_KEY || reason == HandoffRejection.WRONG_TARGET) {
      // Peers misconfigured, not a client's doing: worth a line, once a minute at most.
      long more = MISMATCH_WARNINGS.tryAcquire();
      if (more >= 0) {
        logger.warn(
            LogCode.HANDOFF_REJECTED.marker(),
            "Transfers: the handoff of {} was not used ({}); it logged in through Mojang{}",
            arrival.name(),
            reason == HandoffRejection.FOREIGN_KEY
                ? "sealed under another key: give every proxy the same transfers.handoff key"
                : "written for another address than the one it connected to: set the peer's"
                    + " address as clients reach it",
            LogThrottle.suffix(more, "handoffs"));
      }
    }
    return new Opening.Rejected(reason);
  }
}
