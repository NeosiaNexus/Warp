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

import dev.warp.api.event.Subscription;

import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

/**
 * Registers the proxy commands of a plugin. Each plugin has its own, from {@link
 * dev.warp.api.plugin.PluginContext#commands()}: its commands are removed when it is disabled or
 * fails.
 *
 * <p>Implemented by the proxy; plugins must not implement it.
 */
public interface CommandManager {

  /**
   * Registers a Brigadier command, with aliases that redirect to it.
   *
   * <pre>{@code
   * commands.register(
   *     LiteralArgumentBuilder.<CommandSource>literal("lobby")
   *         .requires(source -> source.hasPermission("lobby.use"))
   *         .executes(context -> { ...; return Command.SINGLE_SUCCESS; }),
   *     "hub");
   * }</pre>
   *
   * @param command the command's root literal
   * @param aliases other names it answers to
   * @return the registration; closing it removes the command and its aliases
   * @throws IllegalStateException if another plugin registered one of these names (the message
   *     names it)
   */
  Subscription register(LiteralArgumentBuilder<CommandSource> command, String... aliases);

  /**
   * Registers a simple command.
   *
   * @param name the command's name
   * @param command the command
   * @param aliases other names it answers to
   * @return the registration; closing it removes the command and its aliases
   * @throws IllegalStateException if another plugin registered one of these names
   */
  Subscription register(String name, SimpleCommand command, String... aliases);

  /**
   * Returns whether the proxy runs a command of this name.
   *
   * @param name the command's name, without {@code /}
   * @return {@code true} if a plugin or the proxy registered it
   */
  boolean hasCommand(String name);

  /**
   * Removes a command, whoever registered it: the proxy's own {@code /server}, for example, so that
   * players cannot pick their server.
   *
   * @param name the command's name
   * @return {@code true} if it existed
   */
  boolean unregister(String name);

  /**
   * Runs a command line as a source, as if typed.
   *
   * @param source who runs it
   * @param commandLine the command line, without {@code /}
   * @return completed with {@code true} if a proxy command ran it
   */
  CompletableFuture<Boolean> execute(CommandSource source, String commandLine);
}
