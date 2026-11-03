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
package dev.warp.api.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RegistrationResult")
class RegistrationResultTest {

  private final RegisteredServer server = mock(RegisteredServer.class);
  private final RegisteredServer previous = mock(RegisteredServer.class);

  @Test
  @DisplayName("should expose the registered server whatever happened")
  void server() {
    List<RegistrationResult> results =
        List.of(
            new RegistrationResult.Added(server),
            new RegistrationResult.Unchanged(server),
            new RegistrationResult.Replaced(server, previous));

    for (RegistrationResult result : results) {
      assertSame(server, result.server(), result.toString());
    }
  }

  @Test
  @DisplayName("should let a switch tell what happened, with the server replaced")
  void exhaustiveSwitch() {
    RegistrationResult result = new RegistrationResult.Replaced(server, previous);

    String described =
        switch (result) {
          case RegistrationResult.Added added -> "added";
          case RegistrationResult.Unchanged unchanged -> "unchanged";
          case RegistrationResult.Replaced replaced ->
              replaced.previous() == previous ? "replaced" : "wrong previous";
        };

    assertEquals("replaced", described);
  }

  @Test
  @DisplayName("should refuse missing servers")
  @SuppressWarnings("NullAway")
  void nulls() {
    assertThrows(NullPointerException.class, () -> new RegistrationResult.Added(null));
    assertThrows(NullPointerException.class, () -> new RegistrationResult.Unchanged(null));
    assertThrows(NullPointerException.class, () -> new RegistrationResult.Replaced(server, null));
    assertThrows(NullPointerException.class, () -> new RegistrationResult.Replaced(null, server));
  }
}
