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
package dev.warp.api.event.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.warp.api.server.RegisteredServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Server registry events")
class ServerRegistryEventsTest {

  private final RegisteredServer server = mock(RegisteredServer.class);
  private final RegisteredServer previous = mock(RegisteredServer.class);

  @Test
  @DisplayName("should tell a new name from a replaced address")
  void replacedServer() {
    ServerRegisteredEvent added = new ServerRegisteredEvent(server, null);
    ServerRegisteredEvent replaced = new ServerRegisteredEvent(server, previous);

    assertTrue(added.replacedServer().isEmpty());
    assertEquals(previous, replaced.replacedServer().orElseThrow());
  }

  @Test
  @DisplayName("should refuse a missing server")
  @SuppressWarnings("NullAway")
  void nulls() {
    assertThrows(NullPointerException.class, () -> new ServerRegisteredEvent(null, previous));
    assertThrows(NullPointerException.class, () -> new ServerUnregisteredEvent(null));
  }
}
