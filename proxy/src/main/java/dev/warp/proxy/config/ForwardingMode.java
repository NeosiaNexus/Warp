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
package dev.warp.proxy.config;

/**
 * Player info forwarding mode for backend connections.
 *
 * <p>Determines how the proxy communicates the player's real identity (IP, UUID, skin) to the
 * backend server, which runs in offline mode.
 */
public enum ForwardingMode {

  /** No forwarding. The backend sees the proxy's IP and generates an offline-mode UUID. */
  NONE,

  /**
   * Velocity modern forwarding via the {@code velocity:player_info} login plugin channel. The
   * payload is HMAC-SHA256 signed with a shared secret.
   */
  VELOCITY
}
