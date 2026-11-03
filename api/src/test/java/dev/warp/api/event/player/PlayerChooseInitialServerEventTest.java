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
package dev.warp.api.event.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import dev.warp.api.server.RegisteredServer;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PlayerChooseInitialServerEvent results")
class PlayerChooseInitialServerEventTest {

  @Nested
  @DisplayName("Allowed")
  class Allowed {

    @Test
    @DisplayName("should carry the server the player goes to")
    void server() {
      RegisteredServer server = mock(RegisteredServer.class);

      PlayerChooseInitialServerEvent.Allowed result =
          new PlayerChooseInitialServerEvent.Allowed(server);

      assertEquals(server, result.server());
    }

    @Test
    @DisplayName("should refuse a missing server, which used to mean a silent disconnection")
    @SuppressWarnings("NullAway")
    void noServer() {
      assertThrows(
          NullPointerException.class, () -> new PlayerChooseInitialServerEvent.Allowed(null));
    }
  }

  @Nested
  @DisplayName("Denied")
  class Denied {

    @Test
    @DisplayName("should carry the reason the player sees")
    void reason() {
      Component reason = Component.text("No lobby available");

      PlayerChooseInitialServerEvent.Denied result =
          new PlayerChooseInitialServerEvent.Denied(reason);

      assertEquals(reason, result.reason());
    }

    @Test
    @DisplayName("should refuse a missing reason")
    @SuppressWarnings("NullAway")
    void noReason() {
      assertThrows(
          NullPointerException.class, () -> new PlayerChooseInitialServerEvent.Denied(null));
    }
  }
}
