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
package dev.warp.proxy.permission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.warp.api.event.Subscription;
import dev.warp.api.permission.Tristate;
import dev.warp.api.player.Player;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PermissionRegistry")
class PermissionRegistryTest {

  private final PermissionRegistry registry = new PermissionRegistry();
  private final Player player = mock(Player.class);

  @Test
  @DisplayName("should refuse every permission without a provider")
  void noProvider() {
    assertFalse(registry.check(player, "lobby.use"));
  }

  @Test
  @DisplayName("should let the highest priority with an opinion decide")
  void priority() {
    registry.register("low", 0, (p, permission) -> Tristate.TRUE);
    registry.register("high", 10, (p, permission) -> Tristate.FALSE);

    assertFalse(registry.check(player, "lobby.use"));
  }

  @Test
  @DisplayName("should ask the next provider when one has no opinion")
  void undefined() {
    registry.register("high", 10, (p, permission) -> Tristate.UNDEFINED);
    registry.register("low", 0, (p, permission) -> Tristate.of(permission.startsWith("lobby.")));

    assertTrue(registry.check(player, "lobby.use"));
    assertFalse(registry.check(player, "admin.ban"));
  }

  @Test
  @DisplayName("should skip a provider that throws")
  void failing() {
    registry.register(
        "broken",
        10,
        (p, permission) -> {
          throw new IllegalStateException("database down");
        });
    registry.register("working", 0, (p, permission) -> Tristate.TRUE);

    assertTrue(registry.check(player, "lobby.use"));
  }

  @Test
  @DisplayName("should forget a provider once its registration is closed")
  void close() {
    Subscription registration = registry.register("perms", 0, (p, permission) -> Tristate.TRUE);

    registration.close();

    assertFalse(registration.isActive());
    assertFalse(registry.check(player, "lobby.use"));
  }

  @Nested
  @DisplayName("when permissions change")
  class Changes {

    private final Player other = mock(Player.class);

    @Test
    @DisplayName("should refresh the command list of the player a provider names")
    void changed() {
      registry.trackPlayers(() -> List.of(player, other));

      registry.changed(player);

      verify(player).refreshCommands();
      verify(other, never()).refreshCommands();
    }

    @Test
    @DisplayName("should refresh every online player when all may have changed")
    void allChanged() {
      registry.trackPlayers(() -> List.of(player, other));

      registry.allChanged();

      verify(player).refreshCommands();
      verify(other).refreshCommands();
    }

    @Test
    @DisplayName("should refresh every online player when a provider comes or goes")
    void providers() {
      registry.trackPlayers(() -> List.of(player));

      Subscription registration = registry.register("perms", 0, (p, permission) -> Tristate.TRUE);
      registration.close();
      registration.close();

      verify(player, times(2)).refreshCommands();
    }

    @Test
    @DisplayName("should refresh nobody before it knows the online players")
    void untracked() {
      registry.register("perms", 0, (p, permission) -> Tristate.TRUE);
      clearInvocations(player);

      registry.allChanged();

      verify(player, never()).refreshCommands();
    }
  }
}
