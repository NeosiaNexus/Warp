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
package dev.warp.api.command;

import dev.warp.api.permission.Tristate;

import net.kyori.adventure.audience.Audience;

/**
 * Who runs a command: a player, or the console. It is an Adventure {@link Audience}: {@code
 * sendMessage} writes to the player's chat, or the console's log.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface CommandSource extends Audience {

  /**
   * Returns whether the source has a permission. The console has every permission; a player has
   * those the registered {@link dev.warp.api.permission.PermissionProvider}s grant (none without
   * one).
   *
   * @param permission the permission node, such as {@code myplugin.command.lobby}
   * @return {@code true} if granted
   */
  boolean hasPermission(String permission);

  /**
   * Returns what the source's permission providers answer for a permission, telling a permission
   * nobody set ({@link Tristate#UNDEFINED}) from one denied ({@link Tristate#FALSE}). A command
   * open to everyone unless an administrator denies it, such as the proxy's {@code /server}, checks
   * this rather than {@link #hasPermission}. The console answers {@link Tristate#TRUE}.
   *
   * <p>The default answers {@link Tristate#TRUE} when {@link #hasPermission} grants the permission
   * and {@link Tristate#UNDEFINED} otherwise; players answer what their providers say.
   *
   * @param permission the permission node
   * @return the providers' answer
   */
  default Tristate permissionValue(String permission) {
    return hasPermission(permission) ? Tristate.TRUE : Tristate.UNDEFINED;
  }
}
