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
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.i18n.Message;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jspecify.annotations.Nullable;

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

  /**
   * Registers {@code /warp antibot}, for staff with {@code warp.command.antibot}.
   *
   * @param commands the proxy's commands
   * @param antiBot the anti-bot
   * @param players finds a connected player by name
   */
  public static void registerAntiBot(
      WarpCommandManager commands,
      AntiBotService antiBot,
      Function<String, Optional<Player>> players) {
    var _ = commands.register(WarpCommandManager.PROXY, AntiBotCommand.warp(antiBot, players));
  }

  /** {@code /server}: lists the servers, or moves the player to one. */
  static LiteralArgumentBuilder<CommandSource> server(ServerRegistry servers) {
    return LiteralArgumentBuilder.<CommandSource>literal("server")
        .requires(source -> source instanceof Player)
        .executes(
            context -> {
              Player player = (Player) context.getSource();
              String current = player.currentServer().map(c -> c.server().name()).orElse(null);
              List<String> names =
                  servers.servers().stream()
                      .map(RegisteredServer::name)
                      .sorted(Comparator.naturalOrder())
                      .toList();
              player.sendMessage(serverList(names, current));
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
                        player.sendMessage(Message.UNKNOWN_SERVER.of(name));
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
                                              Message.ALREADY_ON_SERVER.of(server.name()));
                                      case ConnectionResult.InProgress _ ->
                                          player.sendMessage(Message.ALREADY_SWITCHING.of());
                                      case ConnectionResult.Denied(var server) ->
                                          player.sendMessage(
                                              Message.CONNECT_DENIED.of(server.name()));
                                      default -> {
                                        // Connected, or failed: the player was told on the way.
                                      }
                                    }
                                  });
                      return Command.SINGLE_SUCCESS;
                    }));
  }

  /**
   * The answer to {@code /server}: "Servers: [lobby], survival", the current server in brackets,
   * each other one a link that moves the player there; rendered in the player's language.
   *
   * @param names the servers' names, in order
   * @param current the server the player is on, or {@code null}
   * @return the message
   */
  public static Component serverList(List<String> names, @Nullable String current) {
    TextComponent.Builder list = Component.text().color(NamedTextColor.GRAY);
    boolean first = true;
    for (String name : names) {
      if (!first) {
        list.append(Component.text(", ", NamedTextColor.GRAY));
      }
      first = false;
      list.append(
          name.equals(current)
              ? Component.text("[" + name + "]", NamedTextColor.GREEN)
              : Component.text(name, NamedTextColor.AQUA)
                  .clickEvent(ClickEvent.runCommand("/server " + name))
                  .hoverEvent(HoverEvent.showText(Message.SERVER_LIST_HOVER.of(name))));
    }
    return Message.SERVER_LIST.of(list.build());
  }
}
