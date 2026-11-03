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
package dev.warp.api.permission;

import dev.warp.api.event.Subscription;

/**
 * Registers a plugin's permission providers. Each plugin has its own, from {@link
 * dev.warp.api.plugin.PluginContext#permissions()}: its providers are removed when it is disabled
 * or fails.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface Permissions {

  /**
   * Registers a provider. Providers are asked from the highest priority down; the first answer
   * other than {@link Tristate#UNDEFINED} wins. Equal priorities are asked in registration order.
   *
   * @param priority the provider's priority
   * @param provider the provider
   * @return the registration; closing it removes the provider
   */
  Subscription register(int priority, PermissionProvider provider);
}
