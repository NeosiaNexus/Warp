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
package dev.warp.proxy.antibot;

import dev.warp.api.antibot.VerificationFailure;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.config.ClientInformation;
import dev.warp.protocol.packet.play.KeepAlive;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

import org.jspecify.annotations.Nullable;

/**
 * The probe of an unknown identity (stage S3 of the anti-bot research, section 9.7): what the proxy
 * asks a client as it logs in, and what it makes of the answers. A pure state machine, fed by a
 * session handler with what the client sends and with the passing of time; it sends nothing and
 * knows no channel, so that every rule can be tested on its own.
 *
 * <p><b>Configuration probe</b> (1.20.2+), in the client's first configuration phase, before any
 * backend exists: a {@linkplain #pingId() ping} and a {@linkplain #keepAliveId() keep-alive} with
 * random ids to echo; from 1.20.5 a {@linkplain #cookiePayload() cookie} stored then asked back.
 * The client also sends, of its own, its brand and its settings on entering the phase. Nothing is
 * offered that a server offers too: a Select Known Packs of the proxy's own would spend the one
 * answer some client libraries give per configuration phase (node-minecraft-protocol answers the
 * first offer only), and the server's offer after it would wait forever.
 *
 * <p><b>Pre-join probe</b> (1.8 to 1.20.1), after Login Success and before the first Join Game: one
 * keep-alive with a random id to echo, which every client of those versions answers while it waits
 * for its world. Nothing else is asked: the brand and settings of those versions follow Join Game.
 *
 * <p><b>Verdicts</b> follow section 10 of the research. The probe fails only on protocol
 * impossibilities: an echoed id the proxy never sent, a packet illegal in the state, a cookie that
 * comes back changed, or no echo and no settings at all before the deadline. Everything else is a
 * {@link Flag}: counted, reported with the pass, and never a kick on its own. A client whose
 * answers raise two flags or more passes but is not trusted: it is probed again next time.
 *
 * <p><b>Deadlines</b> follow the measured round trip (rule 3): the first answer gets {@code
 * probe-timeout}; once the ping is answered, every later one gets {@code probe-timeout} plus four
 * round trips, within {@value #MAX_SECONDS} s in all. One exception: the cookie answer, which is
 * optional (its absence is a flag) and follows the keep-alive echo at once in a client that gives
 * it, since the request was flushed right after the keep-alive and clients answer in order. Once
 * the keep-alive is echoed, the probe waits for the cookie only {@link #COOKIE_GRACE} or four round
 * trips, whichever is longer: a client without cookies (client libraries, most of all) is let in
 * without paying the whole {@code probe-timeout}.
 *
 * <p>Not thread-safe: a probe belongs to one connection's event loop.
 */
public final class Probe {

  /** The most a whole probe may last, whatever the round trip. */
  public static final int MAX_SECONDS = 30;

  /** The key of the cookie the configuration probe stores and asks back. */
  public static final String COOKIE_KEY = "warp:probe";

  /**
   * How long the probe waits for the cookie answer after the keep-alive echo, at least: a client
   * that answers cookies does so right after the keep-alive, so a longer wait only delays the
   * clients that have none, flagged {@link Flag#COOKIE_MISSING} and let in.
   */
  public static final Duration COOKIE_GRACE = Duration.ofSeconds(1);

  /** The size of the random cookie payload. */
  static final int COOKIE_BYTES = 16;

  /** What a probe asks of a client. */
  public enum Kind {
    /** The configuration-phase probe of 1.20.2 and newer clients. */
    CONFIGURATION,
    /** The keep-alive probe of 1.8 to 1.20.1 clients, before their first Join Game. */
    PRE_JOIN
  }

  /**
   * What the client did that a vanilla client does not do, without being impossible: recorded,
   * never a kick on its own (section 10 of the research).
   */
  public enum Flag {
    /** No brand before the probe ended: some privacy mods strip it. */
    BRAND_MISSING,
    /** A brand message that is not one printable string of at most 128 bytes. */
    BRAND_INVALID,
    /** A view distance under 2 chunks, which the vanilla client cannot set. */
    VIEW_DISTANCE_LOW,
    /** No answer to the cookie request before the probe ended. */
    COOKIE_MISSING,
    /** The answers came in another order than the requests. */
    ANSWERS_OUT_OF_ORDER
  }

  /** Where the probe stands. */
  public sealed interface Outcome {

    /** Still waiting for the client. */
    record Pending() implements Outcome {}

    /**
     * The client answered as a real client does.
     *
     * @param roundTripNanos the measured round trip, from the ping (or the keep-alive before
     *     1.20.2); 0 if none was measured
     * @param flags what it did that a vanilla client does not do
     */
    record Passed(long roundTripNanos, Set<Flag> flags) implements Outcome {
      /**
       * Copies the flags.
       *
       * @param roundTripNanos the measured round trip
       * @param flags the flags
       */
      public Passed {
        flags = flags.isEmpty() ? EnumSet.noneOf(Flag.class) : EnumSet.copyOf(flags);
      }

      /**
       * Returns whether the client is trusted from this pass: fewer than two flags.
       *
       * @return {@code true} to store the identity as verified
       */
      public boolean trusted() {
        return flags.size() < 2;
      }
    }

    /**
     * The client did what no real client does, or answered nothing.
     *
     * @param reason the bounded reason, for metrics and the client's message
     * @param detail the exact packet sequence or missing answer, for the debug log
     */
    record Failed(VerificationFailure reason, String detail) implements Outcome {}
  }

  private final Kind kind;
  private final ProtocolVersion version;
  private final LongSupplier nanoClock;
  private final long baseTimeoutNanos;
  private final long startedNanos;

  private final int pingId;
  private final long keepAliveId;
  private final byte @Nullable [] cookiePayload;

  private boolean pongAnswered;
  private boolean keepAliveAnswered;
  private boolean cookieAnswered;
  private boolean settingsReceived;
  private boolean brandReceived;
  private long roundTripNanos;
  private long deadlineNanos;
  private int answers;
  private int unknownPackets;
  private final EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
  private Outcome outcome = new Outcome.Pending();

  /**
   * Starts a probe, drawing its ids now.
   *
   * @param kind what to ask
   * @param version the client's version, which decides what exists to ask
   * @param baseTimeout the deadline of the first answer, and the base of the later ones
   * @param random the source of the ids and the cookie
   * @param nanoClock the monotonic clock
   */
  public Probe(
      Kind kind,
      ProtocolVersion version,
      Duration baseTimeout,
      RandomGenerator random,
      LongSupplier nanoClock) {
    if (kind == Kind.CONFIGURATION && !version.supportsConfigurationState()) {
      throw new IllegalArgumentException(version + " has no configuration state");
    }
    this.kind = kind;
    this.version = version;
    this.nanoClock = nanoClock;
    this.baseTimeoutNanos = baseTimeout.toNanos();
    this.startedNanos = nanoClock.getAsLong();
    this.deadlineNanos = startedNanos + baseTimeoutNanos;
    this.pingId = random.nextInt();
    // The id of a keep-alive is a long from 1.12.2 and a non-negative int before.
    this.keepAliveId =
        KeepAlive.hasLongId(version) ? random.nextLong() : random.nextInt() & Integer.MAX_VALUE;
    boolean cookies =
        kind == Kind.CONFIGURATION && version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5);
    if (cookies) {
      byte[] payload = new byte[COOKIE_BYTES];
      random.nextBytes(payload);
      this.cookiePayload = payload;
    } else {
      this.cookiePayload = null;
    }
  }

  // ---------------------------------------------------------------------------
  // What to send
  // ---------------------------------------------------------------------------

  /**
   * Returns what the probe asks.
   *
   * @return the kind
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Returns the id of the ping to send (configuration probe).
   *
   * @return a random int
   */
  public int pingId() {
    return pingId;
  }

  /**
   * Returns the id of the keep-alive to send.
   *
   * @return a random id the client's version carries without loss
   */
  public long keepAliveId() {
    return keepAliveId;
  }

  /**
   * Returns the cookie to store under {@link #COOKIE_KEY} and ask back, if the client's version has
   * cookies (1.20.5+).
   *
   * @return the payload, or {@code null} when no cookie is asked
   */
  public byte @Nullable [] cookiePayload() {
    return cookiePayload == null ? null : cookiePayload.clone();
  }

  /**
   * Returns when the probe next gives up waiting, on the probe's clock.
   *
   * @return the deadline, in nanoseconds of the clock
   */
  public long deadlineNanos() {
    return deadlineNanos;
  }

  /**
   * Returns where the probe stands.
   *
   * @return the outcome so far
   */
  public Outcome outcome() {
    return outcome;
  }

  /**
   * Returns how many frames the probe did not understand and ignored (rule 4: counted, never a
   * silent close).
   *
   * @return the count
   */
  public int unknownPackets() {
    return unknownPackets;
  }

  // ---------------------------------------------------------------------------
  // What the client sends
  // ---------------------------------------------------------------------------

  /**
   * Takes the client's pong (configuration probe).
   *
   * @param id the id echoed
   * @return the outcome
   */
  public Outcome pong(int id) {
    if (done()) {
      return outcome;
    }
    if (kind != Kind.CONFIGURATION || pongAnswered) {
      return fail(VerificationFailure.ILLEGAL_PACKET, "a pong to no ping");
    }
    if (id != pingId) {
      return fail(VerificationFailure.WRONG_ECHO, "pong " + id + " to ping " + pingId);
    }
    pongAnswered = true;
    measureRoundTrip();
    answered(0);
    return evaluate(false);
  }

  /**
   * Takes the client's keep-alive answer.
   *
   * @param id the id echoed
   * @return the outcome
   */
  public Outcome keepAlive(long id) {
    if (done()) {
      return outcome;
    }
    if (keepAliveAnswered) {
      return fail(VerificationFailure.ILLEGAL_PACKET, "a second keep-alive answer");
    }
    if (id != keepAliveId) {
      return fail(VerificationFailure.WRONG_ECHO, "keep-alive " + id + " to " + keepAliveId);
    }
    keepAliveAnswered = true;
    if (kind == Kind.PRE_JOIN) {
      measureRoundTrip();
    }
    answered(1);
    if (cookiePayload != null && !cookieAnswered) {
      // The cookie answer, if it comes, is right behind: wait one grace for it, not the whole
      // base deadline (the deadline only moves closer, and the cap holds).
      long grace = Math.max(COOKIE_GRACE.toNanos(), 4 * roundTripNanos);
      deadlineNanos = Math.min(deadlineNanos, nanoClock.getAsLong() + grace);
    }
    return evaluate(false);
  }

  /**
   * Takes the client's answer to the cookie request (1.20.5+).
   *
   * @param payload the cookie the client has under {@link #COOKIE_KEY}, empty if none
   * @return the outcome
   */
  public Outcome cookie(Optional<byte[]> payload) {
    if (done()) {
      return outcome;
    }
    byte[] expected = cookiePayload;
    if (expected == null || cookieAnswered) {
      return fail(VerificationFailure.ILLEGAL_PACKET, "a cookie to no request");
    }
    cookieAnswered = true;
    if (payload.isPresent()) {
      if (!Arrays.equals(expected, payload.get())) {
        return fail(VerificationFailure.COOKIE_MISMATCH, "the cookie came back changed");
      }
    } else {
      flags.add(Flag.COOKIE_MISSING);
    }
    answered(2);
    return evaluate(false);
  }

  /**
   * Takes the client's settings.
   *
   * @param settings its client information
   * @return the outcome
   */
  public Outcome settings(ClientInformation settings) {
    if (done()) {
      return outcome;
    }
    settingsReceived = true;
    if (settings.viewDistance() < 2) {
      flags.add(Flag.VIEW_DISTANCE_LOW);
    }
    return evaluate(false);
  }

  /**
   * Takes the client's brand.
   *
   * @param brand the brand, or {@code null} if its brand message was not a valid one
   * @return the outcome
   */
  public Outcome brand(@Nullable String brand) {
    if (done()) {
      return outcome;
    }
    brandReceived = true;
    if (brand == null) {
      flags.add(Flag.BRAND_INVALID);
    }
    return evaluate(false);
  }

  /**
   * Takes a packet the client may not send in its state: an acknowledgement of a configuration the
   * proxy never finished, an answer to nothing.
   *
   * @param what the packet, for the debug log
   * @return the outcome, failed
   */
  public Outcome illegalPacket(String what) {
    if (done()) {
      return outcome;
    }
    return fail(VerificationFailure.ILLEGAL_PACKET, what + " in the probe");
  }

  /** Counts a frame the proxy does not read, which is ignored. */
  public void unknownPacket() {
    unknownPackets++;
  }

  /**
   * Takes the passing of the deadline: called when the clock reaches {@link #deadlineNanos()}.
   *
   * @return the outcome: passed if the echoes and the settings arrived, failed otherwise
   */
  public Outcome deadline() {
    if (done()) {
      return outcome;
    }
    if (nanoClock.getAsLong() - deadlineNanos < 0) {
      return outcome; // not yet: the deadline moved when the ping was answered
    }
    return evaluate(true);
  }

  /**
   * Returns whether the client left before the probe ended: the caller's business, recorded here so
   * that the outcome is final.
   */
  boolean done() {
    return !(outcome instanceof Outcome.Pending);
  }

  // ---------------------------------------------------------------------------
  // Rules
  // ---------------------------------------------------------------------------

  private void measureRoundTrip() {
    long now = nanoClock.getAsLong();
    roundTripNanos = Math.max(0, now - startedNanos);
    // Every later answer gets the base deadline plus four round trips, within the cap.
    long cap = startedNanos + TimeUnit.SECONDS.toNanos(MAX_SECONDS);
    deadlineNanos = Math.min(cap, now + baseTimeoutNanos + 4 * roundTripNanos);
  }

  /** Records that the answer at {@code position} of the requests arrived, in order or not. */
  private void answered(int position) {
    if (position < answers) {
      flags.add(Flag.ANSWERS_OUT_OF_ORDER);
    }
    answers = Math.max(answers, position + 1);
  }

  /**
   * Decides. Every answer the client gives comes in the order the requests were sent, so the last
   * one asked (the cookie, or the keep-alive before 1.20.5) closes the probe: what is still missing
   * then will not come. Before that, the probe waits for the deadline.
   */
  private Outcome evaluate(boolean atDeadline) {
    boolean echoes = keepAliveAnswered && (kind == Kind.PRE_JOIN || pongAnswered);
    boolean lastAnswered = cookiePayload != null ? cookieAnswered : keepAliveAnswered;
    boolean settings = kind == Kind.PRE_JOIN || settingsReceived;
    if (echoes && settings && (lastAnswered || atDeadline)) {
      if (kind == Kind.CONFIGURATION) {
        if (!brandReceived) {
          flags.add(Flag.BRAND_MISSING);
        }
        if (cookiePayload != null && !cookieAnswered) {
          flags.add(Flag.COOKIE_MISSING);
        }
      }
      outcome = new Outcome.Passed(roundTripNanos, flags);
      return outcome;
    }
    if (atDeadline) {
      List<String> missing = new ArrayList<>(3);
      if (kind == Kind.CONFIGURATION && !pongAnswered) {
        missing.add("the pong");
      }
      if (!keepAliveAnswered) {
        missing.add("the keep-alive");
      }
      if (!settings) {
        missing.add("the client information");
      }
      return fail(VerificationFailure.TIMED_OUT, "no " + String.join(", ", missing));
    }
    return outcome;
  }

  private Outcome fail(VerificationFailure reason, String detail) {
    outcome = new Outcome.Failed(reason, detail);
    return outcome;
  }

  @Override
  public String toString() {
    return "Probe[" + kind + ", " + version.name() + ", " + outcome + "]";
  }
}
