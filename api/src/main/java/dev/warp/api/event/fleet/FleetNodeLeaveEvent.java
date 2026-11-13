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
package dev.warp.api.event.fleet;

import dev.warp.api.fleet.ProxyNode;

/**
 * Fired when another proxy leaves the fleet: it stopped, or its heartbeat expired. Its players left
 * with it: a {@link FleetPlayerLeaveEvent} per player precedes this event.
 *
 * @param node the node as last seen, its state {@link dev.warp.api.fleet.NodeState#DOWN}
 * @param reason why it left
 */
public record FleetNodeLeaveEvent(ProxyNode node, Reason reason) {

  /** Why a node left. */
  public enum Reason {
    /** It said goodbye: a clean stop, or the end of a drain. */
    SHUTDOWN,
    /** Its heartbeat expired without a goodbye: a crash, a partition, a frozen process. */
    EXPIRED
  }
}
