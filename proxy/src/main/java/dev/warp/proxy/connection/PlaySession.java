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
package dev.warp.proxy.connection;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The proxy's play session: the ID a 26.2+ client receives in its Login Success, and reports in its
 * telemetry.
 *
 * <p>A vanilla server shares one ID among all its connections and draws a new one once it has none
 * left ({@code ServerConnectionListener#getSessionId}): a session lasts as long as the server is
 * never empty. The proxy does the same with its players, as Velocity does: the first player to join
 * an empty proxy starts a session, the last one to leave ends it.
 *
 * <p>Lock-free: players join and leave on any event loop.
 */
public final class PlaySession {

  /** Before the first player: the nil ID, never handed out since the first player draws one. */
  private final AtomicReference<State> state = new AtomicReference<>(new State(new UUID(0, 0), 0));

  /**
   * Counts a player in, starting a new session if the proxy was empty.
   *
   * <p>Each call must be matched by one {@link #leave()}, once the player is gone.
   *
   * @return the ID of the session the player joined
   */
  UUID join() {
    return state.updateAndGet(State::joined).id();
  }

  /** Counts a player out: the session ends with its last player. */
  void leave() {
    state.updateAndGet(State::left);
  }

  /**
   * The current session, or the last one while no player is connected.
   *
   * @param id the session ID
   * @param players how many players are connected
   */
  private record State(UUID id, int players) {

    /** One more player: the first one to join an empty proxy starts a new session. */
    State joined() {
      return players == 0 ? new State(UUID.randomUUID(), 1) : new State(id, players + 1);
    }

    /** One player less. */
    State left() {
      return players == 0 ? this : new State(id, players - 1);
    }
  }
}
