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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** The fair queue of verifications: who is served when, on an event loop driven by hand. */
@DisplayName("VerificationQueue")
class VerificationQueueTest {

  private static final Duration MAX_WAIT = Duration.ofSeconds(20);

  private final AtomicLong nanos = new AtomicLong(TimeUnit.HOURS.toNanos(1));
  private final EmbeddedChannel channel = new EmbeddedChannel();
  private final EventExecutor loop = channel.eventLoop();

  @AfterEach
  void close() {
    channel.finishAndReleaseAll();
  }

  private VerificationQueue queue(int max, int capacity) {
    return new VerificationQueue(max, capacity, MAX_WAIT, nanos::get);
  }

  /** A login in line: records when it was granted or told to retry. */
  private final class Login {
    final int network;
    final String name;
    private VerificationQueue.@Nullable Slot slot;
    private boolean expired;
    private VerificationQueue.@Nullable Waiter waiter;

    Login(int network, String name) {
      this.network = network;
      this.name = name;
    }

    boolean enqueue(VerificationQueue queue) {
      waiter = queue.enqueue(network, loop, granted -> slot = granted, () -> expired = true);
      channel.runPendingTasks();
      return waiter != null;
    }

    boolean granted() {
      channel.runPendingTasks();
      return slot != null;
    }
  }

  // ---------------------------------------------------------------------------
  // Slots
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("slots")
  class Slots {

    @Test
    @DisplayName("should hand out slots at once up to the cap, then none")
    void upToTheCap() {
      VerificationQueue queue = queue(2, 10);

      assertNotNull(queue.tryAcquire());
      assertNotNull(queue.tryAcquire());
      assertNull(queue.tryAcquire());
      assertEquals(2, queue.inFlight());
    }

    @Test
    @DisplayName("should give a released slot to the first in line, not to a newcomer")
    void lineFirst() {
      VerificationQueue queue = queue(1, 10);
      VerificationQueue.Slot held = queue.tryAcquire();
      assertNotNull(held);
      Login waiting = new Login(1, "waiting");
      assertTrue(waiting.enqueue(queue));
      assertEquals(1, queue.waiting());

      assertNull(queue.tryAcquire(), "the line goes first");
      held.release();

      assertTrue(waiting.granted());
      assertEquals(0, queue.waiting());
      assertEquals(1, queue.inFlight());
      assertEquals(1, queue.grantedFromLine());
    }

    @Test
    @DisplayName("should never run more verifications than the cap while logins race the line")
    void capUnderContention() throws Exception {
      int max = 2;
      VerificationQueue queue = queue(max, 1_000);
      EventExecutor executor = new DefaultEventExecutor();
      AtomicInteger over = new AtomicInteger();
      AtomicInteger granted = new AtomicInteger();
      Runnable check =
          () -> {
            if (queue.inFlight() > max) {
              over.incrementAndGet();
            }
          };
      try {
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
          int network = t;
          threads.add(
              Thread.ofPlatform()
                  .start(
                      () -> {
                        for (int i = 0; i < 5_000; i++) {
                          VerificationQueue.Slot slot = queue.tryAcquire();
                          if (slot != null) {
                            check.run();
                            Thread.onSpinWait();
                            slot.release();
                            continue;
                          }
                          VerificationQueue.Waiter waiter =
                              queue.enqueue(
                                  network,
                                  executor,
                                  given -> {
                                    check.run();
                                    granted.incrementAndGet();
                                    given.release();
                                  },
                                  () -> {});
                          if (waiter == null) {
                            Thread.onSpinWait();
                          }
                        }
                      }));
        }
        for (Thread thread : threads) {
          thread.join(TimeUnit.SECONDS.toMillis(30));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((queue.waiting() > 0 || queue.inFlight() > 0) && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
      } finally {
        executor.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
      }

      assertEquals(0, over.get(), "verifications over the cap");
      assertEquals(0, queue.waiting(), "everyone in line was served");
      assertEquals(0, queue.inFlight());
      assertEquals(granted.get(), queue.grantedFromLine());
    }

    @Test
    @DisplayName("should release a slot once, however many times it is told to")
    void releaseOnce() {
      VerificationQueue queue = queue(1, 10);
      VerificationQueue.Slot held = queue.tryAcquire();
      assertNotNull(held);

      held.release();
      held.release();

      assertEquals(0, queue.inFlight());
    }
  }

  // ---------------------------------------------------------------------------
  // Fairness
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("fairness between networks")
  class Fairness {

    @Test
    @DisplayName(
        "should serve one waiter per network per round: a lone player waits behind at most"
            + " one connection of each other network")
    void roundRobin() {
      VerificationQueue queue = queue(1, 1000);
      VerificationQueue.Slot held = queue.tryAcquire();
      assertNotNull(held);
      List<Login> flood = new ArrayList<>();
      for (int i = 0; i < 50; i++) {
        Login bot = new Login(0xB07, "bot" + i);
        assertTrue(bot.enqueue(queue));
        flood.add(bot);
      }
      Login player = new Login(0x5EED, "player");
      assertTrue(player.enqueue(queue));

      List<String> order = new ArrayList<>();
      VerificationQueue.Slot current = held;
      for (int served = 0; served < 3; served++) {
        current.release();
        channel.runPendingTasks();
        Login granted = grantedOf(flood, player);
        order.add(granted.name);
        current = Objects.requireNonNull(granted.slot);
        granted.slot = null;
      }

      assertEquals("bot0", order.get(0), "the flood's first arrived first");
      assertEquals("player", order.get(1), "then the other network's turn");
      assertEquals("bot1", order.get(2));
    }

    @RepeatedTest(5)
    @DisplayName("should let every network through in proportion to one, whatever their sizes")
    void proportional() {
      Random random = new Random();
      VerificationQueue queue = queue(1, 10_000);
      VerificationQueue.Slot held = queue.tryAcquire();
      assertNotNull(held);
      List<Login> all = new ArrayList<>();
      int networks = 8;
      for (int network = 0; network < networks; network++) {
        int size = 1 + random.nextInt(40);
        // One hash per network, as every address of a network hashes the same.
        int hash = random.nextInt() * networks + network;
        for (int i = 0; i < size; i++) {
          all.add(new Login(hash, "n" + network + "-" + i));
        }
      }
      // Arrival order shuffled: fairness must not depend on it.
      java.util.Collections.shuffle(all, random);
      for (Login login : all) {
        assertTrue(login.enqueue(queue));
      }

      // Serve as many rounds as there are networks: each network gets one per round.
      Map<Integer, Integer> served = new HashMap<>();
      VerificationQueue.Slot current = held;
      for (int round = 0; round < networks; round++) {
        current.release();
        channel.runPendingTasks();
        Login granted = all.stream().filter(l -> l.slot != null).findFirst().orElseThrow();
        served.merge(Math.floorMod(granted.network, networks), 1, Integer::sum);
        current = Objects.requireNonNull(granted.slot);
        granted.slot = null;
        all.remove(granted);
      }

      // Buckets differ per network here (distinct hashes modulo the bucket count are likely but
      // not certain): with hash collisions two networks share a bucket, so no network may be
      // served more than twice in as many rounds as there are networks.
      for (int count : served.values()) {
        assertTrue(count <= 2, "one network took " + count + " of " + networks + " turns");
      }
    }

    @Test
    @DisplayName("should refuse a network that holds its share of the line, and nobody else")
    void perNetworkShare() {
      VerificationQueue queue = queue(1, 64); // a sixteenth: 4 per network
      assertNotNull(queue.tryAcquire());
      for (int i = 0; i < 4; i++) {
        assertTrue(new Login(1, "bot" + i).enqueue(queue));
      }

      assertFalse(new Login(1, "bot4").enqueue(queue), "its network's share is full");
      assertTrue(new Login(2, "player").enqueue(queue), "another network still gets in");
      assertEquals(1, queue.refused());
      assertEquals(5, queue.waiting());
    }

    @Test
    @DisplayName("should refuse everyone once the line is full")
    void lineFull() {
      VerificationQueue queue = queue(1, 3);
      assertNotNull(queue.tryAcquire());
      for (int i = 0; i < 3; i++) {
        assertTrue(new Login(i, "n" + i).enqueue(queue));
      }

      assertFalse(new Login(99, "late").enqueue(queue));
      assertEquals(3, queue.waiting());
    }

    @Test
    @DisplayName("should queue nobody when the line has no room at all")
    void noLine() {
      VerificationQueue queue = queue(1, 0);
      assertNotNull(queue.tryAcquire());

      assertFalse(new Login(1, "anyone").enqueue(queue));
    }

    private Login grantedOf(List<Login> flood, Login player) {
      if (player.granted()) {
        return player;
      }
      for (Login bot : flood) {
        if (bot.granted()) {
          return bot;
        }
      }
      throw new AssertionError("nobody was granted");
    }
  }

  // ---------------------------------------------------------------------------
  // Leaving the line
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("leaving the line")
  class Leaving {

    @Test
    @DisplayName("should tell a waiter the proxy is busy after the maximum wait")
    void expires() {
      VerificationQueue queue = queue(1, 10);
      assertNotNull(queue.tryAcquire());
      Login waiting = new Login(1, "waiting");
      assertTrue(waiting.enqueue(queue));

      channel.advanceTimeBy(MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
      channel.runScheduledPendingTasks();

      assertTrue(waiting.expired);
      assertEquals(0, queue.waiting());
      assertEquals(1, queue.expired());
    }

    @Test
    @DisplayName("should forget a waiter that cancels, and serve the next")
    void cancels() {
      VerificationQueue queue = queue(1, 10);
      VerificationQueue.Slot held = queue.tryAcquire();
      assertNotNull(held);
      Login first = new Login(1, "first");
      Login second = new Login(1, "second");
      assertTrue(first.enqueue(queue));
      assertTrue(second.enqueue(queue));

      Objects.requireNonNull(first.waiter).cancel();
      assertEquals(1, queue.waiting());
      held.release();

      assertTrue(second.granted());
      assertFalse(first.granted());
      channel.advanceTimeBy(MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
      channel.runScheduledPendingTasks();
      assertFalse(first.expired, "a cancelled waiter is told nothing");
    }

    @Test
    @DisplayName("should count the time waited by those granted from the line")
    void countsWait() {
      VerificationQueue queue = queue(1, 10);
      VerificationQueue.Slot held = queue.tryAcquire();
      assertNotNull(held);
      Login waiting = new Login(1, "waiting");
      assertTrue(waiting.enqueue(queue));
      nanos.addAndGet(TimeUnit.SECONDS.toNanos(3));

      held.release();

      assertTrue(waiting.granted());
      assertEquals(3.0, queue.waitedSeconds(), 0.001);
    }
  }
}
