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

import dev.warp.api.event.ResultedEvent;

/**
 * Fired for every command a player types, proxy command or not, before anything runs it. Deny it to
 * block a command on every server.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface CommandExecuteEvent extends ResultedEvent<CommandExecuteEvent.Result> {

  /**
   * Returns who typed the command.
   *
   * @return the source
   */
  CommandSource source();

  /**
   * Returns the command line, without {@code /}.
   *
   * @return the command line
   */
  String commandLine();

  /** What happens to the command. The default is {@link Allowed}. */
  sealed interface Result {}

  /** Run it: the proxy runs its own commands, and sends the others to the player's server. */
  record Allowed() implements Result {}

  /**
   * Block it. Signed chat stays consistent: the server is told the command's acknowledgements as if
   * it ran.
   */
  record Denied() implements Result {}
}
