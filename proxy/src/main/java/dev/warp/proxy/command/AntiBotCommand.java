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

import dev.warp.api.antibot.AttackReport;
import dev.warp.api.antibot.Identity;
import dev.warp.api.antibot.Signal;
import dev.warp.api.antibot.Verification;
import dev.warp.api.command.CommandSource;
import dev.warp.api.player.Player;
import dev.warp.proxy.antibot.AntiBotService;
import dev.warp.proxy.antibot.AntiBotStatus;
import dev.warp.proxy.limit.Refusal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.netty.util.NetUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jspecify.annotations.Nullable;

/**
 * {@code /warp antibot}: where the anti-bot stands, lockdown, and the identities it trusts. Needs
 * {@value #PERMISSION}.
 *
 * <ul>
 *   <li>{@code /warp antibot}: level, lockdown, the signals of the last second against their
 *       thresholds, connections not logged in, the authentication line, trusted identities,
 *       refusals
 *   <li>{@code /warp antibot lockdown on|off}
 *   <li>{@code /warp antibot verify|unverify|check <name|uuid> [address]}: a UUID is an account; a
 *       name with an address is that name on that address's network; a name alone is the player of
 *       that name connected now (its account in online mode, and its name and network)
 * </ul>
 */
final class AntiBotCommand {

  /** The permission the command needs. */
  static final String PERMISSION = "warp.command.antibot";

  private AntiBotCommand() {}

  /**
   * Builds {@code /warp} with its {@code antibot} subcommand.
   *
   * @param antiBot the anti-bot
   * @param players finds a connected player by name
   * @return the command
   */
  static LiteralArgumentBuilder<CommandSource> warp(
      AntiBotService antiBot, Function<String, Optional<Player>> players) {
    return LiteralArgumentBuilder.<CommandSource>literal("warp")
        .requires(source -> source.hasPermission(PERMISSION))
        .then(
            LiteralArgumentBuilder.<CommandSource>literal("antibot")
                .executes(
                    context -> {
                      context.getSource().sendMessage(status(antiBot.status()));
                      return Command.SINGLE_SUCCESS;
                    })
                .then(
                    LiteralArgumentBuilder.<CommandSource>literal("lockdown")
                        .then(
                            RequiredArgumentBuilder.<CommandSource, String>argument(
                                    "state", StringArgumentType.word())
                                .suggests(
                                    (context, builder) ->
                                        builder.suggest("on").suggest("off").buildFuture())
                                .executes(context -> lockdown(antiBot, context))))
                .then(identityCommand("verify", antiBot, players))
                .then(identityCommand("unverify", antiBot, players))
                .then(identityCommand("check", antiBot, players)));
  }

  private static LiteralArgumentBuilder<CommandSource> identityCommand(
      String action, AntiBotService antiBot, Function<String, Optional<Player>> players) {
    return LiteralArgumentBuilder.<CommandSource>literal(action)
        .then(
            RequiredArgumentBuilder.<CommandSource, String>argument(
                    "who", StringArgumentType.word())
                .executes(context -> identities(action, antiBot, players, context, null))
                .then(
                    RequiredArgumentBuilder.<CommandSource, String>argument(
                            "address", StringArgumentType.word())
                        .executes(
                            context ->
                                identities(
                                    action,
                                    antiBot,
                                    players,
                                    context,
                                    StringArgumentType.getString(context, "address")))));
  }

  private static int lockdown(AntiBotService antiBot, CommandContext<CommandSource> context) {
    String state = StringArgumentType.getString(context, "state").toLowerCase(Locale.ROOT);
    boolean enabled;
    if (state.equals("on")) {
      enabled = true;
    } else if (state.equals("off")) {
      enabled = false;
    } else {
      context.getSource().sendMessage(Component.text("Use on or off.", NamedTextColor.RED));
      return 0;
    }
    CommandSource source = context.getSource();
    String who = source instanceof Player player ? "command by " + player.username() : "command";
    try {
      boolean changed = antiBot.lockdown(enabled, who);
      source.sendMessage(
          Component.text(
              (changed ? "Lockdown is now " : "Lockdown was already ")
                  + (enabled ? "on: only players who have played here before may log in." : "off."),
              enabled ? NamedTextColor.GOLD : NamedTextColor.GREEN));
      return Command.SINGLE_SUCCESS;
    } catch (IllegalStateException e) {
      source.sendMessage(Component.text(String.valueOf(e.getMessage()), NamedTextColor.RED));
      return 0;
    }
  }

  private static int identities(
      String action,
      AntiBotService antiBot,
      Function<String, Optional<Player>> players,
      CommandContext<CommandSource> context,
      @Nullable String address) {
    CommandSource source = context.getSource();
    String who = StringArgumentType.getString(context, "who");
    List<Identity> identities;
    try {
      identities = resolve(antiBot, players, who, address);
    } catch (IllegalArgumentException e) {
      source.sendMessage(Component.text(String.valueOf(e.getMessage()), NamedTextColor.RED));
      return 0;
    }
    for (Identity identity : identities) {
      String label = describe(antiBot, identity);
      switch (action) {
        case "verify" -> {
          CompletableFuture<Verification> stored;
          try {
            stored = antiBot.trustByCommand(identity);
          } catch (IllegalStateException e) {
            source.sendMessage(Component.text(String.valueOf(e.getMessage()), NamedTextColor.RED));
            return 0;
          }
          var _ =
              stored.whenComplete(
                  (verification, failure) ->
                      source.sendMessage(
                          failure != null
                              ? Component.text(
                                  "Could not trust " + label + ": " + failure.getMessage(),
                                  NamedTextColor.RED)
                              : Component.text("Trusted " + label + ".", NamedTextColor.GREEN)));
        }
        case "unverify" -> {
          var _ =
              antiBot
                  .verified()
                  .remove(identity)
                  .whenComplete(
                      (removed, failure) ->
                          source.sendMessage(
                              Component.text(
                                  failure != null
                                      ? "Could not forget " + label + ": " + failure.getMessage()
                                      : Boolean.TRUE.equals(removed)
                                          ? "No longer trusted: " + label + "."
                                          : label + " was not trusted.",
                                  failure != null ? NamedTextColor.RED : NamedTextColor.GREEN)));
        }
        default -> {
          Optional<Verification> found = antiBot.verified().check(identity);
          source.sendMessage(
              found
                  .map(
                      v ->
                          Component.text(
                              label
                                  + ": trusted since "
                                  + v.verifiedAt()
                                  + " ("
                                  + v.method().name().toLowerCase(Locale.ROOT)
                                  + "), last seen "
                                  + v.lastSeen(),
                              NamedTextColor.GREEN))
                  .orElseGet(
                      () -> Component.text(label + ": not trusted.", NamedTextColor.YELLOW)));
        }
      }
    }
    return Command.SINGLE_SUCCESS;
  }

  /** The identities a command names; throws with a message for the source when it names none. */
  static List<Identity> resolve(
      AntiBotService antiBot,
      Function<String, Optional<Player>> players,
      String who,
      @Nullable String address) {
    UUID account = parseUuid(who);
    if (account != null) {
      return List.of(new Identity.Account(account));
    }
    if (address != null) {
      byte[] bytes = NetUtil.createByteArrayFromIpAddressString(address);
      if (bytes == null) {
        throw new IllegalArgumentException("Not an IP address: " + address);
      }
      try {
        return List.of(new Identity.NameAtAddress(who, InetAddress.getByAddress(bytes)));
      } catch (UnknownHostException e) {
        throw new IllegalArgumentException("Not an IP address: " + address, e);
      }
    }
    Player player =
        players
            .apply(who)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        who + " is not connected: give a UUID, or the name and an address."));
    List<Identity> identities = new ArrayList<>();
    if (antiBot.onlineMode()) {
      identities.add(new Identity.Account(player.uniqueId()));
    }
    identities.add(
        new Identity.NameAtAddress(player.username(), player.remoteAddress().getAddress()));
    return identities;
  }

  private static @Nullable UUID parseUuid(String text) {
    if (text.length() != 36) {
      return null;
    }
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String describe(AntiBotService antiBot, Identity identity) {
    return switch (identity) {
      case Identity.Account(UUID uuid) -> "account " + uuid;
      case Identity.NameAtAddress(String name, InetAddress address) ->
          name + " on " + antiBot.network(address);
    };
  }

  // ---------------------------------------------------------------------------
  // Status
  // ---------------------------------------------------------------------------

  /**
   * Describes where the anti-bot stands, for staff.
   *
   * @param status the snapshot
   * @return the message
   */
  static Component status(AntiBotStatus status) {
    if (!status.enabled()) {
      return Component.text(
          "The anti-bot is off (anti-bot.enabled = false); the connection limits still apply.",
          NamedTextColor.GRAY);
    }
    List<String> lines = new ArrayList<>();
    AttackReport attack = status.attack();
    lines.add(
        "Anti-bot: "
            + (attack == null
                ? "no attack"
                : "ATTACK for "
                    + duration(attack.duration(status.now()))
                    + " (set off by "
                    + attack.trigger().name().toLowerCase(Locale.ROOT)
                    + ")")
            + (status.lockdown() ? ", LOCKDOWN on" : ", lockdown off"));
    for (Signal signal :
        List.of(Signal.UNKNOWN_LOGINS, Signal.AUTHENTICATION_FAILURES, Signal.CONNECTIONS)) {
      long threshold = status.signals().thresholds().getOrDefault(signal, Long.MAX_VALUE);
      lines.add(
          "  "
              + signal.name().toLowerCase(Locale.ROOT).replace('_', ' ')
              + ": "
              + status.signals().lastSecond().get(signal)
              + "/s"
              + (threshold == Long.MAX_VALUE ? "" : " (threshold " + threshold + "/s)"));
    }
    lines.add(
        "  not logged in: "
            + status.pendingConnections()
            + (status.maxPendingConnections() > 0 ? "/" + status.maxPendingConnections() : ""));
    lines.add(
        "  authentications: "
            + status.authenticationsInFlight()
            + (status.maxAuthentications() > 0 ? "/" + status.maxAuthentications() : "")
            + " running, "
            + status.authenticationQueue()
            + " waiting");
    lines.add(
        "  trusted identities: " + status.verified() + " of at most " + status.verifiedCapacity());
    long refused = 0;
    for (Refusal reason : Refusal.values()) {
      refused += status.refusals().getOrDefault(reason, 0L);
    }
    lines.add(
        "  refused before login since the start: "
            + refused
            + (status.blocklisted() >= 0
                ? "; blocklisted addresses: " + status.blocklisted()
                : ""));
    return Component.text(String.join("\n", lines), NamedTextColor.GRAY);
  }

  private static String duration(Duration duration) {
    long seconds = duration.toSeconds();
    return seconds >= 60 ? seconds / 60 + "m " + seconds % 60 + "s" : seconds + "s";
  }
}
