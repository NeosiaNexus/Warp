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

/** The outcome of {@link Player#transfer(String, int)}. */
public enum TransferResult {

  /**
   * The client received the Transfer packet: it leaves the proxy and connects to the target by
   * itself. Whether the target lets it in is between them.
   */
  SENT,

  /** The client predates Minecraft 1.20.5, which has no Transfer packet: nothing was sent. */
  UNSUPPORTED,

  /**
   * The client is still logging in, before it can read a Transfer packet: nothing was sent. It can
   * be transferred once it plays on a server.
   */
  NOT_READY,

  /** The player disconnected before the packet could be sent. */
  DISCONNECTED;

  /**
   * Returns whether the client received the packet.
   *
   * @return {@code true} for {@link #SENT}
   */
  public boolean isSent() {
    return this == SENT;
  }
}
