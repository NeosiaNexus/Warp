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
package dev.warp.proxy.messaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.warp.proxy.server.BackendServer;
import dev.warp.proxy.server.ServerRegistry;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Held forwards")
class HeldForwardsTest {

  private static final long HOLD = TimeUnit.SECONDS.toNanos(30);

  private final AtomicLong now = new AtomicLong();
  private final List<String> drops = new ArrayList<>();
  private final HeldForwards held =
      new HeldForwards(
          HOLD, now::get, (server, from, count, why) -> drops.add(server + " " + count));
  private final ServerRegistry registry =
      new ServerRegistry(
          Map.of(
              "survival", InetSocketAddress.createUnresolved("survival", 25565),
              "creative", InetSocketAddress.createUnresolved("creative", 25565)),
          null,
          List.of());
  private final BackendServer survival = Objects.requireNonNull(registry.getServer("survival"));
  private final BackendServer creative = Objects.requireNonNull(registry.getServer("creative"));

  @Nested
  @DisplayName("delivery")
  class Delivery {

    @Test
    @DisplayName("should deliver a server's messages in order, once")
    void inOrder() {
      held.hold(survival, new byte[] {1}, "lobby");
      held.hold(survival, new byte[] {2}, "lobby");
      held.hold(creative, new byte[] {3}, "lobby");
      List<byte[]> delivered = new ArrayList<>();

      int count = held.deliver(survival, data -> delivered.add(data));

      assertEquals(2, count);
      assertArrayEquals(new byte[] {1}, delivered.get(0));
      assertArrayEquals(new byte[] {2}, delivered.get(1));
      assertEquals(0, held.deliver(survival, data -> true), "delivered once");
      assertEquals(1, held.held(creative), "other servers keep theirs");
    }

    @Test
    @DisplayName("should not deliver a message that waited longer than the hold")
    void stale() {
      held.hold(survival, new byte[] {1}, "lobby");
      now.addAndGet(HOLD + 1);

      int count = held.deliver(survival, data -> true);

      assertEquals(0, count);
      assertEquals(List.of("survival 1"), drops);
    }

    @Test
    @DisplayName("should drop a message the carrier cannot send, and say so")
    void refused() {
      held.hold(survival, new byte[] {1}, "lobby");

      assertEquals(0, held.deliver(survival, data -> false));
      assertEquals(List.of("survival 1"), drops);
      assertEquals(0, held.bytes());
    }
  }

  @Nested
  @DisplayName("expiry")
  class Expiry {

    @Test
    @DisplayName("should drop the messages that waited too long, and forget the server")
    void expire() {
      held.hold(survival, new byte[] {1, 2}, "lobby");
      now.addAndGet(HOLD / 2);
      held.hold(survival, new byte[] {3}, "lobby");
      now.addAndGet(HOLD / 2 + 1);

      held.expire();

      assertEquals(1, held.held(survival), "the younger one stays");
      assertEquals(1, held.bytes());
      assertEquals(List.of("survival 1"), drops);
      now.addAndGet(HOLD);
      held.expire();
      assertEquals(0, held.held(survival));
      assertEquals(0, held.bytes());
    }
  }

  @Nested
  @DisplayName("limits")
  class Limits {

    @Test
    @DisplayName("should refuse more than the per-server limit")
    void perServer() {
      for (int i = 0; i < HeldForwards.MAX_PER_SERVER; i++) {
        assertTrue(held.hold(survival, new byte[] {1}, "lobby"));
      }

      assertFalse(held.hold(survival, new byte[] {1}, "lobby"));
      assertTrue(held.hold(creative, new byte[] {1}, "lobby"), "other servers are not affected");
      assertEquals(HeldForwards.MAX_PER_SERVER, held.held(survival));
    }

    @Test
    @DisplayName("should refuse more than the byte limit across servers")
    void bytes() {
      byte[] big = new byte[(int) (HeldForwards.MAX_BYTES / 2)];
      assertTrue(held.hold(survival, big, "lobby"));
      assertTrue(held.hold(creative, big, "lobby"));

      assertFalse(held.hold(survival, new byte[] {1}, "lobby"));
      assertEquals(HeldForwards.MAX_BYTES, held.bytes());
    }
  }
}
