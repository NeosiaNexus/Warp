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

import java.util.List;

/**
 * A command that takes a list of words, for commands that do not need Brigadier's typed arguments.
 * The client shows it with a single free-text argument and asks the proxy for suggestions.
 */
public interface SimpleCommand {

  /**
   * Runs the command.
   *
   * @param invocation who ran it, and with what
   * @throws Exception reported to the source as a failure, and logged
   */
  void execute(Invocation invocation) throws Exception;

  /**
   * Suggests completions for the word being typed (the last of the arguments, possibly empty).
   *
   * @param invocation who is typing, and what
   * @return the suggestions, empty by default
   */
  default List<String> suggest(Invocation invocation) {
    return List.of();
  }

  /**
   * Returns whether the source may run and see this command. Hidden from the command list of a
   * player who may not.
   *
   * @param source the source
   * @return {@code true} by default
   */
  default boolean hasPermission(CommandSource source) {
    return true;
  }

  /**
   * A run of a command.
   *
   * @param source who ran it
   * @param alias the name it was run under (the command's name or one of its aliases)
   * @param arguments the words after the name, possibly empty; the last may be empty while typing
   */
  record Invocation(CommandSource source, String alias, List<String> arguments) {

    /**
     * Copies the arguments.
     *
     * @param source who ran it
     * @param alias the name it was run under
     * @param arguments the words after the name
     */
    public Invocation {
      arguments = List.copyOf(arguments);
    }
  }
}
