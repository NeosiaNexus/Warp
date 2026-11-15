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
package dev.warp.proxy.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FloodgateReplayGuard")
class FloodgateReplayGuardTest {

  private final AtomicLong clock = new AtomicLong();

  private static byte[] tag(int n) {
    byte[] tag = new byte[16];
    tag[0] = (byte) (n >>> 8);
    tag[1] = (byte) n;
    return tag;
  }

  @Test
  @DisplayName("should accept a tag once and refuse it again within the window")
  void replay() {
    FloodgateReplayGuard guard = new FloodgateReplayGuard(Duration.ofMinutes(10), 100, clock::get);

    assertTrue(guard.accept(tag(1)));
    assertFalse(guard.accept(tag(1)));
    assertTrue(guard.accept(tag(2)));
    clock.addAndGet(Duration.ofMinutes(9).toNanos());
    assertFalse(guard.accept(tag(1)));
  }

  @Test
  @DisplayName("should accept a tag again once its window is over")
  void expires() {
    FloodgateReplayGuard guard = new FloodgateReplayGuard(Duration.ofMinutes(10), 100, clock::get);

    assertTrue(guard.accept(tag(1)));
    clock.addAndGet(Duration.ofMinutes(10).toNanos());
    assertTrue(guard.accept(tag(1)));
    assertFalse(guard.accept(tag(1)));
  }

  @Test
  @DisplayName("should never hold more than its capacity, dropping expired entries first")
  void bounded() {
    FloodgateReplayGuard guard = new FloodgateReplayGuard(Duration.ofMinutes(10), 4, clock::get);

    assertTrue(guard.accept(tag(1)));
    assertTrue(guard.accept(tag(2)));
    clock.addAndGet(Duration.ofMinutes(10).toNanos());
    assertTrue(guard.accept(tag(3)));
    assertTrue(guard.accept(tag(4)));
    // Full: the two expired entries go, the new one fits.
    assertTrue(guard.accept(tag(5)));
    assertEquals(3, guard.size());
    assertFalse(guard.accept(tag(3)));
    assertFalse(guard.accept(tag(5)));
    // Full of live entries: one makes room, the table never exceeds the capacity.
    assertTrue(guard.accept(tag(6)));
    assertTrue(guard.accept(tag(7)));
    assertTrue(guard.size() <= 4, "size " + guard.size());
  }
}
