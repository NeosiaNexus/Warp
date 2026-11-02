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

import dev.warp.api.event.ResultedEvent;
import dev.warp.api.player.Player;

import net.kyori.adventure.text.Component;

/**
 * Fired once the player is authenticated and its profile final, before the client hears that its
 * login succeeded. The last chance to refuse it: load what the player needs here (ranks,
 * punishments), asynchronously if it takes time.
 *
 * <p>A denied login never starts a session: no {@link PostLoginEvent} and no {@link
 * DisconnectEvent} follow.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface LoginEvent extends ResultedEvent<LoginEvent.Result> {

  /**
   * Returns the player logging in.
   *
   * @return the player
   */
  Player player();

  /** Whether the login goes on. The default is {@link Allowed}. */
  sealed interface Result {}

  /** Let the player in. */
  record Allowed() implements Result {}

  /**
   * Refuse the player.
   *
   * @param reason shown to the player
   */
  record Denied(Component reason) implements Result {}
}
