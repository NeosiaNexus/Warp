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

import dev.warp.api.player.Player;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Fired when the proxy sends a player its command list (the backend's commands merged with the
 * proxy's), to hide commands from it. A hidden command is not suggested; whether it still runs is
 * up to whoever owns it.
 *
 * <p>Not fired for a command list the proxy cannot read (a modded server's own argument types): the
 * player then gets the server's list unchanged.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface PlayerAvailableCommandsEvent {

  /**
   * Returns the player.
   *
   * @return the player
   */
  Player player();

  /**
   * Returns the names of the commands the player will see.
   *
   * @return the names, unmodifiable
   */
  Set<String> commands();

  /**
   * Hides commands from the player.
   *
   * @param hide tests a command's name
   */
  void removeIf(Predicate<String> hide);
}
