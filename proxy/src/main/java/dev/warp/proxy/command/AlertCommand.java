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
import dev.warp.api.player.Player;
import dev.warp.proxy.i18n.Message;
import dev.warp.proxy.i18n.PlayerMessages;
import dev.warp.proxy.logging.Plural;

import java.util.List;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /alert <message>}: tells every player of every server, for staff granted {@value
 * #PERMISSION}. The message is MiniMessage, after an {@code [Alert]} prefix; the log keeps who sent
 * what.
 */
final class AlertCommand {

  private static final Logger logger = LoggerFactory.getLogger(AlertCommand.class);

  /** The permission the command needs. */
  static final String PERMISSION = "warp.command.alert";

  private AlertCommand() {}

  /**
   * Builds {@code /alert}.
   *
   * @param proxy the proxy
   * @return the command
   */
  static LiteralArgumentBuilder<CommandSource> build(Warp proxy) {
    return LiteralArgumentBuilder.<CommandSource>literal("alert")
        .requires(source -> source.hasPermission(PERMISSION))
        .then(
            RequiredArgumentBuilder.<CommandSource, String>argument(
                    "message", StringArgumentType.greedyString())
                .executes(
                    context -> {
                      CommandSource source = context.getSource();
                      Component alert = alert(StringArgumentType.getString(context, "message"));
                      List<Player> players = List.copyOf(proxy.players());
                      for (Player player : players) {
                        player.sendMessage(alert);
                      }
                      logger.info(
                          "Alert from {} to {} "
                              + Plural.of(players.size(), "player", "players")
                              + ": {}",
                          source instanceof Player player ? player.username() : "the console",
                          players.size(),
                          PlainTextComponentSerializer.plainText()
                              .serialize(PlayerMessages.english(alert)));
                      source.sendMessage(Message.ALERT_SENT.with(Message.count(players.size())));
                      return Command.SINGLE_SUCCESS;
                    }));
  }

  /**
   * The alert players see.
   *
   * @param typed the message as typed, in MiniMessage
   * @return the alert
   */
  static Component alert(String typed) {
    return Message.ALERT.with(
        Message.component("message", MiniMessage.miniMessage().deserialize(typed)));
  }
}
