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

import java.util.Objects;

import net.kyori.adventure.text.Component;

/**
 * Fired when a draining proxy is about to deal with a player: once for each player connected when
 * the transfer phase starts, then each time a player arrives on a server while the proxy drains
 * (back from a match, or just logged in). The proxy applies the decision the last handler left, one
 * player at a time, at the configured rate.
 *
 * <p>The default decision is {@link Transfer} to {@code drain.transfer-to} when the client supports
 * transfers (Minecraft 1.20.5 and newer) and that address is set; otherwise {@link Keep}, or {@link
 * Kick} when {@code drain.cannot-transfer} is {@code kick}.
 *
 * <p>Keep players in the middle of something that must not be cut short, such as a match: the proxy
 * asks again when they arrive on another server, and moves them then. A network sending players
 * back to a lobby at the end of a match keeps everyone who is not on a lobby:
 *
 * <pre>{@code
 * @Subscribe
 * public void onDrain(PlayerDrainEvent event) {
 *   boolean onLobby = event.player().currentServer()
 *       .map(connection -> connection.server().name().startsWith("lobby"))
 *       .orElse(true);
 *   if (!onLobby) {
 *     event.setResult(new PlayerDrainEvent.Keep());
 *   }
 * }
 * }</pre>
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface PlayerDrainEvent extends ResultedEvent<PlayerDrainEvent.Decision> {

  /**
   * Returns the player the proxy is about to deal with.
   *
   * @return the player
   */
  Player player();

  /**
   * Returns why the proxy asks now.
   *
   * @return the reason
   */
  Reason reason();

  /** Why the proxy asks what to do with a player. */
  enum Reason {

    /** The transfer phase reached the player, connected when it started. */
    TRANSFER_PHASE,

    /**
     * The player arrived on a server while the proxy drains: back from a match it was kept for,
     * moved by a plugin, or just logged in.
     */
    SERVER_CHANGE
  }

  /** What the proxy does with the player. */
  sealed interface Decision permits Transfer, Keep, Kick {}

  /**
   * Transfer the player to {@code host:port} now (Minecraft 1.20.5 and newer): its client leaves
   * and connects there by itself. Point it at a public address that does not lead back here, such
   * as the load balancer once it stopped sending players to this proxy. A client older than 1.20.5
   * cannot be transferred: the proxy then does as {@code drain.cannot-transfer} says.
   *
   * @param host the host name or address the client connects to
   * @param port the port, 1 to 65535
   */
  record Transfer(String host, int port) implements Decision {

    /**
     * Checks the address.
     *
     * @param host the host name or address the client connects to
     * @param port the port, 1 to 65535
     * @throws IllegalArgumentException if {@code host} is blank or {@code port} is not 1 to 65535
     */
    public Transfer {
      Objects.requireNonNull(host, "host");
      if (host.isBlank()) {
        throw new IllegalArgumentException("Transfer host must not be blank");
      }
      if (port < 1 || port > 65_535) {
        throw new IllegalArgumentException("Transfer port must be 1 to 65535, got " + port);
      }
    }
  }

  /**
   * Keep the player for now: it stays until it leaves, the drain times out, or the proxy asks again
   * when it arrives on another server.
   */
  record Keep() implements Decision {}

  /**
   * Disconnect the player now.
   *
   * @param reason shown to the player
   */
  record Kick(Component reason) implements Decision {

    /**
     * Checks the reason is present.
     *
     * @param reason shown to the player
     */
    public Kick {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
