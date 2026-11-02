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
package dev.warp.api.event.proxy;

import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;

import java.util.Objects;

/**
 * Fired each time a drain enters a phase, from {@link DrainPhase#ANNOUNCING} to {@link
 * DrainPhase#STOPPING}. The drain does not wait for the handlers: they run while it goes on. Tell
 * other services the proxy is going away on {@link DrainPhase#ANNOUNCING}; save state on {@link
 * ProxyShutdownEvent}, which the proxy waits for.
 *
 * @param status the drain's status as it entered the phase
 */
public record ProxyDrainEvent(DrainStatus status) {

  /**
   * Checks the event describes a drain.
   *
   * @throws IllegalArgumentException if the status is {@link DrainPhase#NONE}
   */
  public ProxyDrainEvent {
    Objects.requireNonNull(status, "status");
    if (!status.isDraining()) {
      throw new IllegalArgumentException("a drain event needs a drain phase, got NONE");
    }
  }

  /**
   * Returns the phase the drain entered.
   *
   * @return the phase, never {@link DrainPhase#NONE}
   */
  public DrainPhase phase() {
    return status.phase();
  }
}
