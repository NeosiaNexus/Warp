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

import java.util.Optional;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * Fired when a player loses a server: the server kicked it, the connection was lost or timed out,
 * or the server could not be reached or refused it while the player was being moved there. Never
 * fired when the player itself leaves the proxy.
 *
 * <p>The proxy proposes a result: when the player was being moved and still has its current server,
 * it stays there and is notified; otherwise it is redirected down the fallback order, or
 * disconnected when no server is left. Handlers may decide otherwise, asynchronously if they need
 * to wait (for example for a lobby to come back): the player waits on its loading screen meanwhile,
 * up to the handler's time-out.
 *
 * <p>A redirect to a server that already failed during this chain of failures, or to the server
 * that was just lost, disconnects the player instead, so that two failing servers can never bounce
 * a player forever.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface KickedFromServerEvent extends ResultedEvent<KickedFromServerEvent.Result> {

  /**
   * Returns the player.
   *
   * @return the player
   */
  Player player();

  /**
   * Returns the server that was lost.
   *
   * @return the server
   */
  RegisteredServer server();

  /**
   * Returns how the server was lost.
   *
   * @return the cause
   */
  Cause cause();

  /**
   * Returns the reason the server gave, when it kicked or refused the player.
   *
   * @return the reason, or empty if it gave none (lost connection, time-out, unreachable). An empty
   *     component is a reason too: some servers kick with one to send players back to a lobby
   *     without a message.
   */
  Optional<Component> serverKickReason();

  /**
   * Returns whether the player was being moved to this server (first connection, switch or
   * fallback) rather than playing on it.
   *
   * @return {@code true} if it never played on this server during this connection
   */
  boolean kickedDuringServerConnect();

  /** How a server was lost. */
  enum Cause {
    /** The server disconnected the player, or refused its login. */
    KICKED,
    /** The connection to the server closed without a reason. */
    CONNECTION_LOST,
    /** The server could not be reached. */
    UNREACHABLE,
    /** The switch to the server took too long. */
    TIMED_OUT
  }

  /** What happens to the player. */
  sealed interface Result {}

  /**
   * Send the player to another server.
   *
   * @param server the server
   * @param message shown to the player, or {@code null} for none
   */
  record Redirect(RegisteredServer server, @Nullable Component message) implements Result {}

  /**
   * Disconnect the player.
   *
   * @param reason shown to the player
   */
  record DisconnectPlayer(Component reason) implements Result {}

  /**
   * Keep the player on the server it plays on, and tell it. Only possible when it was being moved
   * and still has its current server; otherwise it is disconnected with this message.
   *
   * @param message shown to the player
   */
  record Notify(Component message) implements Result {}
}
