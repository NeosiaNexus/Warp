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
package dev.warp.api.network;

/** Which peer a connection of the proxy leads to. */
public enum ConnectionSide {

  /**
   * A player's client. Inbound packets are serverbound (sent by the client), outbound packets are
   * clientbound.
   */
  CLIENT,

  /**
   * A backend server, which the proxy connected to for a player. Inbound packets are clientbound
   * (sent by the server), outbound packets are serverbound.
   */
  BACKEND
}
