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
package dev.warp.api.fleet.spi;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A player's presence, as written to the store. Its owner is the pair (node, incarnation): a record
 * written by an earlier incarnation of the same node belongs to nobody alive.
 *
 * @param uuid the player's UUID
 * @param name the player's name
 * @param nodeId the node it is connected to
 * @param incarnation the incarnation of that node when it claimed the player
 * @param server the backend it plays on, or {@code null} between servers
 * @param since when it logged in, on its node's clock
 */
public record PlayerRecord(
    UUID uuid,
    String name,
    String nodeId,
    long incarnation,
    @Nullable String server,
    Instant since) {

  /**
   * Returns whether a node owns this record.
   *
   * @param nodeId the node
   * @param incarnation its incarnation
   * @return {@code true} when the record was written by exactly that incarnation of that node
   */
  public boolean ownedBy(String nodeId, long incarnation) {
    return this.nodeId.equals(nodeId) && this.incarnation == incarnation;
  }
}
