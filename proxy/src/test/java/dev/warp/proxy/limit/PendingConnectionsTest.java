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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PendingConnections")
class PendingConnectionsTest {

  private static final Duration GRACE = Duration.ofMillis(250);

  /** Starts away from zero: the table must keep its times from its own creation. */
  private final AtomicLong clock = new AtomicLong(-5_000_000_000L);

  private final List<String> closed = new ArrayList<>();

  /** What other "event loops" were asked to run, in order, until the test runs it. */
  private final Queue<Runnable> asked = new ArrayDeque<>();

  private final List<ExecutorService> threads = new ArrayList<>();

  @AfterEach
  void stopThreads() {
    threads.forEach(ExecutorService::shutdownNow);
  }

  // ---------------------------------------------------------------------------
  // Count
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("count")
  class Count {

    @Test
    @DisplayName("should count connections up to the cap and give places back")
    void countsUpToTheCap() {
      PendingConnections table = table(2);

      assertTrue(table.tryStart());
      assertTrue(table.tryStart());
      assertEquals(2, table.count());
      assertEquals(2, table.max());

      table.end();

      assertEquals(1, table.count());
      assertTrue(table.tryStart());
    }

    @Test
    @DisplayName("should refuse a newcomer over the cap when it tracks nothing, and not count it")
    void refusesWhenNothingTracked() {
      PendingConnections table = table(2);
      table.tryStart();
      table.tryStart();
      pass(GRACE.multipliedBy(4));

      assertFalse(table.tryStart());
      assertEquals(2, table.count());
    }

    @Test
    @DisplayName("should count without a cap and close nothing when the cap is 0")
    void noCap() {
      PendingConnections table = table(0);
      for (int i = 0; i < 100; i++) {
        assertTrue(table.tryStart());
        table.track(new Tracked(table, "c" + i), Runnable::run);
      }
      pass(GRACE.multipliedBy(4));

      assertTrue(table.tryStart());
      assertEquals(101, table.count());
      assertEquals(List.of(), closed);
    }

    @Test
    @DisplayName("should allow an eighth of the cap as slack, between 1 and 32")
    void slack() {
      assertEquals(1, table(1).slack());
      assertEquals(1, table(15).slack());
      assertEquals(2, table(16).slack());
      assertEquals(32, table(256).slack());
      assertEquals(32, table(8192).slack());
    }
  }

  // ---------------------------------------------------------------------------
  // Who is closed
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("making room on the newcomer's event loop")
  class Local {

    @Test
    @DisplayName("should close the oldest silent connection and let the newcomer in")
    void closesOldestSilent() {
      PendingConnections table = table(3);
      accept(table, "first");
      pass(Duration.ofMillis(10));
      accept(table, "second");
      pass(Duration.ofMillis(10));
      accept(table, "third");
      pass(GRACE);

      assertTrue(table.tryStart());

      assertEquals(List.of("first"), closed);
      assertEquals(3, table.count());
    }

    @Test
    @DisplayName("should close them in order of arrival, one per newcomer")
    void oneForOne() {
      PendingConnections table = table(3);
      accept(table, "first");
      accept(table, "second");
      accept(table, "third");
      pass(GRACE);

      assertTrue(table.tryStart());
      assertTrue(table.tryStart());
      assertTrue(table.tryStart());

      assertEquals(List.of("first", "second", "third"), closed);
      assertEquals(3, table.count());
      assertFalse(table.tryStart());
    }

    @Test
    @DisplayName("should not close a connection younger than the grace, and refuse the newcomer")
    void graceProtectsTheYoung() {
      PendingConnections table = table(2);
      accept(table, "first");
      accept(table, "second");
      pass(GRACE.minusNanos(1));

      assertFalse(table.tryStart());

      assertEquals(List.of(), closed);
      assertEquals(2, table.count());
    }

    @Test
    @DisplayName("should close a connection exactly as old as the grace")
    void graceBoundary() {
      PendingConnections table = table(1);
      accept(table, "first");
      pass(GRACE);

      assertTrue(table.tryStart());

      assertEquals(List.of("first"), closed);
    }

    @Test
    @DisplayName("should close only those past the grace when the others are younger")
    void onlyThosePastTheGrace() {
      PendingConnections table = table(2);
      accept(table, "old");
      pass(GRACE);
      accept(table, "young");

      assertTrue(table.tryStart());
      assertFalse(table.tryStart());

      assertEquals(List.of("old"), closed);
    }

    @Test
    @DisplayName("should close a silent connection before an engaged one that stalled for longer")
    void silentBeforeEngaged() {
      PendingConnections table = table(2);
      Tracked engaged = accept(table, "engaged");
      table.progressed(engaged);
      pass(GRACE);
      accept(table, "silent");
      pass(GRACE);

      assertTrue(table.tryStart());

      assertEquals(List.of("silent"), closed);
    }

    @Test
    @DisplayName("should close an engaged connection when no silent one is past the grace")
    void engagedWhenNoSilent() {
      PendingConnections table = table(2);
      Tracked engaged = accept(table, "engaged");
      table.progressed(engaged);
      pass(GRACE);
      accept(table, "young and silent");

      assertTrue(table.tryStart());

      assertEquals(List.of("engaged"), closed);
    }

    @Test
    @DisplayName("should close engaged connections in order of their last progress")
    void engagedByLastProgress() {
      PendingConnections table = table(3);
      Tracked first = accept(table, "first");
      Tracked second = accept(table, "second");
      Tracked third = accept(table, "third");
      table.progressed(second);
      pass(Duration.ofMillis(1));
      table.progressed(third);
      pass(Duration.ofMillis(1));
      table.progressed(first);
      pass(Duration.ofMillis(1));
      table.progressed(second);
      pass(GRACE);

      assertTrue(table.tryStart());
      assertTrue(table.tryStart());
      assertTrue(table.tryStart());

      assertEquals(List.of("third", "first", "second"), closed);
    }

    @Test
    @DisplayName("should count the grace of an engaged connection from its last progress")
    void progressRestartsTheGrace() {
      PendingConnections table = table(1);
      Tracked engaged = accept(table, "engaged");
      pass(GRACE);
      table.progressed(engaged);
      pass(GRACE.minusNanos(1));

      assertFalse(table.tryStart());
      pass(Duration.ofNanos(1));
      assertTrue(table.tryStart());

      assertEquals(List.of("engaged"), closed);
    }

    @Test
    @DisplayName("should never close a settled connection, nor take its progress afterwards")
    void settledIsNeverClosed() {
      PendingConnections table = table(2);
      Tracked login = accept(table, "login");
      Tracked other = accept(table, "other");
      table.progressed(other);
      table.settle(login);
      table.settle(other);
      table.settle(other);
      table.progressed(login);
      pass(GRACE.multipliedBy(10));

      assertFalse(table.tryStart());

      assertEquals(List.of(), closed);
      assertEquals(2, table.count());
    }

    @Test
    @DisplayName("should keep the order when connections leave from the middle and the ends")
    void leavingKeepsTheOrder() {
      PendingConnections table = table(5);
      Tracked a = accept(table, "a");
      accept(table, "b");
      Tracked c = accept(table, "c");
      accept(table, "d");
      Tracked e = accept(table, "e");
      c.leave();
      a.leave();
      e.leave();
      Tracked f = accept(table, "f");
      accept(table, "g");
      accept(table, "h");
      f.leave();
      accept(table, "i");
      pass(GRACE);

      for (int i = 0; i < 5; i++) {
        assertTrue(table.tryStart());
      }

      assertEquals(List.of("b", "d", "g", "h", "i"), closed);
      assertFalse(table.tryStart());
    }

    @Test
    @DisplayName("should ignore the progress and the end of a connection it never tracked")
    void untracked() {
      PendingConnections table = table(1);
      assertTrue(table.tryStart());
      Tracked stranger = new Tracked(table, "stranger");

      table.progressed(stranger);
      table.settle(stranger);
      pass(GRACE);

      assertFalse(table.tryStart());
      assertEquals(List.of(), closed);
    }

    @Test
    @DisplayName("should never close a connection when the grace is zero")
    void zeroGraceNeverCloses() {
      PendingConnections table = new PendingConnections(1, Duration.ZERO, clock::get);
      accept(table, "first");
      pass(Duration.ofSeconds(10));

      assertFalse(table.tryStart());

      assertEquals(List.of(), closed);
      assertEquals(1, table.count());
    }

    @Test
    @DisplayName("should give each place back exactly once, closed to make room or not")
    void onePlacePerConnection() {
      PendingConnections table = table(2);
      Tracked first = accept(table, "first");
      Tracked second = accept(table, "second");
      pass(GRACE);
      assertTrue(table.tryStart());
      Tracked third = new Tracked(table, "third");
      table.track(third, Runnable::run);

      first.leave();
      second.leave();
      third.leave();

      assertEquals(0, table.count());
      assertEquals(1, first.ends.get());
      assertEquals(1, second.ends.get());
      assertEquals(1, third.ends.get());
    }
  }

  // ---------------------------------------------------------------------------
  // Other event loops
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("making room on another event loop")
  class Remote {

    @Test
    @DisplayName("should let the newcomer in at once and close the oldest when that loop runs")
    void asksTheOtherLoop() throws Exception {
      PendingConnections table = table(2);
      ExecutorService other = thread();
      on(other, () -> accept(table, "first", asked::add));
      on(other, () -> accept(table, "second", asked::add));
      pass(GRACE);

      assertTrue(table.tryStart());

      assertEquals(3, table.count());
      assertEquals(List.of(), closed);
      assertEquals(1, asked.size());

      on(other, asked.remove());

      assertEquals(List.of("first"), closed);
      assertEquals(2, table.count());
    }

    @Test
    @DisplayName("should ask a loop once however many newcomers wait on it, then again")
    void asksOnce() throws Exception {
      PendingConnections table = table(16);
      ExecutorService other = thread();
      for (int i = 0; i < 16; i++) {
        String name = "c" + i;
        on(other, () -> accept(table, name, asked::add));
      }
      pass(GRACE);

      assertTrue(table.tryStart());
      assertTrue(table.tryStart());
      assertEquals(1, asked.size());
      on(other, asked.remove());

      assertEquals(List.of("c0", "c1"), closed);
      assertEquals(16, table.count());

      assertTrue(table.tryStart());
      assertEquals(1, asked.size());
    }

    @Test
    @DisplayName("should refuse newcomers once the count is over the cap by the slack")
    void slackBoundsTheOvershoot() throws Exception {
      PendingConnections table = table(16);
      ExecutorService other = thread();
      for (int i = 0; i < 16; i++) {
        String name = "c" + i;
        on(other, () -> accept(table, name, asked::add));
      }
      pass(GRACE);

      assertTrue(table.tryStart());
      assertTrue(table.tryStart());
      assertFalse(table.tryStart());

      assertEquals(16 + table.slack(), table.count());
    }

    @Test
    @DisplayName("should refuse the newcomer when no loop has a connection past the grace")
    void nothingOldEnoughElsewhere() throws Exception {
      PendingConnections table = table(2);
      ExecutorService other = thread();
      on(other, () -> accept(table, "first", asked::add));
      on(other, () -> accept(table, "second", asked::add));
      pass(GRACE.minusNanos(1));

      assertFalse(table.tryStart());

      assertEquals(0, asked.size());
      assertEquals(2, table.count());
    }

    @Test
    @DisplayName("should refuse the newcomer when the other loops hold only settled connections")
    void nothingToCloseElsewhere() throws Exception {
      PendingConnections table = table(1);
      ExecutorService other = thread();
      on(other, () -> table.settle(accept(table, "login", asked::add)));
      pass(GRACE);

      assertFalse(table.tryStart());

      assertEquals(0, asked.size());
    }

    @Test
    @DisplayName("should ask the loop whose oldest connection stalled the longest")
    void asksTheOldest() throws Exception {
      PendingConnections table = table(3);
      Queue<Runnable> askedOfYoung = new ArrayDeque<>();
      Queue<Runnable> askedOfLate = new ArrayDeque<>();
      ExecutorService young = thread();
      ExecutorService old = thread();
      ExecutorService late = thread();
      on(old, () -> table.progressed(accept(table, "old", asked::add)));
      pass(Duration.ofMillis(1));
      on(young, () -> accept(table, "young", askedOfYoung::add));
      pass(Duration.ofMillis(1));
      on(late, () -> accept(table, "late", askedOfLate::add));
      pass(GRACE);

      assertTrue(table.tryStart());

      assertEquals(1, asked.size());
      assertEquals(0, askedOfYoung.size());
      assertEquals(0, askedOfLate.size());
    }

    @Test
    @DisplayName("should take a place on its own loop before asking another")
    void ownLoopFirst() throws Exception {
      PendingConnections table = table(2);
      ExecutorService other = thread();
      on(other, () -> accept(table, "elsewhere", asked::add));
      pass(Duration.ofMillis(1));
      accept(table, "here");
      pass(GRACE);

      assertTrue(table.tryStart());

      assertEquals(List.of("here"), closed);
      assertEquals(0, asked.size());
    }

    @Test
    @DisplayName("should pay back, on its own loop, what another loop could not close")
    void paysBackTheOvershoot() throws Exception {
      PendingConnections table = table(2);
      ExecutorService other = thread();
      Tracked[] elsewhere = new Tracked[1];
      on(other, () -> elsewhere[0] = accept(table, "elsewhere", asked::add));
      assertTrue(table.tryStart());
      Tracked here = new Tracked(table, "here");
      pass(GRACE);
      // Let in on the promise of the other loop, whose connection then gives its name.
      assertTrue(table.tryStart());
      Tracked newcomer = new Tracked(table, "newcomer");
      on(other, () -> table.settle(elsewhere[0]));
      on(other, asked.remove());
      assertEquals(List.of(), closed);
      assertEquals(3, table.count());
      table.track(here, Runnable::run);
      table.track(newcomer, Runnable::run);
      pass(GRACE);

      assertTrue(table.tryStart());

      assertEquals(List.of("here", "newcomer"), closed);
      assertEquals(2, table.count());
    }

    @Test
    @DisplayName("should let the newcomer in when the other loop is shutting down, and ask again")
    void loopShuttingDown() throws Exception {
      PendingConnections table = table(8);
      ExecutorService other = thread();
      AtomicInteger rejections = new AtomicInteger();
      Executor stopping =
          task -> {
            rejections.incrementAndGet();
            throw new RejectedExecutionException("shutting down");
          };
      on(other, () -> accept(table, "first", stopping));
      for (int i = 0; i < 7; i++) {
        assertTrue(table.tryStart());
      }
      pass(GRACE);

      assertTrue(table.tryStart());
      assertFalse(table.tryStart());

      assertEquals(9, table.count());
      assertEquals(1, rejections.get());
      table.end();
      assertTrue(table.tryStart());
      assertEquals(2, rejections.get());
    }
  }

  // ---------------------------------------------------------------------------
  // Several event loops at once
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("accepts on several event loops at once")
  class Concurrent {

    private static final int LOOPS = 4;
    private static final int CAP = 64;
    private static final int ACCEPTS_PER_LOOP = 20_000;

    private final AtomicInteger held = new AtomicInteger();
    private final AtomicInteger mostHeld = new AtomicInteger();
    private final AtomicInteger admitted = new AtomicInteger();
    private final AtomicInteger closedAfterLeaving = new AtomicInteger();
    private final Queue<Flooding> everyone = new ConcurrentLinkedQueue<>();

    @Test
    @DisplayName(
        "should keep the count exact, within the cap and its slack, and end each connection once")
    void exactUnderContention() throws Exception {
      // A real clock and no grace to speak of: every tracked connection may be closed at once.
      PendingConnections table = new PendingConnections(CAP, Duration.ofNanos(1), System::nanoTime);
      List<ExecutorService> loops = new ArrayList<>();
      List<Future<?>> floods = new ArrayList<>();
      for (int i = 0; i < LOOPS; i++) {
        loops.add(thread());
      }
      for (ExecutorService loop : loops) {
        // Each accept is its own task, so that what other loops ask runs in between.
        for (int i = 0; i < ACCEPTS_PER_LOOP; i++) {
          boolean stays = i % 7 == 0;
          floods.add(loop.submit(() -> flood(table, loop, stays)));
        }
      }
      for (Future<?> flood : floods) {
        flood.get(30, TimeUnit.SECONDS);
      }

      assertTrue(admitted.get() > CAP * 10, "the flood must have been let in: " + admitted.get());
      assertTrue(
          mostHeld.get() <= CAP + table.slack(),
          "held " + mostHeld.get() + " at once, over " + CAP + " + " + table.slack());

      for (ExecutorService loop : loops) {
        on(loop, () -> everyone.stream().filter(c -> c.loop == loop).forEach(Flooding::leave));
      }
      assertEquals(0, table.count());
      assertEquals(0, held.get());
      assertEquals(0, closedAfterLeaving.get());
      assertEquals(admitted.get(), everyone.stream().mapToInt(c -> c.ends.get()).sum());
    }

    private void flood(PendingConnections table, ExecutorService loop, boolean settles) {
      if (!table.tryStart()) {
        return;
      }
      admitted.incrementAndGet();
      mostHeld.accumulateAndGet(held.incrementAndGet(), Math::max);
      Flooding connection = new Flooding(table, loop);
      everyone.add(connection);
      table.track(connection, loop);
      if (settles) {
        // A login that gave its name, then left: it holds its place meanwhile.
        table.settle(connection);
        connection.leave();
      }
    }

    private final class Flooding extends PendingConnection {

      private final PendingConnections table;
      private final ExecutorService loop;
      private final AtomicInteger ends = new AtomicInteger();

      private Flooding(PendingConnections table, ExecutorService loop) {
        this.table = table;
        this.loop = loop;
      }

      private void leave() {
        if (ends.get() != 0) {
          return;
        }
        ends.incrementAndGet();
        table.settle(this);
        held.decrementAndGet();
        table.end();
      }

      @Override
      protected void evicted() {
        if (ends.get() != 0) {
          closedAfterLeaving.incrementAndGet();
        }
        leave();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private PendingConnections table(int cap) {
    return new PendingConnections(cap, GRACE, clock::get);
  }

  private void pass(Duration time) {
    clock.addAndGet(time.toNanos());
  }

  /** Counts and tracks a connection on the calling thread, which runs what is asked of it. */
  private Tracked accept(PendingConnections table, String name) {
    return accept(table, name, Runnable::run);
  }

  private Tracked accept(PendingConnections table, String name, Executor eventLoop) {
    assertTrue(table.tryStart(), name);
    Tracked connection = new Tracked(table, name);
    table.track(connection, eventLoop);
    return connection;
  }

  private ExecutorService thread() {
    ExecutorService thread = Executors.newSingleThreadExecutor();
    threads.add(thread);
    return thread;
  }

  private static void on(ExecutorService thread, Runnable task) throws Exception {
    thread.submit(task).get(10, TimeUnit.SECONDS);
  }

  /** A connection that records its close, and gives its place back once. */
  private final class Tracked extends PendingConnection {

    private final PendingConnections table;
    private final String name;
    private final AtomicInteger ends = new AtomicInteger();

    private Tracked(PendingConnections table, String name) {
      this.table = table;
      this.name = name;
    }

    private void leave() {
      if (ends.get() != 0) {
        return;
      }
      ends.incrementAndGet();
      table.settle(this);
      table.end();
    }

    @Override
    protected void evicted() {
      synchronized (closed) {
        closed.add(name);
      }
      leave();
    }
  }
}
