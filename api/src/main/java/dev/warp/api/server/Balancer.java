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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Picks the server of a {@link ServerGroup} a player goes to.
 *
 * <p>The proxy calls a balancer with the servers of the group it can send the player to right now:
 * registered, not the server the player is leaving, not one that failed for this player already,
 * and not one whose circuit breaker is open (its last ping or last connection attempt failed and
 * its cool-down has not passed). The balancer only ranks them. It is never called with an empty
 * list, and it may keep state between calls (a round-robin cursor), since the proxy creates one
 * balancer per group.
 *
 * <p>Called on a player's event loop: a balancer must not block or take locks shared with slow
 * work, and must be safe to call from several threads at once.
 *
 * <p>Plugins register their own with {@link ServerGroupRegistry#register}. The built-in strategies
 * ({@code round-robin}, {@code least-connections}, {@code lowest-latency}, {@code random}, {@code
 * weighted}, {@code fill}) come from {@link ServerGroupRegistry#balancer(String)}.
 */
@FunctionalInterface
public interface Balancer {

  /**
   * Picks a server among the candidates.
   *
   * @param candidates the servers the player may go to, in the order of the group, at least one
   * @return the server to connect the player to, one of the candidates, or empty to let the proxy
   *     move on to the next fallback as if the group had no server available
   */
  Optional<RegisteredServer> choose(List<Candidate> candidates);

  /**
   * A server a balancer may pick, with what the proxy knows about it.
   *
   * <p>Implemented by the proxy; plugins must not implement it.
   */
  interface Candidate {

    /**
     * Returns the server.
     *
     * @return the server
     */
    RegisteredServer server();

    /**
     * Returns the server's weight in its group: a relative share, {@code 1} unless configured.
     *
     * @return the weight, at least 1
     */
    int weight();

    /**
     * Returns how many players the proxy counts on the server right now.
     *
     * @return the player count
     */
    int connections();

    /**
     * Returns the round trip of the proxy's last successful ping of the server.
     *
     * @return the round trip, or empty when the server was never pinged successfully
     */
    Optional<Duration> latency();
  }
}
