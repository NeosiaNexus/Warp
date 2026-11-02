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
import dev.warp.api.server.RegisteredServer;

import java.util.Objects;
import java.util.Optional;

import net.kyori.adventure.text.Component;

/**
 * Fired after {@link PostLoginEvent} to choose the first server a player joins, or to turn the
 * player away with a reason it sees.
 *
 * <p>The result starts as {@link Allowed} with the server the proxy proposes (its default server,
 * else the first registered server of its fallback order), or as {@link Denied} with the proxy's
 * "no server available" message when none of them is registered. Handlers may pick another server,
 * deny the player with their own reason, or let a server in when the proxy had none. The player
 * waits on the proxy, kept alive, while asynchronous handlers decide.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface PlayerChooseInitialServerEvent
    extends ResultedEvent<PlayerChooseInitialServerEvent.Result> {

  /**
   * Returns the player.
   *
   * @return the player
   */
  Player player();

  /**
   * Returns the server the proxy proposed before any handler ran, whatever the result became.
   *
   * @return the proposed server, or empty when none of the default and fallback servers is
   *     registered
   */
  Optional<RegisteredServer> proposedServer();

  /** Where the player goes first. */
  sealed interface Result {}

  /**
   * Send the player to a server. If it cannot be reached or refuses the player, the player goes
   * down the fallback order, as on any first connection.
   *
   * @param server the server
   */
  record Allowed(RegisteredServer server) implements Result {

    /**
     * Checks the server.
     *
     * @param server the server
     */
    public Allowed {
      Objects.requireNonNull(server, "server");
    }
  }

  /**
   * Disconnect the player before it joins any server.
   *
   * @param reason shown to the player on its disconnection screen
   */
  record Denied(Component reason) implements Result {

    /**
     * Checks the reason.
     *
     * @param reason shown to the player on its disconnection screen
     */
    public Denied {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
