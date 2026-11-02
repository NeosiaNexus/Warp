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
package dev.warp.proxy.limit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AuthenticationLane")
class AuthenticationLaneTest {

  private final EmbeddedChannel loop = frozen();
  private final List<String> order = new ArrayList<>();

  @Test
  @DisplayName("should give a free slot to a trusted login before unknown ones queued earlier")
  void trustedFirst() {
    AuthenticationLane lane =
        new AuthenticationLane(1, 10, Duration.ofSeconds(10), System::nanoTime);
    AuthenticationLane.Slot taken = lane.tryAcquire();
    assertNotNull(taken);

    enqueue(lane, false, "unknown 1");
    enqueue(lane, false, "unknown 2");
    enqueue(lane, true, "trusted");
    assertEquals(3, lane.waiting());
    taken.release();
    loop.runPendingTasks();

    assertEquals(List.of("trusted"), order);
    assertEquals(2, lane.waiting());
    assertEquals(1, lane.inFlight(), "the slot went to the trusted login");
  }

  @Test
  @DisplayName("should keep unknown logins in arrival order")
  void fifo() {
    AuthenticationLane lane =
        new AuthenticationLane(1, 10, Duration.ofSeconds(10), System::nanoTime);
    AuthenticationLane.Slot taken = lane.tryAcquire();
    assertNotNull(taken);
    List<AuthenticationLane.Slot> slots = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      String name = "unknown " + i;
      lane.enqueue(
          false,
          executor(),
          slot -> {
            order.add(name);
            slots.add(slot);
          },
          () -> order.add("expired"));
    }

    taken.release();
    loop.runPendingTasks();
    slots.getLast().release();
    loop.runPendingTasks();

    assertEquals(List.of("unknown 1", "unknown 2"), order);
  }

  @Test
  @DisplayName("should refuse a login when its line is full, and let others time out")
  void boundedAndTimed() {
    AuthenticationLane lane =
        new AuthenticationLane(1, 2, Duration.ofSeconds(10), System::nanoTime);
    assertNotNull(lane.tryAcquire());
    enqueue(lane, false, "a");
    enqueue(lane, false, "b");

    assertNull(lane.enqueue(false, executor(), slot -> {}, () -> {}), "line full");
    assertNotNull(
        lane.enqueue(true, executor(), slot -> {}, () -> {}), "the trusted line is apart");
    loop.advanceTimeBy(10, TimeUnit.SECONDS);
    loop.runScheduledPendingTasks();

    assertEquals(List.of("expired a", "expired b"), order.stream().sorted().toList());
    assertEquals(0, lane.waiting());
  }

  @Test
  @DisplayName("should never take more slots than its maximum, nor wait when unlimited")
  void bounds() {
    AuthenticationLane lane =
        new AuthenticationLane(2, 10, Duration.ofSeconds(10), System::nanoTime);
    assertNotNull(lane.tryAcquire());
    assertNotNull(lane.tryAcquire());
    assertNull(lane.tryAcquire());
    assertTrue(lane.mustWait());

    AuthenticationLane unlimited =
        new AuthenticationLane(0, 0, Duration.ofSeconds(10), System::nanoTime);
    for (int i = 0; i < 1000; i++) {
      assertNotNull(unlimited.tryAcquire());
    }
    assertEquals(false, unlimited.mustWait());
  }

  private void enqueue(AuthenticationLane lane, boolean trusted, String name) {
    assertNotNull(
        lane.enqueue(
            trusted, executor(), slot -> order.add(name), () -> order.add("expired " + name)));
  }

  private EventExecutor executor() {
    return loop.eventLoop();
  }

  private static EmbeddedChannel frozen() {
    EmbeddedChannel channel = new EmbeddedChannel();
    channel.freezeTime();
    return channel;
  }
}
