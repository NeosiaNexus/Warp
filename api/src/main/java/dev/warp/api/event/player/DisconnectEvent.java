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

import dev.warp.api.player.Player;

/**
 * Fired once a player's session ended, exactly once per {@link PostLoginEvent}. The player is no
 * longer reachable through the proxy when it fires; its network resources are already released.
 *
 * @param player the player that left
 * @param cause why the session ended
 */
public record DisconnectEvent(Player player, Cause cause) {

  /** Why a session ended. */
  public enum Cause {
    /** The client closed the connection (quit, crash, network loss). */
    CLIENT_DISCONNECTED,
    /** The client stopped answering keep-alives. */
    TIMED_OUT,
    /** The proxy or a plugin disconnected the player. */
    DISCONNECTED_BY_PROXY,
    /** The same account logged in again and the proxy kept the new session. */
    CONFLICTING_LOGIN,
    /** No server could take the player. */
    NO_SERVER_AVAILABLE,
    /** The proxy shut down. */
    PROXY_SHUTDOWN
  }
}
