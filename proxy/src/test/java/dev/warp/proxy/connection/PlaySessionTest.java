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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PlaySession")
class PlaySessionTest {

  private static final UUID NIL = new UUID(0, 0);

  private final PlaySession session = new PlaySession();

  @Nested
  @DisplayName("one player after another")
  class Sequential {

    @Test
    @DisplayName("should give every player online at the same time the same ID")
    void sharedWhileOnline() {
      UUID first = session.join();
      UUID second = session.join();
      session.leave();
      UUID third = session.join();

      assertEquals(first, second);
      assertEquals(first, third);
    }

    @Test
    @DisplayName("should start a new session once the last player left")
    void newAfterEmpty() {
      UUID first = session.join();
      session.leave();

      UUID next = session.join();

      assertNotEquals(first, next);
    }

    @Test
    @DisplayName("should never hand out the ID of the empty proxy")
    void neverNil() {
      UUID first = session.join();

      assertNotEquals(NIL, first);
    }

    @Test
    @DisplayName("should ignore a leave without a player")
    void unmatchedLeave() {
      session.leave();

      UUID first = session.join();
      UUID second = session.join();

      assertNotEquals(NIL, first);
      assertEquals(first, second);
    }
  }

  @Nested
  @DisplayName("players on many event loops")
  class Concurrent {

    private static final int THREADS = 8;
    private static final int PLAYERS_PER_THREAD = 500;

    @Test
    @DisplayName("should hand one ID to players joining together, and a new one once all left")
    void joinAndLeaveTogether() throws Exception {
      Set<UUID> ids = ConcurrentHashMap.newKeySet();

      onEveryThread(
          () -> {
            for (int i = 0; i < PLAYERS_PER_THREAD; i++) {
              ids.add(session.join());
            }
          });
      UUID together = ids.iterator().next();
      onEveryThread(
          () -> {
            for (int i = 0; i < PLAYERS_PER_THREAD; i++) {
              session.leave();
            }
          });
      UUID afterwards = session.join();

      assertEquals(Set.of(together), ids);
      assertNotEquals(together, afterwards);
    }

    /** Runs {@code task} on {@link #THREADS} threads at once, and waits for all of them. */
    private void onEveryThread(Runnable task) throws Exception {
      CountDownLatch start = new CountDownLatch(1);
      try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
          done.add(
              executor.submit(
                  () -> {
                    start.await();
                    task.run();
                    return null;
                  }));
        }
        start.countDown();
        for (Future<?> future : done) {
          future.get();
        }
      }
    }
  }
}
