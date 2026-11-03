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
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.player.Player;
import dev.warp.proxy.config.DrainConfig;

import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /shutdown [reason]}: stops the proxy through its drain, for staff granted {@value
 * #PERMISSION}, and the console.
 *
 * <p>Nobody is cut off without a word: every player is told at once, the reason in chat, while the
 * drain gives load balancers {@code drain.propagation-delay} to notice; then it transfers the
 * players to {@code drain.transfer-to}, or kicks or keeps them as {@code drain.cannot-transfer}
 * says, and the proxy stops once it is empty or {@code drain.timeout} expired. The players kicked,
 * the logins refused meanwhile and those still connected at the end see the reason, written in
 * MiniMessage, or {@code drain.message} without one. A second {@code /shutdown} says where the
 * first one stands.
 */
final class ShutdownCommand {

  private static final Logger logger = LoggerFactory.getLogger(ShutdownCommand.class);

  /** The permission the command needs. */
  static final String PERMISSION = "warp.command.shutdown";

  private ShutdownCommand() {}

  /**
   * Builds {@code /shutdown}.
   *
   * @param proxy the proxy
   * @param control starts the drain
   * @return the command
   */
  static LiteralArgumentBuilder<CommandSource> build(Warp proxy, ProxyControl control) {
    return LiteralArgumentBuilder.<CommandSource>literal("shutdown")
        .requires(source -> source.hasPermission(PERMISSION))
        .executes(context -> shutdown(proxy, control, context.getSource(), null))
        .then(
            RequiredArgumentBuilder.<CommandSource, String>argument(
                    "reason", StringArgumentType.greedyString())
                .executes(
                    context ->
                        shutdown(
                            proxy,
                            control,
                            context.getSource(),
                            StringArgumentType.getString(context, "reason"))));
  }

  private static int shutdown(
      Warp proxy, ProxyControl control, CommandSource source, @Nullable String typed) {
    if (proxy.isDraining()) {
      source.sendMessage(already(proxy.drainStatus()));
      return 0;
    }
    Component reason = typed == null ? null : reason(typed);
    List<Player> players = List.copyOf(proxy.players());
    try {
      var _ = control.shutdown(reason);
    } catch (IllegalStateException e) {
      source.sendMessage(
          Message.SHUTDOWN_REFUSED.render(Message.text("error", String.valueOf(e.getMessage()))));
      return 0;
    }
    logger.info(
        "Shutdown requested by {}{}",
        source instanceof Player player ? player.username() : "the console",
        reason == null ? "" : ": " + PlainTextComponentSerializer.plainText().serialize(reason));
    Component announcement = reason != null ? reason : control.drain().message();
    for (Player player : players) {
      player.sendMessage(announcement);
    }
    source.sendMessage(started(control.drain(), players.size()));
    return Command.SINGLE_SUCCESS;
  }

  /**
   * What the players are told: "The server is shutting down: " and the reason, in MiniMessage.
   *
   * @param typed the reason as typed
   * @return the message
   */
  static Component reason(String typed) {
    return Message.SHUTDOWN_REASON.render(
        Message.component("reason", MiniMessage.miniMessage().deserialize(typed)));
  }

  /**
   * What the sender is told: how many players, what happens to them, when the proxy stops at the
   * latest.
   *
   * @param drain the drain's configuration
   * @param players the players connected
   * @return the message
   */
  static Component started(DrainConfig drain, int players) {
    String delay = DrainConfig.format(drain.propagationDelay());
    Component fallback =
        drain.cannotTransfer() == DrainConfig.CannotTransfer.KICK
            ? Message.SHUTDOWN_FALLBACK_KICK.render()
            : Message.SHUTDOWN_FALLBACK_KEEP.render();
    DrainConfig.Target target = drain.transferTo();
    Component plan;
    if (target != null) {
      plan =
          Message.SHUTDOWN_PLAN_TRANSFER.render(
              Message.text("target", target.toString()),
              Message.text("delay", delay),
              Message.component("fallback", fallback));
    } else if (drain.cannotTransfer() == DrainConfig.CannotTransfer.KICK) {
      plan = Message.SHUTDOWN_PLAN_KICK.render(Message.text("delay", delay));
    } else {
      plan = Message.SHUTDOWN_PLAN_KEEP.render();
    }
    return Message.SHUTDOWN_STARTED.render(
        Message.count(players),
        Message.component("plan", plan),
        Message.text("timeout", DrainConfig.format(drain.timeout())));
  }

  /**
   * Where a drain in progress stands.
   *
   * @param status the drain
   * @return the message
   */
  static Component already(DrainStatus status) {
    return Message.SHUTDOWN_ALREADY.render(
        Message.text("elapsed", CommandSupport.duration(status.elapsed())),
        Message.text("phase", status.phase().name().toLowerCase(Locale.ROOT)),
        Message.text("trigger", describe(status.trigger())),
        Message.text("players", Message.number(status.players())));
  }

  /**
   * Names what started a drain.
   *
   * @param trigger the trigger
   * @return such as {@code SIGTERM}
   */
  static String describe(@Nullable DrainTrigger trigger) {
    if (trigger == null) {
      return "?";
    }
    return switch (trigger) {
      case SIGNAL -> "SIGTERM";
      case HTTP -> "POST /drain";
      case PLUGIN -> "a plugin";
      case COMMAND -> "/shutdown";
    };
  }
}
