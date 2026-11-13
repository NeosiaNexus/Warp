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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests the keep-alive wheel on the clock of an embedded channel's event loop, one tick at a time.
 */
@DisplayName("Keep-alive wheel")
class KeepAliveWheelTest {

  private static final int INTERVAL_SECONDS = 15;

  private final EmbeddedChannel channel = new EmbeddedChannel();
  private final KeepAliveWheel wheel = KeepAliveWheel.of(channel.eventLoop());

  /** The names of the tasks that ran, in order. */
  private final List<String> served = new ArrayList<>();

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  // ---------------------------------------------------------------------------
  // Turns
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Turns")
  class Turns {

    @Test
    @DisplayName("should serve a seat one interval after it was taken, then every interval")
    void servesEveryInterval() {
      wheel.join(named("a"));

      elapse(INTERVAL_SECONDS - 1);
      assertEquals(List.of(), served, "nothing is due before one interval");
      elapse(1);
      assertEquals(List.of("a"), served);
      elapse(INTERVAL_SECONDS - 1);
      assertEquals(List.of("a"), served, "the second turn is one full interval after the first");
      elapse(1);
      assertEquals(List.of("a", "a"), served);
    }

    @Test
    @DisplayName("should serve a seat taken later one interval after it was taken")
    void servesLateSeatAfterOneInterval() {
      wheel.join(named("a"));
      elapse(7);
      wheel.join(named("b"));

      elapse(INTERVAL_SECONDS - 1);
      assertEquals(List.of("a"), served, "b is not due yet");
      elapse(1);
      assertEquals(List.of("a", "b"), served);
    }

    @Test
    @DisplayName("should serve seats taken at the same time in the same tick")
    void servesSeatsOfOneSlotTogether() {
      wheel.join(named("a"));
      wheel.join(named("b"));
      wheel.join(named("c"));

      elapse(INTERVAL_SECONDS);

      assertEquals(List.of("c", "b", "a"), served);
    }

    @Test
    @DisplayName("should give a seat taken after every slot was served a turn per interval")
    void wrapsAround() {
      wheel.join(named("a"));
      elapse(2 * INTERVAL_SECONDS + 3);
      served.clear();
      wheel.join(named("b"));

      elapse(INTERVAL_SECONDS);

      assertEquals(List.of("a", "b"), served);
    }

    @Test
    @DisplayName("should keep serving the other seats when a task fails")
    void survivesFailingTask() {
      wheel.join(named("a"));
      wheel.join(
          () -> {
            served.add("failing");
            throw new IllegalStateException("boom");
          });
      wheel.join(named("c"));

      elapse(2 * INTERVAL_SECONDS);

      assertEquals(List.of("c", "failing", "a", "c", "failing", "a"), served);
    }
  }

  // ---------------------------------------------------------------------------
  // Leaving
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Leaving")
  class Leaving {

    @Test
    @DisplayName("should no longer serve a seat that was left")
    void stopsServingLeftSeat() {
      KeepAliveWheel.Seat seat = wheel.join(named("a"));
      elapse(INTERVAL_SECONDS);

      seat.leave();
      elapse(2 * INTERVAL_SECONDS);

      assertEquals(List.of("a"), served);
    }

    @Test
    @DisplayName("should keep serving the seats around one that was left, wherever it stood")
    void keepsNeighbours() {
      KeepAliveWheel.Seat last = wheel.join(named("a"));
      wheel.join(named("b"));
      KeepAliveWheel.Seat middle = wheel.join(named("c"));
      wheel.join(named("d"));
      KeepAliveWheel.Seat first = wheel.join(named("e"));

      middle.leave();
      elapse(INTERVAL_SECONDS);
      assertEquals(List.of("e", "d", "b", "a"), served);

      served.clear();
      first.leave();
      elapse(INTERVAL_SECONDS);
      assertEquals(List.of("d", "b", "a"), served);

      served.clear();
      last.leave();
      elapse(INTERVAL_SECONDS);
      assertEquals(List.of("d", "b"), served);
      assertEquals(2, wheel.seats());
    }

    @Test
    @DisplayName("should count a seat left twice once")
    void leavesOnce() {
      KeepAliveWheel.Seat seat = wheel.join(named("a"));
      wheel.join(named("b"));

      seat.leave();
      seat.leave();

      assertEquals(1, wheel.seats());
      elapse(INTERVAL_SECONDS);
      assertEquals(List.of("b"), served);
    }

    @Test
    @DisplayName("should stop its timer once the last seat was left, and start it again")
    void stopsTimerWhenEmpty() {
      KeepAliveWheel.Seat seat = wheel.join(named("a"));
      assertEquals(1, wheel.seats());

      seat.leave();

      assertEquals(0, wheel.seats());
      assertEquals(-1, channel.runScheduledPendingTasks(), "no timer is left on the loop");

      wheel.join(named("b"));
      elapse(INTERVAL_SECONDS);
      assertEquals(List.of("b"), served);
    }

    @Test
    @DisplayName("should run one timer however many seats are taken")
    void runsOneTimer() {
      wheel.join(named("a"));
      KeepAliveWheel.Seat second = wheel.join(named("b"));
      second.leave();

      elapse(INTERVAL_SECONDS);

      assertEquals(List.of("a"), served, "a second timer would serve a slot twice as often");
      elapse(INTERVAL_SECONDS - 1);
      assertEquals(List.of("a"), served);
    }
  }

  // ---------------------------------------------------------------------------
  // Changes during a tick
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Changes made by a task during its turn")
  class DuringTick {

    @Test
    @DisplayName("should let a task leave its own seat and still serve the following ones")
    void taskLeavesItsOwnSeat() {
      wheel.join(named("a"));
      AtomicReference<KeepAliveWheel.Seat> own = new AtomicReference<>();
      own.set(
          wheel.join(
              () -> {
                served.add("b");
                Objects.requireNonNull(own.get()).leave();
              }));
      wheel.join(named("c"));

      elapse(2 * INTERVAL_SECONDS);

      assertEquals(List.of("c", "b", "a", "c", "a"), served);
    }

    @Test
    @DisplayName("should not serve the next seat when the running task makes it leave")
    void taskMakesNextSeatLeave() {
      wheel.join(named("a"));
      KeepAliveWheel.Seat next = wheel.join(named("b"));
      wheel.join(
          () -> {
            served.add("c");
            next.leave();
          });

      elapse(INTERVAL_SECONDS);

      assertEquals(List.of("c", "a"), served);
    }

    @Test
    @DisplayName("should not serve a later seat when the running task makes it leave")
    void taskMakesLaterSeatLeave() {
      KeepAliveWheel.Seat later = wheel.join(named("a"));
      wheel.join(named("b"));
      wheel.join(
          () -> {
            served.add("c");
            later.leave();
          });

      elapse(INTERVAL_SECONDS);

      assertEquals(List.of("c", "b"), served);
    }

    @Test
    @DisplayName("should keep the pass going when a task makes an already served seat leave")
    void taskMakesServedSeatLeave() {
      wheel.join(named("a"));
      AtomicReference<KeepAliveWheel.Seat> servedSeat = new AtomicReference<>();
      wheel.join(
          () -> {
            served.add("b");
            Objects.requireNonNull(servedSeat.get()).leave();
          });
      servedSeat.set(wheel.join(named("c")));

      elapse(INTERVAL_SECONDS);

      assertEquals(List.of("c", "b", "a"), served);
    }

    @Test
    @DisplayName("should serve a seat taken during a tick one interval later, not in that tick")
    void seatTakenDuringTickWaits() {
      wheel.join(
          () -> {
            served.add("a");
            if (served.size() == 1) {
              wheel.join(named("b"));
            }
          });

      elapse(INTERVAL_SECONDS);
      assertEquals(List.of("a"), served);
      elapse(INTERVAL_SECONDS - 1);
      assertEquals(List.of("a"), served);
      elapse(1);
      assertEquals(List.of("a", "b", "a"), served);
    }

    @Test
    @DisplayName("should serve nothing twice when the last task of a tick leaves its seat")
    void lastTaskLeaves() {
      AtomicReference<KeepAliveWheel.Seat> own = new AtomicReference<>();
      own.set(
          wheel.join(
              () -> {
                served.add("a");
                Objects.requireNonNull(own.get()).leave();
              }));
      wheel.join(named("b"));
      elapse(INTERVAL_SECONDS);
      served.clear();

      KeepAliveWheel.Seat late = wheel.join(named("c"));
      late.leave();
      elapse(INTERVAL_SECONDS);

      assertEquals(List.of("b"), served, "a seat left after a tick must not disturb the next one");
    }
  }

  // ---------------------------------------------------------------------------
  // One wheel per event loop
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("One wheel per event loop")
  class PerEventLoop {

    @Test
    @DisplayName("should give an event loop the same wheel every time")
    void sameWheelForSameLoop() {
      assertSame(wheel, KeepAliveWheel.of(channel.eventLoop()));
    }

    @Test
    @DisplayName("should give another event loop a wheel of its own, on its own clock")
    void otherWheelForOtherLoop() {
      EmbeddedChannel other = new EmbeddedChannel();
      try {
        KeepAliveWheel otherWheel = KeepAliveWheel.of(other.eventLoop());
        assertNotSame(wheel, otherWheel);
        otherWheel.join(named("other"));
        wheel.join(named("a"));

        elapse(INTERVAL_SECONDS);

        assertEquals(List.of("a"), served, "the other loop's clock did not move");
      } finally {
        other.finishAndReleaseAll();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private Runnable named(String name) {
    return () -> served.add(name);
  }

  /** Lets seconds pass on the channel's clock, running the timer after each of them. */
  private void elapse(int seconds) {
    for (int i = 0; i < seconds; i++) {
      channel.advanceTimeBy(1, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();
    }
  }
}
