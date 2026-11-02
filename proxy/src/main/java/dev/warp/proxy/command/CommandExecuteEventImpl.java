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

import dev.warp.api.command.CommandExecuteEvent;
import dev.warp.api.command.CommandSource;

import java.util.Objects;

/** {@link CommandExecuteEvent} as the proxy fires it. */
public final class CommandExecuteEventImpl implements CommandExecuteEvent {

  private final CommandSource source;
  private final String commandLine;
  private volatile Result result = new Allowed();

  /**
   * Creates the event.
   *
   * @param source who typed the command
   * @param commandLine the command line, without {@code /}
   */
  public CommandExecuteEventImpl(CommandSource source, String commandLine) {
    this.source = source;
    this.commandLine = commandLine;
  }

  @Override
  public CommandSource source() {
    return source;
  }

  @Override
  public String commandLine() {
    return commandLine;
  }

  @Override
  public Result result() {
    return result;
  }

  @Override
  public void setResult(Result result) {
    this.result = Objects.requireNonNull(result, "result");
  }
}
