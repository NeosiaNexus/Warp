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
package dev.warp.proxy.permission;

import dev.warp.api.event.Subscription;
import dev.warp.api.permission.PermissionProvider;
import dev.warp.api.permission.Permissions;

import java.util.function.Consumer;

/** One plugin's permission providers: registered under its name, removed with it. */
public final class PluginPermissions implements Permissions {

  private final PermissionRegistry registry;
  private final String owner;
  private final Consumer<Subscription> tracker;

  /**
   * Creates a plugin's registrar.
   *
   * @param registry the proxy's providers
   * @param owner the plugin's identifier
   * @param tracker receives every registration, to remove it when the plugin goes away
   */
  public PluginPermissions(
      PermissionRegistry registry, String owner, Consumer<Subscription> tracker) {
    this.registry = registry;
    this.owner = owner;
    this.tracker = tracker;
  }

  @Override
  public Subscription register(int priority, PermissionProvider provider) {
    Subscription subscription = registry.register(owner, priority, provider);
    tracker.accept(subscription);
    return subscription;
  }
}
