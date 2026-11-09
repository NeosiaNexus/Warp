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
package dev.warp.proxy.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChallengeTable")
class ChallengeTableTest {

  private static final byte[] A = {10, 0, 0, 1};
  private static final byte[] B = {10, 0, 0, 2};
  private static final byte[] A6 = {
    0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
  };

  private final ChallengeTable table = new ChallengeTable(new SplittableRandom(42));

  @Test
  @DisplayName("should accept the token it issued, from the same address and port, before expiry")
  void acceptsOwnToken() {
    long id = ChallengeTable.identity(A, 40000);

    int token = table.issue(id, 0);

    assertTrue(token > 0);
    assertTrue(table.matches(id, token, TimeUnit.SECONDS.toNanos(29)));
  }

  @Test
  @DisplayName("should refuse the token once 30 seconds passed")
  void expires() {
    long id = ChallengeTable.identity(A, 40000);
    int token = table.issue(id, 0);

    assertFalse(table.matches(id, token, TimeUnit.SECONDS.toNanos(30)));
  }

  @Test
  @DisplayName("should refuse a wrong token")
  void wrongToken() {
    long id = ChallengeTable.identity(A, 40000);
    int token = table.issue(id, 0);

    assertFalse(table.matches(id, token + 1, 0));
    assertFalse(table.matches(id, 0, 0));
  }

  @Test
  @DisplayName("should bind the token to the port, not the address alone")
  void boundToPort() {
    long id = ChallengeTable.identity(A, 40000);
    int token = table.issue(id, 0);

    assertFalse(table.matches(ChallengeTable.identity(A, 40001), token, 0));
    assertFalse(table.matches(ChallengeTable.identity(B, 40000), token, 0));
    assertNotEquals(ChallengeTable.identity(A, 40000), ChallengeTable.identity(A6, 40000));
  }

  @Test
  @DisplayName("should replace the token of an endpoint that shakes hands again")
  void replaces() {
    long id = ChallengeTable.identity(A, 40000);
    int first = table.issue(id, 0);
    int second = table.issue(id, 1);

    assertNotEquals(first, second);
    assertFalse(table.matches(id, first, 2));
    assertTrue(table.matches(id, second, 2));
  }

  @Test
  @DisplayName("should hold a bounded number of slots whatever the number of endpoints")
  void bounded() {
    // Far more endpoints than slots: no growth, just overwritten slots.
    for (int port = 1; port < 3 * ChallengeTable.SLOTS; port++) {
      table.issue(ChallengeTable.identity(A, port & 0xFFFF), 0);
    }
    assertEquals(ChallengeTable.SLOTS, 65_536);
  }

  @Test
  @DisplayName("should survive a wrapping clock")
  void clockWraps() {
    long id = ChallengeTable.identity(A, 40000);
    long now = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1);

    int token = table.issue(id, now);

    assertTrue(table.matches(id, token, now + TimeUnit.SECONDS.toNanos(10)));
    assertFalse(table.matches(id, token, now + TimeUnit.SECONDS.toNanos(31)));
  }
}
