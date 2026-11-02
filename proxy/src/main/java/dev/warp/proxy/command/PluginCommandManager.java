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

import dev.warp.api.command.CommandManager;
import dev.warp.api.command.CommandSource;
import dev.warp.api.command.SimpleCommand;
import dev.warp.api.event.Subscription;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

/** One plugin's commands: registered under its name, removed with it. */
public final class PluginCommandManager implements CommandManager {

  private final WarpCommandManager commands;
  private final String owner;
  private final Consumer<Subscription> tracker;

  /**
   * Creates a plugin's command manager.
   *
   * @param commands the proxy's commands
   * @param owner the plugin's identifier
   * @param tracker receives every registration, to remove it when the plugin goes away
   */
  public PluginCommandManager(
      WarpCommandManager commands, String owner, Consumer<Subscription> tracker) {
    this.commands = commands;
    this.owner = owner;
    this.tracker = tracker;
  }

  @Override
  public Subscription register(LiteralArgumentBuilder<CommandSource> command, String... aliases) {
    Subscription subscription = commands.register(owner, command, aliases);
    tracker.accept(subscription);
    return subscription;
  }

  @Override
  public Subscription register(String name, SimpleCommand command, String... aliases) {
    Subscription subscription = commands.register(owner, name, command, aliases);
    tracker.accept(subscription);
    return subscription;
  }

  @Override
  public boolean hasCommand(String name) {
    return commands.hasCommand(name);
  }

  @Override
  public boolean unregister(String name) {
    return commands.unregister(name);
  }

  @Override
  public CompletableFuture<Boolean> execute(CommandSource source, String commandLine) {
    return commands.execute(source, commandLine);
  }
}
