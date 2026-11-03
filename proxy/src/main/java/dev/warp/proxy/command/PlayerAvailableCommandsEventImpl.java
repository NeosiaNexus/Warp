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
package dev.warp.proxy.command;

import dev.warp.api.command.PlayerAvailableCommandsEvent;
import dev.warp.api.player.Player;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** {@link PlayerAvailableCommandsEvent} as the proxy fires it. */
public final class PlayerAvailableCommandsEventImpl implements PlayerAvailableCommandsEvent {

  private final Player player;
  private final Set<String> commands;

  /**
   * Creates the event.
   *
   * @param player the player
   * @param commands the names of the commands it will see
   */
  public PlayerAvailableCommandsEventImpl(Player player, List<String> commands) {
    this.player = player;
    this.commands = new LinkedHashSet<>(commands);
  }

  @Override
  public Player player() {
    return player;
  }

  @Override
  public synchronized Set<String> commands() {
    return Set.copyOf(commands);
  }

  @Override
  public synchronized void removeIf(Predicate<String> hide) {
    commands.removeIf(hide);
  }

  /**
   * Returns whether a command is still shown.
   *
   * @param name the command's name
   * @return {@code true} if no handler hid it
   */
  public synchronized boolean keeps(String name) {
    return commands.contains(name);
  }
}
