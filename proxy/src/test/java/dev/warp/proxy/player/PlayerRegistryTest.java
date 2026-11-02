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
package dev.warp.proxy.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.warp.proxy.connection.ConnectedPlayer;
import dev.warp.proxy.player.PlayerRegistry.Registration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PlayerRegistry")
class PlayerRegistryTest {

  private final PlayerRegistry registry = new PlayerRegistry();

  @Nested
  @DisplayName("register()")
  class Register {

    @Test
    @DisplayName("should make a player reachable by UUID and by name in any case")
    void registers() {
      ConnectedPlayer steve = player(UUID.randomUUID(), "Steve");

      Registration result = registry.register(steve, false);

      assertEquals(Registration.REGISTERED, result);
      assertSame(steve, registry.get(steve.uuid()));
      assertSame(steve, registry.get("sTeVe"));
      assertEquals(1, registry.count());
      assertEquals(List.of(steve), registry.all());
    }

    @Test
    @DisplayName(
        "should refuse a second session of the same account when the policy keeps the first")
    void refusesDuplicate() {
      UUID id = UUID.randomUUID();
      ConnectedPlayer first = player(id, "Steve");
      ConnectedPlayer second = player(id, "Steve");
      registry.register(first, false);

      Registration result = registry.register(second, false);

      assertEquals(Registration.REFUSED, result);
      assertSame(first, registry.get(id));
      verify(first, never()).disconnectDuplicate();
    }

    @Test
    @DisplayName(
        "should kick the existing session and register the newcomer when the policy says so")
    void kicksExisting() {
      UUID id = UUID.randomUUID();
      ConnectedPlayer first = player(id, "Steve");
      ConnectedPlayer second = player(id, "Steve");
      registry.register(first, true);

      Registration result = registry.register(second, true);

      assertEquals(Registration.REGISTERED, result);
      verify(first).disconnectDuplicate();
      assertSame(second, registry.get(id));
      assertSame(second, registry.get("steve"));
      assertEquals(1, registry.count());
    }

    @Test
    @DisplayName("should accept registering the same player twice")
    void idempotent() {
      ConnectedPlayer steve = player(UUID.randomUUID(), "Steve");
      registry.register(steve, false);

      assertEquals(Registration.REGISTERED, registry.register(steve, false));
      assertEquals(1, registry.count());
    }

    @Test
    @DisplayName("should register exactly one of several sessions racing for the same account")
    void oneWinnerUnderContention() throws InterruptedException {
      UUID id = UUID.randomUUID();
      int threads = 8;
      List<ConnectedPlayer> sessions = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        sessions.add(player(id, "Steve"));
      }
      ExecutorService pool = Executors.newFixedThreadPool(threads);
      CountDownLatch start = new CountDownLatch(1);
      List<Registration> results = java.util.Collections.synchronizedList(new ArrayList<>());
      try {
        for (ConnectedPlayer session : sessions) {
          pool.execute(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                results.add(registry.register(session, false));
              });
        }
        start.countDown();
      } finally {
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
      }

      assertEquals(1, results.stream().filter(r -> r == Registration.REGISTERED).count());
      assertEquals(1, registry.count());
    }
  }

  @Nested
  @DisplayName("unregister()")
  class Unregister {

    @Test
    @DisplayName("should remove a player from both indexes")
    void removes() {
      ConnectedPlayer steve = player(UUID.randomUUID(), "Steve");
      registry.register(steve, false);

      registry.unregister(steve);

      assertNull(registry.get(steve.uuid()));
      assertNull(registry.get("Steve"));
      assertEquals(0, registry.count());
    }

    @Test
    @DisplayName("should leave alone the session that replaced a kicked one")
    void staleUnregisterIsIgnored() {
      UUID id = UUID.randomUUID();
      ConnectedPlayer first = player(id, "Steve");
      ConnectedPlayer second = player(id, "Steve");
      registry.register(first, true);
      registry.register(second, true);

      registry.unregister(first);

      assertSame(second, registry.get(id));
      assertSame(second, registry.get("steve"));
    }
  }

  /** A player that, like the real one, unregisters itself when kicked as a duplicate. */
  private ConnectedPlayer player(UUID id, String name) {
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.uuid()).thenReturn(id);
    when(player.username()).thenReturn(name);
    doAnswer(
            invocation -> {
              registry.unregister(player);
              return null;
            })
        .when(player)
        .disconnectDuplicate();
    return player;
  }
}
