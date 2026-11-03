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
package dev.warp.api.lifecycle;

/**
 * The phases of a drain, in the order the proxy goes through them. A drain never goes back, and
 * ends with the proxy shutting down.
 */
public enum DrainPhase {

  /** Not draining: the proxy serves players. */
  NONE,

  /**
   * Phase 0: the readiness probe and the HAProxy agent check report the drain, and the proxy waits
   * for load balancers to stop sending players here ({@code drain.propagation-delay}).
   */
  ANNOUNCING,

  /**
   * Phase 1: the players connected when it started are dealt with one at a time, at the configured
   * rate: transferred to {@code drain.transfer-to}, kept, or kicked.
   */
  TRANSFERRING,

  /**
   * Phase 2: waiting for the players kept to leave, up to {@code drain.timeout}. Players arriving
   * on a server meanwhile (back from a match) are dealt with as in phase 1.
   */
  WAITING,

  /**
   * Phase 3: every player left or the time-out expired. The proxy disconnects whoever is left,
   * disables its plugins and exits.
   */
  STOPPING
}
