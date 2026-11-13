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
import dev.warp.api.fleet.FleetPlayer;
import dev.warp.api.player.Player;
import dev.warp.protocol.ProtocolVersion;
import dev.warp.proxy.connection.Platforms;
import dev.warp.proxy.i18n.Message;

import java.util.Optional;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.kyori.adventure.text.Component;

/**
 * {@code /find <player>}: where a player plays, its latency and game version, for staff granted
 * {@value #PERMISSION}. Its address is shown only to sources also granted {@value
 * #ADDRESS_PERMISSION}: it is personal data, which BungeeCord's {@code /ip} shows to any holder of
 * a single permission.
 */
final class FindCommand {

  /** The permission the command needs. */
  static final String PERMISSION = "warp.command.find";

  /** The permission that shows the player's address too. */
  static final String ADDRESS_PERMISSION = "warp.command.find.address";

  private FindCommand() {}

  /**
   * Builds {@code /find}.
   *
   * @param proxy the proxy
   * @return the command
   */
  static LiteralArgumentBuilder<CommandSource> build(Warp proxy) {
    return LiteralArgumentBuilder.<CommandSource>literal("find")
        .requires(source -> source.hasPermission(PERMISSION))
        .then(
            RequiredArgumentBuilder.<CommandSource, String>argument(
                    "player", StringArgumentType.string())
                .suggests(
                    (context, builder) -> {
                      CommandSupport.suggestPlayers(builder, proxy.players());
                      return builder.buildFuture();
                    })
                .executes(
                    context -> {
                      CommandSource source = context.getSource();
                      String name = StringArgumentType.getString(context, "player");
                      Optional<Player> player = proxy.player(name);
                      if (player.isEmpty()) {
                        Optional<FleetPlayer> remote = proxy.fleet().player(name);
                        if (remote.isPresent()) {
                          source.sendMessage(describeRemote(remote.get()));
                          return Command.SINGLE_SUCCESS;
                        }
                        source.sendMessage(
                            Message.UNKNOWN_PLAYER.with(Message.text("player", name)));
                        return 0;
                      }
                      source.sendMessage(
                          describe(player.get(), source.hasPermission(ADDRESS_PERMISSION)));
                      return Command.SINGLE_SUCCESS;
                    }));
  }

  /**
   * The answer to {@code /find} for a player on another proxy of the fleet: where it is, nothing
   * personal, which only its own proxy knows.
   *
   * @param player the player
   * @return the message
   */
  static Component describeRemote(FleetPlayer player) {
    String server = player.server();
    if (server == null) {
      return Message.FIND_REMOTE_CONNECTING.with(
          Message.text("player", player.name()), Message.text("node", player.proxyId()));
    }
    return Message.FIND_REMOTE.with(
        Message.text("player", player.name()),
        Message.text("node", player.proxyId()),
        Message.text("server", server));
  }

  /**
   * The answer to {@code /find}.
   *
   * @param player the player
   * @param address whether to show its address
   * @return the message
   */
  static Component describe(Player player, boolean address) {
    ProtocolVersion version = ProtocolVersion.byProtocolId(player.protocolVersion());
    Component where =
        address
            ? Message.FIND_ADDRESS.with(
                Message.text("address", player.remoteAddress().getAddress().getHostAddress()))
            : Component.empty();
    String versionName =
        (version != null ? version.name() : "protocol " + player.protocolVersion())
            + Platforms.describe(player.platform());
    return player
        .currentServer()
        .map(
            connection ->
                Message.FIND_ON.with(
                    Message.text("player", player.username()),
                    Message.component(
                        "server", GlistCommand.serverLink(connection.server().name())),
                    Message.number("ping", player.ping()),
                    Message.text("version", versionName),
                    Message.component("address", where)))
        .orElseGet(
            () ->
                Message.FIND_CONNECTING.with(
                    Message.text("player", player.username()),
                    Message.text("version", versionName),
                    Message.component("address", where)));
  }
}
