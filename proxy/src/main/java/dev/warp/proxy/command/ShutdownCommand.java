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
import dev.warp.api.lifecycle.DrainPhase;
import dev.warp.api.lifecycle.DrainStatus;
import dev.warp.api.lifecycle.DrainTrigger;
import dev.warp.api.player.Player;
import dev.warp.proxy.config.DrainConfig;
import dev.warp.proxy.i18n.Message;
import dev.warp.proxy.i18n.PlayerMessages;

import java.util.List;

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
 * MiniMessage, or the message {@code warp.disconnect.restarting} without one, each player in its
 * language. A second {@code /shutdown} says where the first one stands.
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
      CommandSupport.tell(source, already(proxy.drainStatus()));
      return 0;
    }
    Component reason = typed == null ? null : reason(typed);
    List<Player> players = List.copyOf(proxy.players());
    try {
      var _ = control.shutdown(reason);
    } catch (IllegalStateException e) {
      CommandSupport.tell(
          source,
          Message.SHUTDOWN_REFUSED.with(Message.text("error", String.valueOf(e.getMessage()))));
      return 0;
    }
    logger.info(
        "Shutdown requested by {}{}",
        source instanceof Player player ? player.username() : "the console",
        reason == null
            ? ""
            : ": "
                + PlainTextComponentSerializer.plainText()
                    .serialize(PlayerMessages.english(reason)));
    Component announcement = reason != null ? reason : control.drain().message();
    for (Player player : players) {
      player.sendMessage(announcement);
    }
    CommandSupport.tell(source, started(control.drain(), players.size()));
    return Command.SINGLE_SUCCESS;
  }

  /**
   * What the players are told: "The server is shutting down: " and the reason, in MiniMessage.
   *
   * @param typed the reason as typed
   * @return the message
   */
  static Component reason(String typed) {
    return Message.SHUTDOWN_REASON.with(
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
    Component delay = CommandSupport.duration(drain.propagationDelay());
    Component fallback =
        drain.cannotTransfer() == DrainConfig.CannotTransfer.KICK
            ? Message.SHUTDOWN_FALLBACK_KICK.of()
            : Message.SHUTDOWN_FALLBACK_KEEP.of();
    DrainConfig.Target target = drain.transferTo();
    Component plan;
    if (target != null) {
      plan =
          Message.SHUTDOWN_PLAN_TRANSFER.with(
              Message.text("target", target.toString()),
              Message.component("delay", delay),
              Message.component("fallback", fallback));
    } else if (drain.cannotTransfer() == DrainConfig.CannotTransfer.KICK) {
      plan = Message.SHUTDOWN_PLAN_KICK.with(Message.component("delay", delay));
    } else {
      plan = Message.SHUTDOWN_PLAN_KEEP.of();
    }
    return Message.SHUTDOWN_STARTED.with(
        Message.count(players),
        Message.component("plan", plan),
        Message.component("timeout", CommandSupport.duration(drain.timeout())));
  }

  /**
   * Where a drain in progress stands.
   *
   * @param status the drain
   * @return the message
   */
  static Component already(DrainStatus status) {
    return Message.SHUTDOWN_ALREADY.with(
        Message.component("elapsed", CommandSupport.duration(status.elapsed())),
        Message.component("phase", describe(status.phase())),
        Message.component("trigger", describe(status.trigger())),
        Message.number("players", status.players()));
  }

  /**
   * Names what started a drain.
   *
   * @param trigger the trigger
   * @return such as {@code SIGTERM}, or "a plugin" in the reader's language
   */
  static Component describe(@Nullable DrainTrigger trigger) {
    if (trigger == null) {
      return Component.text("?");
    }
    return switch (trigger) {
      case SIGNAL -> Message.TRIGGER_SIGNAL.of();
      case HTTP -> Message.TRIGGER_HTTP.of();
      case PLUGIN -> Message.TRIGGER_PLUGIN.of();
      case COMMAND -> Message.TRIGGER_COMMAND.of();
    };
  }

  /**
   * Names where a drain stands.
   *
   * @param phase the phase
   * @return such as "transferring", in the reader's language
   */
  static Component describe(DrainPhase phase) {
    return switch (phase) {
      case NONE -> Message.PHASE_NONE.of();
      case ANNOUNCING -> Message.PHASE_ANNOUNCING.of();
      case TRANSFERRING -> Message.PHASE_TRANSFERRING.of();
      case WAITING -> Message.PHASE_WAITING.of();
      case STOPPING -> Message.PHASE_STOPPING.of();
    };
  }
}
