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

import dev.warp.api.command.CommandSource;
import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerRegistry;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.kyori.adventure.text.Component;

/**
 * The commands the proxy itself offers: {@code /server}. A plugin removes them with {@link
 * dev.warp.api.command.CommandManager#unregister(String)} when players must not pick their server.
 */
public final class ProxyCommands {

  private ProxyCommands() {}

  /**
   * Registers the proxy's commands.
   *
   * @param commands the proxy's commands
   * @param servers the backend servers
   */
  public static void register(WarpCommandManager commands, ServerRegistry servers) {
    var _ = commands.register(WarpCommandManager.PROXY, server(servers));
  }

  /** {@code /server}: lists the servers, or moves the player to one. */
  static LiteralArgumentBuilder<CommandSource> server(ServerRegistry servers) {
    return LiteralArgumentBuilder.<CommandSource>literal("server")
        .requires(source -> source instanceof Player)
        .executes(
            context -> {
              Player player = (Player) context.getSource();
              String current = player.currentServer().map(c -> c.server().name()).orElse(null);
              String list =
                  servers.servers().stream()
                      .map(RegisteredServer::name)
                      .sorted(Comparator.naturalOrder())
                      .map(name -> name.equals(current) ? "[" + name + "]" : name)
                      .collect(Collectors.joining(", "));
              player.sendMessage(Component.text("Servers: " + list));
              return Command.SINGLE_SUCCESS;
            })
        .then(
            RequiredArgumentBuilder.<CommandSource, String>argument(
                    "server", StringArgumentType.word())
                .suggests(
                    (context, builder) -> {
                      String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                      for (RegisteredServer server : servers.servers()) {
                        if (server.name().startsWith(typed)) {
                          builder.suggest(server.name());
                        }
                      }
                      return builder.buildFuture();
                    })
                .executes(
                    context -> {
                      Player player = (Player) context.getSource();
                      String name = StringArgumentType.getString(context, "server");
                      Optional<RegisteredServer> target = servers.server(name);
                      if (target.isEmpty()) {
                        player.sendMessage(Component.text("Unknown server: " + name));
                        return 0;
                      }
                      var _ =
                          player
                              .connect(target.get())
                              .thenAccept(
                                  result -> {
                                    switch (result) {
                                      case ConnectionResult.AlreadyConnected(var server) ->
                                          player.sendMessage(
                                              Component.text(
                                                  "Already connected to " + server.name() + "."));
                                      case ConnectionResult.InProgress _ ->
                                          player.sendMessage(
                                              Component.text("Already switching servers."));
                                      case ConnectionResult.Denied(var server) ->
                                          player.sendMessage(
                                              Component.text(
                                                  "You cannot join "
                                                      + server.name()
                                                      + " right now."));
                                      default -> {
                                        // Connected, or failed: the player was told on the way.
                                      }
                                    }
                                  });
                      return Command.SINGLE_SUCCESS;
                    }));
  }
}
