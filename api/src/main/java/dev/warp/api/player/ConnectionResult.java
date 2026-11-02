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
package dev.warp.api.player;

import dev.warp.api.server.RegisteredServer;

import java.util.Optional;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of {@link Player#connect(RegisteredServer)}.
 *
 * <p>Switch over it to handle every case; the compiler tells when a case is missing.
 */
public sealed interface ConnectionResult {

  /**
   * The player now plays on the server.
   *
   * @param server the server
   */
  record Connected(RegisteredServer server) implements ConnectionResult {}

  /**
   * The player already plays on the server: nothing was done.
   *
   * @param server the server
   */
  record AlreadyConnected(RegisteredServer server) implements ConnectionResult {}

  /**
   * Another switch is in progress: nothing was done.
   *
   * @param pending the server the other switch moves the player to
   */
  record InProgress(RegisteredServer pending) implements ConnectionResult {}

  /**
   * A plugin denied the switch in {@link dev.warp.api.event.player.ServerPreConnectEvent}. The
   * player stays where it was.
   *
   * @param server the server it was not allowed to join
   */
  record Denied(RegisteredServer server) implements ConnectionResult {}

  /**
   * The server could not be reached, refused the player, or the switch timed out. The player stays
   * where it was, unless its current server was lost meanwhile.
   *
   * @param server the server
   * @param reason the server's reason when it gave one
   */
  record Failed(RegisteredServer server, @Nullable Component reason) implements ConnectionResult {

    /**
     * Returns the server's reason.
     *
     * @return the reason, or empty if the server gave none (unreachable, timed out)
     */
    public Optional<Component> serverReason() {
      return Optional.ofNullable(reason);
    }
  }

  /** The player disconnected before the switch completed. */
  record Disconnected() implements ConnectionResult {}

  /**
   * Returns whether the player plays on the requested server.
   *
   * @return {@code true} for {@link Connected} and {@link AlreadyConnected}
   */
  default boolean isSuccessful() {
    return this instanceof Connected || this instanceof AlreadyConnected;
  }
}
