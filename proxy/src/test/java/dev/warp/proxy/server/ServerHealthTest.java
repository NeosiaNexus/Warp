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
package dev.warp.proxy.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServerHealth")
class ServerHealthTest {

  private static final Duration COOLDOWN = Duration.ofSeconds(10);

  private final AtomicLong now = new AtomicLong();
  private final ServerHealth health = new ServerHealth(now::get, COOLDOWN);

  private void advance(Duration duration) {
    now.addAndGet(duration.toNanos());
  }

  @Nested
  @DisplayName("circuit breaker")
  class Breaker {

    @Test
    @DisplayName("should allow a server it never heard of")
    void unknownIsClosed() {
      assertTrue(health.allows("lobby"));
      assertEquals(ServerHealth.State.CLOSED, health.status("lobby").state());
    }

    @Test
    @DisplayName("should open after a failed connection and skip the server during the cool-down")
    void opensOnConnectFailure() {
      health.connectFailed("lobby");

      assertFalse(health.allows("lobby"));
      assertEquals(ServerHealth.State.OPEN, health.status("lobby").state());
      assertEquals(1, health.status("lobby").failures());
      advance(COOLDOWN.minusSeconds(1));
      assertFalse(health.allows("lobby"));
    }

    @Test
    @DisplayName("should open after a failed ping")
    void opensOnPingFailure() {
      health.pingFailed("lobby");

      assertFalse(health.allows("lobby"));
    }

    @Test
    @DisplayName("should let one probe through once the cool-down has passed")
    void halfOpenProbe() {
      health.connectFailed("lobby");
      advance(COOLDOWN);

      assertEquals(ServerHealth.State.HALF_OPEN, health.status("lobby").state());
      assertTrue(health.allows("lobby"));
      health.picked("lobby");
      assertFalse(health.allows("lobby"), "a second player waits for the probe");
    }

    @Test
    @DisplayName("should close when the probe connects, and reopen when it fails")
    void probeOutcome() {
      health.connectFailed("lobby");
      advance(COOLDOWN);
      health.picked("lobby");

      health.connectSucceeded("lobby");
      assertEquals(ServerHealth.State.CLOSED, health.status("lobby").state());
      assertEquals(0, health.status("lobby").failures());
      assertTrue(health.allows("lobby"));

      health.connectFailed("lobby");
      advance(COOLDOWN);
      health.picked("lobby");
      health.connectFailed("lobby");
      assertEquals(ServerHealth.State.OPEN, health.status("lobby").state());
      assertEquals(2, health.status("lobby").failures());
      assertFalse(health.allows("lobby"));
    }

    @Test
    @DisplayName("should let another probe through when the first never reports")
    void probeExpires() {
      health.connectFailed("lobby");
      advance(COOLDOWN);
      health.picked("lobby");
      assertFalse(health.allows("lobby"));

      advance(COOLDOWN);

      assertTrue(health.allows("lobby"));
    }

    @Test
    @DisplayName("should close at once when a ping is answered")
    void pingCloses() {
      health.connectFailed("lobby");

      health.pingSucceeded("lobby", Duration.ofMillis(12));

      assertTrue(health.allows("lobby"));
      assertEquals(Optional.of(Duration.ofMillis(12)), health.latency("lobby"));
    }

    @Test
    @DisplayName("should not mark a closed server as probing when picked")
    void pickedWhenClosed() {
      health.picked("lobby");
      health.pingSucceeded("lobby", Duration.ofMillis(1));
      health.picked("lobby");

      assertFalse(health.status("lobby").probing());
      assertTrue(health.allows("lobby"));
    }

    @Test
    @DisplayName("should forget a server")
    void forget() {
      health.connectFailed("lobby");

      health.forget("lobby");

      assertTrue(health.allows("lobby"));
    }
  }

  @Nested
  @DisplayName("login refusals")
  class LoginRefusals {

    @Test
    @DisplayName("should take a single refused login as aimed at that player")
    void oneRefusalCounts() {
      health.loginRefused("lobby");

      assertTrue(health.allows("lobby"));
      assertEquals(ServerHealth.State.CLOSED, health.status("lobby").state());
      assertEquals(1, health.status("lobby").failures());
    }

    @Test
    @DisplayName("should open after three refused logins in a row")
    void opensAfterRepeatedRefusals() {
      health.loginRefused("lobby");
      health.loginRefused("lobby");
      assertTrue(health.allows("lobby"), "two refusals may be two banned players");

      health.loginRefused("lobby");

      assertFalse(health.allows("lobby"));
      assertEquals(ServerHealth.State.OPEN, health.status("lobby").state());
      assertEquals(ServerHealth.LOGIN_REFUSALS_TO_OPEN, health.status("lobby").failures());
    }

    @Test
    @DisplayName("should forget the refusals once a login is accepted")
    void acceptedLoginResets() {
      health.loginRefused("lobby");
      health.loginRefused("lobby");

      health.connectSucceeded("lobby");
      health.loginRefused("lobby");
      health.loginRefused("lobby");

      assertTrue(health.allows("lobby"));
      assertEquals(2, health.status("lobby").failures());
    }

    @Test
    @DisplayName("should reopen at once when the probe's login is refused")
    void refusedProbeReopens() {
      health.pingFailed("lobby");
      advance(COOLDOWN);
      health.picked("lobby");

      health.loginRefused("lobby");

      assertEquals(ServerHealth.State.OPEN, health.status("lobby").state());
      assertFalse(health.allows("lobby"));
      advance(COOLDOWN.minusSeconds(1));
      assertFalse(health.allows("lobby"), "another full cool-down");
    }
  }

  @Nested
  @DisplayName("latency")
  class Latency {

    @Test
    @DisplayName("should keep the last round trip across failures")
    void keepsLatency() {
      health.pingSucceeded("lobby", Duration.ofMillis(20));
      health.pingFailed("lobby");

      assertEquals(Optional.of(Duration.ofMillis(20)), health.latency("lobby"));
      health.connectSucceeded("lobby");
      assertEquals(Optional.of(Duration.ofMillis(20)), health.latency("lobby"));
    }

    @Test
    @DisplayName("should be empty before any successful ping")
    void emptyAtFirst() {
      assertEquals(Optional.empty(), health.latency("lobby"));
    }
  }

  @Test
  @DisplayName("should reject a cool-down that is not positive")
  void rejectsBadCooldown() {
    assertThrows(IllegalArgumentException.class, () -> new ServerHealth(now::get, Duration.ZERO));
  }
}
