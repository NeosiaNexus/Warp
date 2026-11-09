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
package dev.warp.proxy.metrics;

import java.util.Locale;

/** The {@code outcome} label of {@code warp_server_connects_total}: one value per attempt. */
public enum ServerConnectOutcome {
  /** The backend accepted the player. */
  SUCCESS,
  /** The TCP connection was refused, or nothing listens there. */
  REFUSED,
  /** The connection or the backend login took too long. */
  TIMEOUT,
  /** The backend closed the connection before the login finished. */
  RESET,
  /** The backend spoke a protocol the proxy did not expect, or a forwarding handshake failed. */
  PROTOCOL,
  /** The backend disconnected the player during its login with a reason. */
  KICKED,
  /** The player left, or a plugin denied the switch. */
  CANCELLED,
  /** Anything else. */
  OTHER;

  private final String label = name().toLowerCase(Locale.ROOT);

  /**
   * Returns the label value.
   *
   * @return the value
   */
  public String label() {
    return label;
  }
}
