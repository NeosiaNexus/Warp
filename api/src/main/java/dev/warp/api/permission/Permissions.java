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
import dev.warp.api.player.Player;

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

  /**
   * Tells the proxy that a player's permissions changed: a node was granted or taken, the player
   * joined or left a group. The proxy refreshes what it built from them, its command list, so that
   * the commands it completes are those it may run now (see {@link Player#refreshCommands()}).
   *
   * <p>Call it once the provider answers with the new permissions. Safe from any thread and cheap:
   * changes in quick succession, such as each node of an edit, are merged into one refresh. A
   * player who is offline is ignored. Registering or removing a provider refreshes every player by
   * itself.
   *
   * @param player the player whose permissions changed
   */
  void changed(Player player);

  /**
   * Tells the proxy that the permissions of any online player may have changed, as when a group
   * everyone inherits was edited or the permission data was reloaded: refreshes every player as
   * {@link #changed(Player)} does.
   */
  void allChanged();
}
