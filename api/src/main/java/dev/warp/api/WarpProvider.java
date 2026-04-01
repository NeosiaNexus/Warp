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
package dev.warp.api;

import org.jspecify.annotations.Nullable;

/**
 * Static service locator for the {@link Warp} API singleton.
 *
 * <p>The proxy implementation calls {@link #set(Warp)} exactly once during startup. Plugin code
 * retrieves the instance via {@link #get()}.
 *
 * <pre>{@code
 * Warp warp = WarpProvider.get();
 * String version = warp.version();
 * }</pre>
 */
public final class WarpProvider {

  private static @Nullable Warp instance;

  private WarpProvider() {}

  /**
   * Returns the global {@link Warp} instance.
   *
   * @return the singleton Warp API instance
   * @throws IllegalStateException if the proxy has not yet been initialised
   */
  public static Warp get() {
    if (instance == null) {
      throw new IllegalStateException(
          "Warp API is not available yet — the proxy has not finished initialising.");
    }
    return instance;
  }

  /**
   * Sets the global {@link Warp} instance. Must be called exactly once by the proxy implementation
   * during startup.
   *
   * <p><b>Internal use only.</b> Plugin code must never call this method.
   *
   * @param warp the Warp API implementation to register
   * @throws IllegalStateException if an instance has already been set
   */
  public static void set(Warp warp) {
    if (instance != null) {
      throw new IllegalStateException("Warp API instance has already been set.");
    }
    instance = warp;
  }
}
