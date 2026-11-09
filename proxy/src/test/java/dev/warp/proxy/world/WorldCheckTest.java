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
package dev.warp.proxy.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.antibot.VerificationFailure;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.protocol.packet.play.ClientMovement;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The physics state machine of the world check, against a simulated vanilla client. */
@DisplayName("world check")
class WorldCheckTest {

  private static final Duration STAGE = Duration.ofSeconds(5);
  private static final long RTT = TimeUnit.MILLISECONDS.toNanos(40);

  static Stream<ProtocolVersion> families() {
    return Stream.of(
        ProtocolVersion.MINECRAFT_1_8,
        ProtocolVersion.MINECRAFT_1_12_2,
        ProtocolVersion.MINECRAFT_1_16_4,
        ProtocolVersion.MINECRAFT_1_19_2,
        ProtocolVersion.MINECRAFT_1_20_1,
        ProtocolVersion.MINECRAFT_1_20_4,
        ProtocolVersion.MINECRAFT_1_21_4,
        ProtocolVersion.MINECRAFT_26_3);
  }

  /** A client that confirms, reports the target, falls with the vanilla gravity and lands. */
  private WorldCheck.Outcome vanillaFall(WorldCheck check, ProtocolVersion version) {
    long now = 0;
    WorldCheck.Outcome outcome = new WorldCheck.Outcome.Pending();
    if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)) {
      outcome = check.teleportConfirmed(check.teleportId(), now);
      assertInstanceOf(WorldCheck.Outcome.Pending.class, outcome);
    }
    outcome =
        check.moved(
            new ClientMovement.PositionRotation(
                check.startX(), check.startY(), check.startZ(), 0, 0, false),
            now);
    assertInstanceOf(WorldCheck.Outcome.Pending.class, outcome, "the first position");
    double y = check.startY();
    double v = 0;
    double top = check.platformTop();
    for (int tick = 0; tick < 64; tick++) {
      now += TimeUnit.MILLISECONDS.toNanos(50);
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_21_2)) {
        check.tickEnded();
      }
      v = (v - 0.08) * 0.98f;
      double next = y + v;
      if (next <= top) {
        return check.moved(
            new ClientMovement.Position(check.startX(), top, check.startZ(), true), now);
      }
      y = next;
      outcome =
          check.moved(new ClientMovement.Position(check.startX(), y, check.startZ(), false), now);
      assertInstanceOf(WorldCheck.Outcome.Pending.class, outcome, "tick " + tick);
    }
    throw new AssertionError("never landed");
  }

  private WorldCheck.Outcome echo(WorldCheck check, ProtocolVersion version) {
    return version.isAtLeast(ProtocolVersion.MINECRAFT_1_17)
        ? check.pong(check.pingId())
        : check.transaction(check.transactionAction());
  }

  @Nested
  @DisplayName("a vanilla client")
  class Vanilla {

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.world.WorldCheckTest#families")
    @DisplayName("should pass, trusted, after its fall and its echo")
    void passes(ProtocolVersion version) {
      WorldCheck check = new WorldCheck(version, new Random(7), STAGE, RTT, 0);

      assertInstanceOf(WorldCheck.Outcome.Pending.class, echo(check, version));
      WorldCheck.Outcome outcome = vanillaFall(check, version);

      WorldCheck.Outcome.Passed passed = assertInstanceOf(WorldCheck.Outcome.Passed.class, outcome);
      assertTrue(passed.trusted(), passed.toString());
      assertTrue(check.landed());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.world.WorldCheckTest#families")
    @DisplayName("should pass with a flag when the echo comes after the landing")
    void lateEcho(ProtocolVersion version) {
      WorldCheck check = new WorldCheck(version, new Random(11), STAGE, RTT, 0);

      assertInstanceOf(WorldCheck.Outcome.Pending.class, vanillaFall(check, version));
      WorldCheck.Outcome outcome = echo(check, version);

      WorldCheck.Outcome.Passed passed = assertInstanceOf(WorldCheck.Outcome.Passed.class, outcome);
      assertTrue(passed.flags().contains(WorldCheck.Flag.LATE_ECHO));
      assertTrue(passed.trusted(), "one flag is tolerated");
    }

    @Test
    @DisplayName("should land between 8 and 12 ticks after the teleport, at a random height")
    void fallLength() {
      for (int seed = 0; seed < 50; seed++) {
        WorldCheck check =
            new WorldCheck(ProtocolVersion.MINECRAFT_1_20_1, new Random(seed), STAGE, RTT, 0);
        double distance = check.startY() - check.platformTop();
        assertTrue(distance > WorldCheck.fallDistance(7), "seed " + seed + ": " + distance);
        assertTrue(distance < WorldCheck.fallDistance(12), "seed " + seed + ": " + distance);
        assertTrue(check.platformY() >= 40 && check.platformY() <= 200);
        assertTrue(check.blockX() >= 1 && check.blockX() <= 14);
      }
    }
  }

  @Nested
  @DisplayName("a script")
  class Script {

    @Test
    @DisplayName("should fail on a teleport id it was never sent")
    void wrongTeleportId() {
      WorldCheck check =
          new WorldCheck(ProtocolVersion.MINECRAFT_1_20_1, new Random(1), STAGE, RTT, 0);

      WorldCheck.Outcome outcome = check.teleportConfirmed(check.teleportId() + 1, 0);

      assertEquals(
          VerificationFailure.WRONG_ECHO,
          assertInstanceOf(WorldCheck.Outcome.Failed.class, outcome).reason());
    }

    @Test
    @DisplayName("should fail on a first position that is not the teleport target")
    void wrongFirstPosition() {
      WorldCheck check =
          new WorldCheck(ProtocolVersion.MINECRAFT_1_20_1, new Random(1), STAGE, RTT, 0);
      check.teleportConfirmed(check.teleportId(), 0);

      WorldCheck.Outcome outcome =
          check.moved(new ClientMovement.PositionRotation(0, 64, 0, 0, 0, true), 0);

      assertEquals(
          VerificationFailure.WRONG_POSITION,
          assertInstanceOf(WorldCheck.Outcome.Failed.class, outcome).reason());
    }

    @Test
    @DisplayName("should fail on a position before the teleport was confirmed")
    void positionBeforeConfirm() {
      WorldCheck check =
          new WorldCheck(ProtocolVersion.MINECRAFT_1_20_1, new Random(1), STAGE, RTT, 0);

      WorldCheck.Outcome outcome =
          check.moved(
              new ClientMovement.PositionRotation(
                  check.startX(), check.startY(), check.startZ(), 0, 0, false),
              0);

      assertEquals(
          VerificationFailure.ILLEGAL_PACKET,
          assertInstanceOf(WorldCheck.Outcome.Failed.class, outcome).reason());
    }

    @Test
    @DisplayName("should fail on a pong it was never sent")
    void wrongPong() {
      WorldCheck check =
          new WorldCheck(ProtocolVersion.MINECRAFT_1_20_1, new Random(1), STAGE, RTT, 0);

      WorldCheck.Outcome outcome = check.pong(check.pingId() ^ 1);

      assertEquals(
          VerificationFailure.WRONG_ECHO,
          assertInstanceOf(WorldCheck.Outcome.Failed.class, outcome).reason());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.warp.proxy.world.WorldCheckTest#families")
    @DisplayName("should get a mismatch, not a failure, when it hangs in the air or falls wrong")
    void noGravity(ProtocolVersion version) {
      WorldCheck check = new WorldCheck(version, new Random(3), STAGE, RTT, 0);
      if (version.isAtLeast(ProtocolVersion.MINECRAFT_1_9)) {
        check.teleportConfirmed(check.teleportId(), 0);
      }
      check.moved(
          new ClientMovement.PositionRotation(
              check.startX(), check.startY(), check.startZ(), 0, 0, false),
          0);

      // Teleports itself straight onto the platform, as a bot that knows about gravity but
      // not about ticks would.
      WorldCheck.Outcome outcome =
          check.moved(
              new ClientMovement.Position(
                  check.startX(), check.platformTop(), check.startZ(), true),
              0);

      assertInstanceOf(WorldCheck.Outcome.Mismatch.class, outcome);
    }

    @Test
    @DisplayName("should pass after its retry when the second fall is right")
    void retryThenPass() {
      ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
      WorldCheck check = new WorldCheck(version, new Random(5), STAGE, RTT, 0);
      check.teleportConfirmed(check.teleportId(), 0);
      check.moved(
          new ClientMovement.PositionRotation(
              check.startX(), check.startY(), check.startZ(), 0, 0, false),
          0);
      assertInstanceOf(
          WorldCheck.Outcome.Mismatch.class,
          check.moved(
              new ClientMovement.Position(
                  check.startX(), check.startY() - 1, check.startZ(), false),
              0));
      double before = check.startY();

      check.retry(0);
      assertTrue(check.retried());
      assertFalse(before == check.startY() && check.landed(), "a new height");
      echo(check, version);
      WorldCheck.Outcome outcome = vanillaFall(check, version);

      WorldCheck.Outcome.Passed passed = assertInstanceOf(WorldCheck.Outcome.Passed.class, outcome);
      assertEquals(List.of(WorldCheck.Flag.RETRIED), List.copyOf(passed.flags()));
      assertTrue(passed.trusted());
    }

    @Test
    @DisplayName("should time out without a confirmation, after the stage and four round trips")
    void timesOut() {
      WorldCheck check =
          new WorldCheck(ProtocolVersion.MINECRAFT_1_20_1, new Random(1), STAGE, RTT, 0);

      assertInstanceOf(
          WorldCheck.Outcome.Pending.class, check.deadline(STAGE.toNanos() + 4 * RTT - 1));
      WorldCheck.Outcome outcome = check.deadline(STAGE.toNanos() + 4 * RTT);

      assertEquals(
          VerificationFailure.TIMED_OUT,
          assertInstanceOf(WorldCheck.Outcome.Failed.class, outcome).reason());
    }

    @Test
    @DisplayName("should treat a fall that stops as a mismatch at its deadline, never a strike")
    void fallStops() {
      WorldCheck check =
          new WorldCheck(ProtocolVersion.MINECRAFT_1_20_1, new Random(1), STAGE, RTT, 0);
      check.teleportConfirmed(check.teleportId(), 0);
      check.moved(
          new ClientMovement.PositionRotation(
              check.startX(), check.startY(), check.startZ(), 0, 0, false),
          0);

      WorldCheck.Outcome outcome = check.deadline(check.deadlineNanos());

      assertInstanceOf(WorldCheck.Outcome.Mismatch.class, outcome);
    }
  }
}
