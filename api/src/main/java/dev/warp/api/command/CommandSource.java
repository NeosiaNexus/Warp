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

import net.kyori.adventure.text.Component;

/**
 * Who runs a command: a player, or the console.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface CommandSource {

  /**
   * Sends a message to the source: the player's chat, or the console's log.
   *
   * @param message the message
   */
  void sendMessage(Component message);

  /**
   * Returns whether the source has a permission. The console has every permission; a player has
   * those the permission provider grants (none without one).
   *
   * @param permission the permission node, such as {@code myplugin.command.lobby}
   * @return {@code true} if granted
   */
  boolean hasPermission(String permission);
}
