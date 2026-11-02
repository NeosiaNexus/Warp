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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.api.antibot.AttackLevel;
import dev.warp.api.antibot.AttackReport;
import dev.warp.api.antibot.Signal;
import dev.warp.proxy.config.AntiBotConfig;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Drives the detector second by second on a clock of its own. */
@DisplayName("AttackDetector")
class AttackDetectorTest {

  private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
  private final AtomicInteger pending = new AtomicInteger();
  private final List<String> heard = new ArrayList<>();
  private final List<AttackReport> ended = new ArrayList<>();

  private final AttackDetector detector =
      new AttackDetector(
          AntiBotConfig.Attack.DEFAULTS,
          pending::get,
          1_000,
          24,
          48,
          clock::get,
          new AttackDetector.Listener() {
            @Override
            public void started(AttackReport attack) {
              heard.add("started " + attack.trigger());
            }

            @Override
            public void ongoing(AttackReport attack, AttackDetector.Snapshot now) {
              heard.add("ongoing");
            }

            @Override
            public void ended(AttackReport attack) {
              heard.add("ended");
              AttackDetectorTest.this.ended.add(attack);
            }
          });

  @Nested
  @DisplayName("entering an attack")
  class Entering {

    @Test
    @DisplayName("should start after 3 seconds in a row over the floor, not after 2")
    void sustained() {
      seconds(2, Signal.UNKNOWN_LOGINS, 50);
      assertEquals(AttackLevel.NORMAL, detector.level());

      seconds(1, Signal.UNKNOWN_LOGINS, 50);

      assertEquals(AttackLevel.ATTACK, detector.level());
      assertEquals(List.of("started UNKNOWN_LOGINS"), heard);
      AttackReport attack = detector.currentAttack().orElseThrow();
      assertEquals(50, attack.triggerRate());
      assertEquals(10, attack.threshold());
      assertEquals(150, attack.totals().get(Signal.UNKNOWN_LOGINS), "the sustaining seconds count");
    }

    @Test
    @DisplayName("should not start on a burst that breaks off, such as known players reconnecting")
    void bursts() {
      for (int i = 0; i < 10; i++) {
        seconds(2, Signal.UNKNOWN_LOGINS, 50);
        seconds(1, Signal.UNKNOWN_LOGINS, 0);
      }
      seconds(5, Signal.CONNECTIONS, 5_000); // connections alone never start one

      assertEquals(AttackLevel.NORMAL, detector.level());
      assertTrue(heard.isEmpty());
    }

    @Test
    @DisplayName("should raise the threshold to a multiple of a learned baseline")
    void learnedBaseline() {
      // An hour of 5 unknown logins a second: a busy server's ordinary rate.
      seconds(3_600, Signal.UNKNOWN_LOGINS, 5);

      long threshold = detector.threshold(Signal.UNKNOWN_LOGINS);
      assertTrue(threshold > 10 && threshold <= 20, "4 x about 5/s, got " + threshold);
      seconds(5, Signal.UNKNOWN_LOGINS, 15);
      assertEquals(AttackLevel.NORMAL, detector.level(), "15/s is within 4 x the baseline");
    }

    @Test
    @DisplayName("should start when connections not logged in pass half their cap")
    void pendingShare() {
      pending.set(600);

      seconds(3, Signal.REFUSALS, 0);

      assertEquals(List.of("started PENDING_CONNECTIONS"), heard);
    }
  }

  @Nested
  @DisplayName("during and after an attack")
  class During {

    @Test
    @DisplayName("should sum up every 10 s, name its sources and end after 60 calm seconds")
    void lifecycle() {
      seconds(3, Signal.AUTHENTICATION_FAILURES, 100);
      for (int i = 0; i < 20; i++) {
        detector.record(Signal.AUTHENTICATION_FAILURES, address("203.0.113." + (i % 3)));
      }
      seconds(10, Signal.AUTHENTICATION_FAILURES, 100);
      seconds(
          59, Signal.AUTHENTICATION_FAILURES, 2); // under half the floor of 5, but not long enough
      assertEquals(AttackLevel.ATTACK, detector.level());

      seconds(1, Signal.AUTHENTICATION_FAILURES, 2);

      assertEquals(AttackLevel.NORMAL, detector.level());
      assertTrue(heard.contains("ongoing"));
      assertEquals("ended", heard.getLast());
      AttackReport report = ended.getFirst();
      assertNotNull(report.endedAt());
      assertEquals(120, report.peaks().get(Signal.AUTHENTICATION_FAILURES), "with the 20 above");
      assertEquals("203.0.113.0/24", report.topSources().getFirst().network());
      assertEquals(20, report.topSources().getFirst().events());
    }
  }

  /** Records {@code count} events of a signal in each of {@code seconds} seconds. */
  private void seconds(int seconds, Signal signal, int count) {
    for (int s = 0; s < seconds; s++) {
      for (int i = 0; i < count; i++) {
        detector.record(signal);
      }
      clock.addAndGet(1_000);
      detector.tick();
    }
  }

  private static InetAddress address(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new AssertionError(e);
    }
  }
}
