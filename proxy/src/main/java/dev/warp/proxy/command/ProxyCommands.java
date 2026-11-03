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

import dev.warp.api.Warp;
import dev.warp.api.command.CommandSource;
import dev.warp.api.permission.Tristate;
import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerRegistry;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.i18n.Message;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jspecify.annotations.Nullable;

/**
 * The commands the proxy itself offers, each behind a permission of {@code warp.command.<name>}:
 *
 * <table>
 *   <caption>Commands and permissions</caption>
 *   <tr><th>Command</th><th>Permission</th><th>Default</th></tr>
 *   <tr><td>{@code /server [server]}</td><td>{@value #SERVER_PERMISSION}</td>
 *       <td>everyone, unless a permission provider denies it</td></tr>
 *   <tr><td>{@code /warp}, {@code /warp plugins}</td><td>{@value WarpCommand#PERMISSION}</td>
 *       <td>console only</td></tr>
 *   <tr><td>{@code /warp antibot ...}</td><td>{@value AntiBotCommand#PERMISSION}</td>
 *       <td>console only</td></tr>
 *   <tr><td>{@code /glist [server|all] [page]}</td><td>{@value GlistCommand#PERMISSION}</td>
 *       <td>console only</td></tr>
 *   <tr><td>{@code /send <target> <server>}</td><td>{@value SendCommand#PERMISSION}</td>
 *       <td>console only</td></tr>
 *   <tr><td>{@code /find <player>}</td><td>{@value FindCommand#PERMISSION} (and {@value
 *       FindCommand#ADDRESS_PERMISSION} for the address)</td><td>console only</td></tr>
 *   <tr><td>{@code /alert <message>}</td><td>{@value AlertCommand#PERMISSION}</td>
 *       <td>console only</td></tr>
 *   <tr><td>{@code /shutdown [reason]}</td><td>{@value ShutdownCommand#PERMISSION}</td>
 *       <td>console only</td></tr>
 * </table>
 *
 * <p>"Console only" means granted to the console, which has every permission, and to the players a
 * permission provider grants it. A player without a command's permission does not see it, and what
 * it types goes to its backend, so that a backend command of the same name still works for it. A
 * plugin replaces a command with {@link dev.warp.api.command.CommandManager#unregister(String)}
 * followed by its own registration.
 */
public final class ProxyCommands {

  /**
   * The permission of {@code /server}: granted unless a permission provider answers {@code false}.
   */
  static final String SERVER_PERMISSION = "warp.command.server";

  private ProxyCommands() {}

  /**
   * Registers {@code /server}, which needs nothing but the servers.
   *
   * @param commands the proxy's commands
   * @param servers the backend servers
   */
  public static void register(WarpCommandManager commands, ServerRegistry servers) {
    var _ = commands.register(WarpCommandManager.PROXY, server(servers));
  }

  /**
   * Registers the administration commands: {@code /warp}, {@code /glist}, {@code /send}, {@code
   * /find}, {@code /alert} and {@code /shutdown}.
   *
   * @param commands the proxy's commands
   * @param proxy the proxy
   * @param control what the proxy's API does not tell
   * @param antiBot the anti-bot, for {@code /warp antibot}, or {@code null} without one
   */
  public static void registerAdmin(
      WarpCommandManager commands,
      Warp proxy,
      ProxyControl control,
      @Nullable AntiBotService antiBot) {
    String owner = WarpCommandManager.PROXY;
    var _ =
        commands.register(
            owner,
            WarpCommand.build(
                proxy,
                control,
                antiBot == null ? null : AntiBotCommand.node(antiBot, proxy::player)));
    var _ = commands.register(owner, GlistCommand.build(proxy));
    var _ = commands.register(owner, SendCommand.build(proxy));
    var _ = commands.register(owner, FindCommand.build(proxy));
    var _ = commands.register(owner, AlertCommand.build(proxy));
    var _ = commands.register(owner, ShutdownCommand.build(proxy, control));
  }

  /**
   * {@code /server}: lists the servers, or moves the player to one. The console gets the list, and
   * is pointed to {@code /send} to move someone.
   */
  static LiteralArgumentBuilder<CommandSource> server(ServerRegistry servers) {
    return LiteralArgumentBuilder.<CommandSource>literal("server")
        .requires(source -> source.permissionValue(SERVER_PERMISSION) != Tristate.FALSE)
        .executes(
            context -> {
              CommandSource source = context.getSource();
              String current =
                  source instanceof Player player
                      ? player.currentServer().map(c -> c.server().name()).orElse(null)
                      : null;
              List<String> names =
                  servers.servers().stream()
                      .map(RegisteredServer::name)
                      .sorted(Comparator.naturalOrder())
                      .toList();
              source.sendMessage(serverList(names, current));
              return Command.SINGLE_SUCCESS;
            })
        .then(
            CommandSupport.serverArgument("server", servers)
                .executes(
                    context -> {
                      String name = StringArgumentType.getString(context, "server");
                      Optional<RegisteredServer> target = servers.server(name);
                      if (target.isEmpty()) {
                        context.getSource().sendMessage(Message.UNKNOWN_SERVER.of(name));
                        return 0;
                      }
                      if (!(context.getSource() instanceof Player player)) {
                        context.getSource().sendMessage(Message.SERVER_NOT_A_PLAYER.of());
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
