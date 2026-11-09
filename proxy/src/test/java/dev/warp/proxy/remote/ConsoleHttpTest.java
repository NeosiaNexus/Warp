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
package dev.warp.proxy.remote;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ConsoleHttp")
class ConsoleHttpTest {

  @Nested
  @DisplayName("loopback")
  class Loopback {

    @Test
    @DisplayName("should recognize localhost and literal loopback addresses")
    void loopback() {
      assertTrue(ConsoleHttp.loopback("127.0.0.1"));
      assertTrue(ConsoleHttp.loopback("127.0.0.53"));
      assertTrue(ConsoleHttp.loopback("localhost"));
      assertTrue(ConsoleHttp.loopback("LOCALHOST"));
      assertTrue(ConsoleHttp.loopback("::1"));
      assertTrue(ConsoleHttp.loopback("[::1]"));
    }

    @Test
    @DisplayName("should not trust names or other addresses, even ones resolving to loopback")
    void notLoopback() {
      assertFalse(ConsoleHttp.loopback(null));
      assertFalse(ConsoleHttp.loopback("warp-management.minecraft.svc"));
      assertFalse(ConsoleHttp.loopback("10.0.0.1"));
      assertFalse(ConsoleHttp.loopback("fe80::1"));
      assertFalse(ConsoleHttp.loopback("localhost.example.com"));
      assertFalse(ConsoleHttp.loopback(""));
    }
  }
}
