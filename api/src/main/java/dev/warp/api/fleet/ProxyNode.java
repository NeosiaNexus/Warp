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
package dev.warp.api.fleet;

import java.time.Instant;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * A proxy of the fleet, as its last heartbeat described it.
 *
 * @param id the node id, unique in the network: {@code fleet.node-id}, the host name by default
 * @param incarnation increases each time a proxy starts under this id; a presence record carries
 *     the incarnation that wrote it, so a restarted proxy never mistakes a predecessor's players
 *     for its own
 * @param publicAddress the {@code host:port} players are transferred to when a draining proxy picks
 *     this one, or {@code null} when the node did not configure {@code fleet.public-address}
 * @param players how many players it carried at its last heartbeat
 * @param state where it stands
 * @param lastSeen when this proxy last observed a heartbeat of it, on this proxy's clock: for
 *     display only, liveness is decided by the store's time-to-live, never by comparing clocks
 * @param version the Warp version it runs
 */
public record ProxyNode(
    String id,
    long incarnation,
    @Nullable String publicAddress,
    int players,
    NodeState state,
    Instant lastSeen,
    String version) {

  /**
   * Returns the address players are transferred to.
   *
   * @return the {@code host:port}, or empty when the node has none configured
   */
  public Optional<String> publicAddressIfAny() {
    return Optional.ofNullable(publicAddress);
  }

  /**
   * Returns whether a draining proxy may send players here: ready, with a public address.
   *
   * @return {@code true} when players can be transferred to this node
   */
  public boolean acceptsTransfers() {
    return state == NodeState.READY && publicAddress != null;
  }
}
