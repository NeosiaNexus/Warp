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
package dev.warp.api.plugin;

import java.util.Locale;
import java.util.Optional;

/**
 * Low-level access a plugin declares in {@link Plugin#capabilities()}, beyond the regular API.
 *
 * <p>A capability is a promise the proxy can check and show: an administrator sees, in the log line
 * that enables the plugin and in {@link PluginDescription#capabilities()}, which plugins reach
 * below the stable API, and the proxy refuses that access to plugins that did not declare it.
 * Velocity never shipped such a distinction (PaperMC/Velocity#327), so a plugin that injects into
 * the network pipeline by reflection looks the same there as one that only listens to events.
 */
public enum Capability {

  /**
   * Adds Netty handlers to player and backend connections, through {@link PluginContext#network()}.
   * The plugin then sees the proxy's Netty, and must not ship its own.
   */
  NETWORK_PIPELINE;

  /**
   * Returns the identifier of this capability in the plugin descriptor.
   *
   * @return the identifier, such as {@code network-pipeline}
   */
  public String id() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /**
   * Returns the capability with an identifier.
   *
   * @param id the identifier, as returned by {@link #id()}
   * @return the capability, or empty if this version of the proxy does not know it
   */
  public static Optional<Capability> byId(String id) {
    for (Capability capability : values()) {
      if (capability.id().equals(id)) {
        return Optional.of(capability);
      }
    }
    return Optional.empty();
  }
}
