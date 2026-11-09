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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.antibot.VerificationFailure;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.config.ClientInformation;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The probe's rules, one answer at a time, on a clock of their own. */
@DisplayName("Probe")
class ProbeTest {

  private static final ProtocolVersion MODERN = ProtocolVersion.MINECRAFT_1_21_4;
  private static final ProtocolVersion FIRST_CONFIGURATION = ProtocolVersion.MINECRAFT_1_20_2;
  private static final Duration BASE = Duration.ofSeconds(5);
  private static final ClientInformation VANILLA_SETTINGS =
      new ClientInformation("en_us", (byte) 12, 0, true, (byte) 0x7f, 1, false, true, 0);

  private final AtomicLong nanos = new AtomicLong(TimeUnit.HOURS.toNanos(1));
  private final Random random = new Random(42);

  private Probe configuration(ProtocolVersion version) {
    return new Probe(Probe.Kind.CONFIGURATION, version, BASE, random, nanos::get);
  }

  private Probe preJoin(ProtocolVersion version) {
    return new Probe(Probe.Kind.PRE_JOIN, version, BASE, random, nanos::get);
  }

  private static byte[] cookieOf(Probe probe) {
    return Objects.requireNonNull(probe.cookiePayload(), "a cookie is asked from 1.20.5");
  }

  /** What a vanilla client sends on entering the configuration phase, in order. */
  private static Probe.Outcome vanillaAnswers(Probe probe, ProtocolVersion version) {
    probe.settings(VANILLA_SETTINGS);
    probe.brand("vanilla");
    probe.pong(probe.pingId());
    Probe.Outcome outcome = probe.keepAlive(probe.keepAliveId());
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
      byte[] cookie = probe.cookiePayload();
      assertNotNull(cookie);
      outcome = probe.cookie(Optional.of(cookie));
    }
    return outcome;
  }

  // ---------------------------------------------------------------------------
  // What a vanilla client does
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("a vanilla client")
  class VanillaClient {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"1.20.2", "1.20.4", "1.20.5", "1.21.4", "26.3"})
    @DisplayName("should pass with no flag, trusted, once its last answer arrives")
    void passes(String name) {
      ProtocolVersion version =
          ProtocolVersion.values().stream().filter(v -> v.name().equals(name)).findFirst().get();
      Probe probe = configuration(version);

      Probe.Outcome outcome = vanillaAnswers(probe, version);

      Probe.Outcome.Passed passed = assertInstanceOf(Probe.Outcome.Passed.class, outcome);
      assertEquals(Set.of(), passed.flags());
      assertTrue(passed.trusted());
    }

    @Test
    @DisplayName("should ask no cookie before 1.20.5")
    void nothingToAskBefore1205() {
      Probe probe = configuration(FIRST_CONFIGURATION);

      assertNull(probe.cookiePayload());
    }

    @Test
    @DisplayName("should measure the round trip on the pong and move the deadline after it")
    void roundTrip() {
      Probe probe = configuration(MODERN);
      long start = nanos.get();
      assertEquals(start + BASE.toNanos(), probe.deadlineNanos());

      nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
      probe.pong(probe.pingId());

      long rtt = TimeUnit.MILLISECONDS.toNanos(250);
      assertEquals(nanos.get() + BASE.toNanos() + 4 * rtt, probe.deadlineNanos());
      probe.settings(VANILLA_SETTINGS);
      probe.keepAlive(probe.keepAliveId());
      Probe.Outcome.Passed passed =
          assertInstanceOf(Probe.Outcome.Passed.class, probe.cookie(Optional.of(cookieOf(probe))));
      assertEquals(rtt, passed.roundTripNanos());
    }

    @Test
    @DisplayName("should never let the deadline pass 30 seconds, whatever the round trip")
    void deadlineCapped() {
      Probe probe = configuration(MODERN);
      long start = nanos.get();

      nanos.addAndGet(TimeUnit.SECONDS.toNanos(7)); // 7 s + 5 s + 4 x 7 s would be 40 s
      probe.pong(probe.pingId());

      assertEquals(start + TimeUnit.SECONDS.toNanos(Probe.MAX_SECONDS), probe.deadlineNanos());
    }

    @Test
    @DisplayName("should ignore and count frames it does not read")
    void unknownPacketsCounted() {
      Probe probe = configuration(MODERN);

      probe.unknownPacket();
      probe.unknownPacket();

      assertEquals(2, probe.unknownPackets());
      assertInstanceOf(Probe.Outcome.Pending.class, probe.outcome());
    }
  }

  // ---------------------------------------------------------------------------
  // Hard verdicts: protocol impossibilities only
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("a client without cookies")
  class NoCookies {

    @Test
    @DisplayName(
        "should wait one grace for the cookie after the keep-alive echo, not the whole timeout")
    void cookieGrace() {
      Probe probe = configuration(MODERN);
      probe.settings(VANILLA_SETTINGS);
      probe.brand("node-minecraft-protocol");
      nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
      probe.pong(probe.pingId());
      long afterPong = probe.deadlineNanos();
      nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));

      Probe.Outcome outcome = probe.keepAlive(probe.keepAliveId());

      assertInstanceOf(Probe.Outcome.Pending.class, outcome);
      assertEquals(nanos.get() + Probe.COOKIE_GRACE.toNanos(), probe.deadlineNanos());
      assertTrue(probe.deadlineNanos() < afterPong, "moved closer");
      nanos.set(probe.deadlineNanos() - 1);
      assertInstanceOf(Probe.Outcome.Pending.class, probe.deadline());
      nanos.set(probe.deadlineNanos());
      Probe.Outcome.Passed passed = assertInstanceOf(Probe.Outcome.Passed.class, probe.deadline());
      assertEquals(Set.of(Probe.Flag.COOKIE_MISSING), passed.flags());
      assertTrue(passed.trusted());
    }

    @Test
    @DisplayName("should give the cookie four round trips when the connection is slow")
    void cookieGraceFollowsRoundTrip() {
      Probe probe = configuration(MODERN);
      probe.settings(VANILLA_SETTINGS);
      long rtt = TimeUnit.MILLISECONDS.toNanos(400);
      nanos.addAndGet(rtt);
      probe.pong(probe.pingId());

      probe.keepAlive(probe.keepAliveId());

      assertEquals(nanos.get() + 4 * rtt, probe.deadlineNanos());
    }

    @Test
    @DisplayName("should move no deadline before 1.20.5, where no cookie is asked")
    void noGraceWithoutCookie() {
      Probe probe = configuration(FIRST_CONFIGURATION);
      nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
      probe.pong(probe.pingId());
      long afterPong = probe.deadlineNanos();

      Probe.Outcome outcome = probe.keepAlive(probe.keepAliveId());

      assertInstanceOf(Probe.Outcome.Pending.class, outcome, "its settings are still to come");
      assertEquals(afterPong, probe.deadlineNanos());
    }
  }

  @Nested
  @DisplayName("hard verdicts")
  class HardVerdicts {

    @Test
    @DisplayName("should fail a pong with an id the proxy never sent")
    void wrongPong() {
      Probe probe = configuration(MODERN);

      Probe.Outcome outcome = probe.pong(probe.pingId() + 1);

      assertEquals(VerificationFailure.WRONG_ECHO, reason(outcome));
    }

    @Test
    @DisplayName("should fail a keep-alive with an id the proxy never sent")
    void wrongKeepAlive() {
      Probe probe = configuration(MODERN);

      assertEquals(VerificationFailure.WRONG_ECHO, reason(probe.keepAlive(1234)));
    }

    @Test
    @DisplayName("should fail a second pong, a second keep-alive or a second known packs answer")
    void duplicateAnswers() {
      Probe pongs = configuration(MODERN);
      pongs.pong(pongs.pingId());
      assertEquals(VerificationFailure.ILLEGAL_PACKET, reason(pongs.pong(pongs.pingId())));

      Probe keepAlives = configuration(MODERN);
      keepAlives.keepAlive(keepAlives.keepAliveId());
      assertEquals(
          VerificationFailure.ILLEGAL_PACKET,
          reason(keepAlives.keepAlive(keepAlives.keepAliveId())));
    }

    @Test
    @DisplayName("should fail a cookie on a version that has none")
    void answersToNothing() {
      assertEquals(
          VerificationFailure.ILLEGAL_PACKET,
          reason(configuration(FIRST_CONFIGURATION).cookie(Optional.empty())));
    }

    @Test
    @DisplayName("should fail a pong on the pre-join probe, which sends no ping")
    void pongBeforeConfiguration() {
      Probe probe = preJoin(ProtocolVersion.MINECRAFT_1_12_2);

      assertEquals(VerificationFailure.ILLEGAL_PACKET, reason(probe.pong(1)));
    }

    @Test
    @DisplayName("should fail a cookie that comes back changed")
    void cookieChanged() {
      Probe probe = configuration(MODERN);
      byte[] changed = cookieOf(probe);
      changed[0] ^= 1;

      assertEquals(VerificationFailure.COOKIE_MISMATCH, reason(probe.cookie(Optional.of(changed))));
    }

    @Test
    @DisplayName("should fail a packet illegal in the state")
    void illegalPacket() {
      Probe probe = configuration(MODERN);

      Probe.Outcome outcome = probe.illegalPacket("Acknowledge Finish Configuration");

      assertEquals(VerificationFailure.ILLEGAL_PACKET, reason(outcome));
      assertTrue(((Probe.Outcome.Failed) outcome).detail().contains("Acknowledge"));
    }

    @Test
    @DisplayName("should keep its first verdict: nothing after a failure changes it")
    void verdictIsFinal() {
      Probe probe = configuration(MODERN);
      Probe.Outcome failed = probe.pong(probe.pingId() + 1);

      assertEquals(failed, vanillaAnswers(probe, MODERN));
      assertEquals(failed, probe.deadline());
    }

    private static VerificationFailure reason(Probe.Outcome outcome) {
      return assertInstanceOf(Probe.Outcome.Failed.class, outcome).reason();
    }
  }

  // ---------------------------------------------------------------------------
  // Deadlines
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("at the deadline")
  class Deadlines {

    @Test
    @DisplayName("should do nothing before the deadline")
    void notYet() {
      Probe probe = configuration(MODERN);
      nanos.addAndGet(BASE.toNanos() - 1);

      assertInstanceOf(Probe.Outcome.Pending.class, probe.deadline());
    }

    @Test
    @DisplayName("should time out a client that answered nothing, naming what is missing")
    void nothing() {
      Probe probe = configuration(MODERN);
      nanos.addAndGet(BASE.toNanos());

      Probe.Outcome.Failed failed = assertInstanceOf(Probe.Outcome.Failed.class, probe.deadline());

      assertEquals(VerificationFailure.TIMED_OUT, failed.reason());
      assertTrue(failed.detail().contains("pong"), failed.detail());
      assertTrue(failed.detail().contains("keep-alive"), failed.detail());
      assertTrue(failed.detail().contains("client information"), failed.detail());
    }

    @Test
    @DisplayName("should time out a client that echoed everything but never sent its settings")
    void echoesWithoutSettings() {
      Probe probe = configuration(MODERN);
      probe.pong(probe.pingId());
      probe.keepAlive(probe.keepAliveId());
      probe.cookie(Optional.of(cookieOf(probe)));
      assertInstanceOf(Probe.Outcome.Pending.class, probe.outcome(), "waits for the settings");
      nanos.set(probe.deadlineNanos());

      Probe.Outcome.Failed failed = assertInstanceOf(Probe.Outcome.Failed.class, probe.deadline());

      assertEquals(VerificationFailure.TIMED_OUT, failed.reason());
      assertTrue(failed.detail().contains("client information"), failed.detail());
    }

    @Test
    @DisplayName(
        "should pass a client that echoed and sent its settings but ignored the rest, with the"
            + " flags of what it ignored")
    void echoesAndSettingsOnly() {
      Probe probe = configuration(MODERN);
      probe.settings(VANILLA_SETTINGS);
      probe.pong(probe.pingId());
      probe.keepAlive(probe.keepAliveId());
      nanos.set(probe.deadlineNanos());

      Probe.Outcome.Passed passed = assertInstanceOf(Probe.Outcome.Passed.class, probe.deadline());

      assertEquals(Set.of(Probe.Flag.BRAND_MISSING, Probe.Flag.COOKIE_MISSING), passed.flags());
      assertFalse(passed.trusted(), "two flags: let in, probed again next time");
    }
  }

  // ---------------------------------------------------------------------------
  // Flags: never a kick on their own
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("flags")
  class Flags {

    @Test
    @DisplayName("should flag a missing or invalid brand, a low view distance, a missing cookie")
    void softSignals() {
      Probe probe = configuration(MODERN);
      probe.settings(
          new ClientInformation("en_us", (byte) 1, 0, true, (byte) 0, 1, false, true, 0));
      probe.brand(null);
      probe.pong(probe.pingId());
      probe.keepAlive(probe.keepAliveId());

      Probe.Outcome.Passed passed =
          assertInstanceOf(Probe.Outcome.Passed.class, probe.cookie(Optional.empty()));

      assertEquals(
          Set.of(Probe.Flag.BRAND_INVALID, Probe.Flag.VIEW_DISTANCE_LOW, Probe.Flag.COOKIE_MISSING),
          passed.flags());
      assertFalse(passed.trusted());
    }

    @Test
    @DisplayName("should flag answers out of order without failing them")
    void outOfOrder() {
      Probe probe = configuration(FIRST_CONFIGURATION);
      probe.settings(VANILLA_SETTINGS);
      probe.brand("fabric");
      probe.keepAlive(probe.keepAliveId());

      Probe.Outcome.Passed passed =
          assertInstanceOf(Probe.Outcome.Passed.class, probe.pong(probe.pingId()));

      assertEquals(Set.of(Probe.Flag.ANSWERS_OUT_OF_ORDER), passed.flags());
    }

    @Test
    @DisplayName("should pass a modded client: Forge brand, extra messages")
    void moddedClient() {
      Probe probe = configuration(MODERN);
      probe.settings(VANILLA_SETTINGS);
      probe.brand("forge");
      probe.unknownPacket(); // a Forge handshake message
      probe.unknownPacket();
      probe.pong(probe.pingId());
      probe.keepAlive(probe.keepAliveId());

      Probe.Outcome.Passed passed =
          assertInstanceOf(Probe.Outcome.Passed.class, probe.cookie(Optional.of(cookieOf(probe))));

      assertEquals(Set.of(), passed.flags());
      assertTrue(passed.trusted());
    }
  }

  // ---------------------------------------------------------------------------
  // Pre-join probe
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("the pre-join probe")
  class PreJoin {

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = ProbeVersions.class,
        names = {"V1_8", "V1_12_1", "V1_12_2", "V1_16_4", "V1_20_1"})
    @DisplayName("should pass on the echo of its keep-alive, with an id the version carries")
    void passesOnEcho(ProbeVersions version) {
      Probe probe = preJoin(version.version);
      if (!dev.warp.protocol.packet.play.KeepAlive.hasLongId(version.version)) {
        assertTrue(probe.keepAliveId() >= 0 && probe.keepAliveId() <= Integer.MAX_VALUE);
      }
      nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(80));

      Probe.Outcome.Passed passed =
          assertInstanceOf(Probe.Outcome.Passed.class, probe.keepAlive(probe.keepAliveId()));

      assertEquals(TimeUnit.MILLISECONDS.toNanos(80), passed.roundTripNanos());
      assertTrue(passed.trusted());
    }

    @Test
    @DisplayName("should time out without the echo")
    void timesOut() {
      Probe probe = preJoin(ProtocolVersion.MINECRAFT_1_16_4);
      nanos.set(probe.deadlineNanos());

      Probe.Outcome.Failed failed = assertInstanceOf(Probe.Outcome.Failed.class, probe.deadline());

      assertEquals(VerificationFailure.TIMED_OUT, failed.reason());
    }

    @Test
    @DisplayName("should refuse a configuration probe of a version without the state")
    void noConfigurationBefore1202() {
      assertThrows(
          IllegalArgumentException.class, () -> configuration(ProtocolVersion.MINECRAFT_1_20_1));
    }
  }

  /** Versions of the pre-join probe, named for the test's display. */
  enum ProbeVersions {
    V1_8(ProtocolVersion.MINECRAFT_1_8),
    V1_12_1(ProtocolVersion.MINECRAFT_1_12_1),
    V1_12_2(ProtocolVersion.MINECRAFT_1_12_2),
    V1_16_4(ProtocolVersion.MINECRAFT_1_16_4),
    V1_20_1(ProtocolVersion.MINECRAFT_1_20_1);

    final ProtocolVersion version;

    ProbeVersions(ProtocolVersion version) {
      this.version = version;
    }
  }
}
