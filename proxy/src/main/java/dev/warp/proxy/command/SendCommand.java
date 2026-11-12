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
import dev.warp.api.fleet.SendResult;
import dev.warp.api.player.ConnectionResult;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerConnection;
import dev.warp.proxy.i18n.Message;
import dev.warp.proxy.i18n.PlayerMessages;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;

/**
 * {@code /send}: moves players to a server, for staff granted {@value #PERMISSION}.
 *
 * <ul>
 *   <li>{@code /send <player|server> <destination>}: a player, or everyone on a server. A name that
 *       is both is refused rather than guessed: {@code /send player <name> <destination>} and
 *       {@code /send server <name> <destination>} say which
 *   <li>{@code /send all <destination>}: everyone
 *   <li>{@code /send current <destination>}: everyone on the sender's server (players only)
 * </ul>
 *
 * <p>Players move through {@link Player#connect}, the path {@code /server} takes: {@code
 * ServerPreConnectEvent} may deny or redirect each one, and a player the destination refuses stays
 * where it was. Players already there are not moved. Before moving several, the destination is
 * pinged: when it does not answer nobody is moved. At most {@value #MAX_IN_FLIGHT} connections are
 * in flight at once, so that a {@code /send all} does not flood the destination with logins. The
 * sender then gets one line: how many were sent, and why each of the others was not, the players'
 * names on hover.
 */
final class SendCommand {

  /** The permission the command needs. */
  static final String PERMISSION = "warp.command.send";

  /** How many players are connecting to the destination at once, at most. */
  static final int MAX_IN_FLIGHT = 16;

  /** How long one player's move may take before the summary counts it as unanswered. */
  static final Duration MOVE_TIMEOUT = Duration.ofSeconds(60);

  /** How many names a failure's hover lists before "and N more". */
  static final int MAX_NAMES_SHOWN = 20;

  private static final PlainTextComponentSerializer PLAIN =
      PlainTextComponentSerializer.plainText();

  private SendCommand() {}

  /** Who a command names: players, or why it names none. */
  private sealed interface Selection {}

  /**
   * Players to move.
   *
   * @param players the players
   * @param from where they are, for the summary
   * @param single whether the command named one player
   */
  private record Players(List<Player> players, Component from, boolean single)
      implements Selection {}

  /**
   * Nobody: the command names nobody, and says why.
   *
   * @param message what the sender is told
   */
  private record Refusal(Component message) implements Selection {}

  /**
   * A player on another proxy of the fleet, moved through its own proxy.
   *
   * @param player the player
   */
  private record Remote(FleetPlayer player) implements Selection {}

  /**
   * What became of one player.
   *
   * @param player the player
   * @param reason why it was not sent, or {@code null} if it plays on the destination
   */
  record Outcome(Player player, @Nullable Component reason) {}

  // ---------------------------------------------------------------------------
  // Tree
  // ---------------------------------------------------------------------------

  /**
   * Builds {@code /send}.
   *
   * @param proxy the proxy
   * @return the command
   */
  static LiteralArgumentBuilder<CommandSource> build(Warp proxy) {
    return LiteralArgumentBuilder.<CommandSource>literal("send")
        .requires(source -> source.hasPermission(PERMISSION))
        .then(
            LiteralArgumentBuilder.<CommandSource>literal("all")
                .then(destination(proxy, context -> all(proxy))))
        .then(
            LiteralArgumentBuilder.<CommandSource>literal("current")
                .then(destination(proxy, context -> current(context.getSource()))))
        .then(
            LiteralArgumentBuilder.<CommandSource>literal("player")
                .then(
                    nameArgument(proxy, false)
                        .then(
                            destination(
                                proxy,
                                context ->
                                    player(proxy, StringArgumentType.getString(context, "name"))))))
        .then(
            LiteralArgumentBuilder.<CommandSource>literal("server")
                .then(
                    nameArgument(proxy, true)
                        .then(
                            destination(
                                proxy,
                                context ->
                                    server(proxy, StringArgumentType.getString(context, "name"))))))
        .then(
            RequiredArgumentBuilder.<CommandSource, String>argument(
                    "target", StringArgumentType.string())
                .suggests(
                    (context, builder) -> {
                      CommandSupport.suggestPlayers(builder, proxy.players());
                      return CommandSupport.suggestServers(builder, proxy.servers(), List.of());
                    })
                .then(
                    destination(
                        proxy,
                        context ->
                            target(proxy, StringArgumentType.getString(context, "target")))));
  }

  private static RequiredArgumentBuilder<CommandSource, String> nameArgument(
      Warp proxy, boolean server) {
    return RequiredArgumentBuilder.<CommandSource, String>argument(
            "name", StringArgumentType.string())
        .suggests(
            (context, builder) -> {
              if (server) {
                return CommandSupport.suggestServers(builder, proxy.servers(), List.of());
              }
              CommandSupport.suggestPlayers(builder, proxy.players());
              return builder.buildFuture();
            });
  }

  private static RequiredArgumentBuilder<CommandSource, String> destination(
      Warp proxy, Function<CommandContext<CommandSource>, Selection> selection) {
    return CommandSupport.serverArgument("server", proxy.servers())
        .executes(
            context ->
                send(
                    proxy,
                    context.getSource(),
                    selection.apply(context),
                    StringArgumentType.getString(context, "server")));
  }

  // ---------------------------------------------------------------------------
  // Selections
  // ---------------------------------------------------------------------------

  private static Selection all(Warp proxy) {
    return new Players(List.copyOf(proxy.players()), Message.SEND_TARGET_ALL.of(), false);
  }

  private static Selection current(CommandSource source) {
    Optional<ServerConnection> current =
        source instanceof Player player ? player.currentServer() : Optional.empty();
    if (current.isEmpty()) {
      return new Refusal(Message.SEND_NO_CURRENT.of());
    }
    return onServer(current.get().server());
  }

  private static Selection player(Warp proxy, String name) {
    return proxy
        .player(name)
        .<Selection>map(player -> new Players(List.of(player), Component.empty(), true))
        .or(() -> remote(proxy, name))
        .orElseGet(() -> new Refusal(Message.UNKNOWN_PLAYER.with(Message.text("player", name))));
  }

  /** A player on another proxy of the fleet, if the name is one's. */
  private static Optional<Selection> remote(Warp proxy, String name) {
    return proxy
        .fleet()
        .player(name)
        .filter(player -> !player.proxyId().equals(proxy.fleet().local().id()))
        .map(Remote::new);
  }

  private static Selection server(Warp proxy, String name) {
    return proxy
        .servers()
        .server(name)
        .map(SendCommand::onServer)
        .orElseGet(() -> new Refusal(Message.UNKNOWN_SERVER.with(Message.text("server", name))));
  }

  private static Selection onServer(RegisteredServer server) {
    return new Players(
        List.copyOf(server.players()),
        Message.SEND_TARGET_SERVER.with(Message.text("server", server.name())),
        false);
  }

  /** A bare name: a player or a server, refused when it is both. */
  private static Selection target(Warp proxy, String name) {
    boolean isServer = proxy.servers().server(name).isPresent();
    boolean isPlayer = proxy.player(name).isPresent() || remote(proxy, name).isPresent();
    if (isServer && isPlayer) {
      return new Refusal(Message.SEND_AMBIGUOUS.with(Message.text("target", name)));
    }
    if (isServer) {
      return server(proxy, name);
    }
    if (isPlayer) {
      return player(proxy, name);
    }
    return new Refusal(Message.SEND_UNKNOWN_TARGET.with(Message.text("target", name)));
  }

  // ---------------------------------------------------------------------------
  // Sending
  // ---------------------------------------------------------------------------

  private static int send(
      Warp proxy, CommandSource source, Selection selection, String destinationName) {
    if (selection instanceof Remote(FleetPlayer player)) {
      return sendRemote(proxy, source, player, destinationName);
    }
    Optional<RegisteredServer> found = proxy.servers().server(destinationName);
    if (found.isEmpty()) {
      source.sendMessage(Message.UNKNOWN_SERVER.with(Message.text("server", destinationName)));
      return 0;
    }
    RegisteredServer destination = found.get();
    if (!(selection instanceof Players(List<Player> players, Component from, boolean single))) {
      source.sendMessage(((Refusal) selection).message());
      return 0;
    }
    if (players.isEmpty()) {
      source.sendMessage(Message.SEND_NOBODY.with(Message.component("target", from)));
      return 0;
    }
    List<Player> moving = new ArrayList<>();
    List<Player> there = new ArrayList<>();
    for (Player player : players) {
      (isOn(player, destination) ? there : moving).add(player);
    }
    if (moving.isEmpty()) {
      source.sendMessage(
          Message.SEND_ALREADY_THERE.with(
              Message.count(there.size()), Message.text("server", destination.name())));
      return 0;
    }
    if (!single) {
      String unreachable = unreachable(destination);
      if (unreachable != null) {
        source.sendMessage(
            Message.SEND_UNREACHABLE.with(
                Message.text("server", destination.name()), Message.text("error", unreachable)));
        return 0;
      }
      source.sendMessage(
          Message.SEND_STARTED.with(
              Message.number("count", moving.size()),
              Message.component("target", from),
              Message.text("server", destination.name())));
    }
    List<Outcome> outcomes = new ArrayList<>(moveAll(moving, destination));
    for (Player player : there) {
      outcomes.add(new Outcome(player, Message.SEND_REASON_ALREADY.of()));
    }
    source.sendMessage(summary(outcomes, from, destination, single));
    return Command.SINGLE_SUCCESS;
  }

  /** Moves a player of another proxy through that proxy, which knows its own servers. */
  private static int sendRemote(
      Warp proxy, CommandSource source, FleetPlayer player, String destination) {
    SendResult result;
    try {
      result =
          proxy
              .fleet()
              .send(player.uuid(), destination)
              .get(MOVE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      result = SendResult.FAILED;
    } catch (ExecutionException | TimeoutException e) {
      result = SendResult.TIMED_OUT;
    }
    if (result == SendResult.SENT) {
      source.sendMessage(
          Message.SEND_REMOTE_DONE.with(
              Message.text("player", player.name()),
              Message.text("server", destination),
              Message.text("node", player.proxyId())));
      return Command.SINGLE_SUCCESS;
    }
    Message reason =
        switch (result) {
          case UNKNOWN_SERVER -> Message.FLEET_RESULT_UNKNOWN_SERVER;
          case UNKNOWN_PLAYER -> Message.FLEET_RESULT_UNKNOWN_PLAYER;
          case TIMED_OUT -> Message.FLEET_RESULT_TIMED_OUT;
          case SENT, FAILED -> Message.FLEET_RESULT_FAILED;
        };
    source.sendMessage(
        Message.SEND_REMOTE_FAILED.with(
            Message.text("player", player.name()),
            Message.text("server", destination),
            Message.text("node", player.proxyId()),
            Message.component("reason", reason.of())));
    return 0;
  }

  private static boolean isOn(Player player, RegisteredServer server) {
    return player
        .currentServer()
        .map(connection -> connection.server().name().equalsIgnoreCase(server.name()))
        .orElse(false);
  }

  /** Pings the destination; returns why it does not answer, or {@code null} if it does. */
  private static @Nullable String unreachable(RegisteredServer destination) {
    try {
      var _ = destination.ping().get();
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "interrupted";
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
  }

  /**
   * Moves players to a server, {@value #MAX_IN_FLIGHT} at a time, and waits for every outcome.
   * Blocks: commands run on virtual threads.
   *
   * @param players the players
   * @param server the server
   * @return what became of each, in the order given
   */
  static List<Outcome> moveAll(List<Player> players, RegisteredServer server) {
    Semaphore slots = new Semaphore(MAX_IN_FLIGHT);
    List<CompletableFuture<Outcome>> moves = new ArrayList<>(players.size());
    for (Player player : players) {
      slots.acquireUninterruptibly();
      CompletableFuture<ConnectionResult> move;
      try {
        move = player.connect(server);
      } catch (RuntimeException e) {
        slots.release();
        moves.add(CompletableFuture.completedFuture(new Outcome(player, error(e))));
        continue;
      }
      moves.add(
          move.orTimeout(MOVE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
              .handle(
                  (result, failure) -> {
                    slots.release();
                    return new Outcome(player, reason(result, failure));
                  }));
    }
    return moves.stream().map(CompletableFuture::join).toList();
  }

  /** Why a move failed, or {@code null} if the player plays on the destination. */
  private static @Nullable Component reason(
      @Nullable ConnectionResult result, @Nullable Throwable failure) {
    if (failure != null) {
      Throwable cause =
          failure instanceof CompletionException && failure.getCause() != null
              ? failure.getCause()
              : failure;
      return cause instanceof TimeoutException
          ? Message.SEND_REASON_TIMEOUT.with(Message.text("seconds", "" + MOVE_TIMEOUT.toSeconds()))
          : error(cause);
    }
    if (result == null) {
      return error(new IllegalStateException("no result"));
    }
    return switch (result) {
      case ConnectionResult.Connected _, ConnectionResult.AlreadyConnected _ -> null;
      case ConnectionResult.InProgress _ -> Message.SEND_REASON_SWITCHING.of();
      case ConnectionResult.Denied _ -> Message.SEND_REASON_DENIED.of();
      case ConnectionResult.Failed failed ->
          failed
              .serverReason()
              .map(reason -> Message.SEND_REASON_REFUSED.with(Message.component("reason", reason)))
              .orElseGet(Message.SEND_REASON_UNREACHABLE::of);
      case ConnectionResult.Disconnected _ -> Message.SEND_REASON_LEFT.of();
    };
  }

  private static Component error(Throwable failure) {
    return Message.SEND_REASON_ERROR.with(
        Message.text(
            "error",
            failure.getMessage() != null
                ? failure.getMessage()
                : failure.getClass().getSimpleName()));
  }

  // ---------------------------------------------------------------------------
  // Summary
  // ---------------------------------------------------------------------------

  /**
   * The sender's answer: one line however many players moved.
   *
   * @param outcomes what became of each player
   * @param from where they were, for several
   * @param destination the destination
   * @param single whether the command named one player
   * @return the message
   */
  static Component summary(
      List<Outcome> outcomes, Component from, RegisteredServer destination, boolean single) {
    if (single && outcomes.size() == 1) {
      Outcome outcome = outcomes.getFirst();
      Component reason = outcome.reason();
      return reason == null
          ? Message.SEND_ONE_DONE.with(
              Message.text("player", outcome.player().username()),
              Message.text("server", destination.name()))
          : Message.SEND_ONE_FAILED.with(
              Message.text("player", outcome.player().username()),
              Message.text("server", destination.name()),
              Message.component("reason", reason));
    }
    // Grouped by what the reason reads, in order of appearance.
    record Failure(Component reason, List<Player> players) {}
    Map<String, Failure> failures = new LinkedHashMap<>();
    int sent = 0;
    for (Outcome outcome : outcomes) {
      Component reason = outcome.reason();
      if (reason == null) {
        sent++;
      } else {
        failures
            .computeIfAbsent(
                PLAIN.serialize(PlayerMessages.english(reason)),
                _ -> new Failure(reason, new ArrayList<>()))
            .players()
            .add(outcome.player());
      }
    }
    Component details = Component.empty();
    if (!failures.isEmpty()) {
      List<Component> groups = new ArrayList<>();
      for (Failure group : failures.values()) {
        groups.add(
            Message.SEND_FAILURE_GROUP
                .with(
                    Message.number("count", group.players().size()),
                    Message.component("reason", group.reason()))
                .hoverEvent(HoverEvent.showText(names(group.players()))));
      }
      details =
          Message.SEND_FAILURES.with(
              Message.component("groups", CommandSupport.join(groups, ", ")));
    }
    return Message.SEND_SUMMARY.with(
        Message.number("sent", sent),
        Message.number("count", outcomes.size()),
        Message.component("target", from),
        Message.text("server", destination.name()),
        Message.component("failures", details));
  }

  /** The names of a group's players, at most {@value #MAX_NAMES_SHOWN}. */
  private static Component names(List<Player> group) {
    List<String> names =
        group.stream().map(Player::username).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    String shown = String.join(", ", names.subList(0, Math.min(names.size(), MAX_NAMES_SHOWN)));
    if (names.size() <= MAX_NAMES_SHOWN) {
      return Message.VALUE.with(Message.text("text", shown));
    }
    return Message.SEND_MORE.with(
        Message.component("names", Message.VALUE.with(Message.text("text", shown))),
        Message.number("count", names.size() - MAX_NAMES_SHOWN));
  }
}
