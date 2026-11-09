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
import dev.warp.api.fleet.Fleet;
import dev.warp.api.fleet.ProxyNode;
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.proxy.i18n.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * {@code /glist}: who plays where, for staff granted {@value #PERMISSION}. Readable on a network of
 * thousands: counts first, names on demand, a page at a time.
 *
 * <ul>
 *   <li>{@code /glist}: the total, then one line per server with players and its count (a link to
 *       its names), the empty servers on one line, and the players not on a server yet
 *   <li>{@code /glist <server> [page]}: the names of the players on a server, {@value #PAGE_SIZE} a
 *       page, alphabetical
 *   <li>{@code /glist all [page]}: every name, grouped by server, {@value #PAGE_SIZE} a page
 * </ul>
 *
 * <p>On a fleet, the total and the servers' counts cover every proxy, and one line per proxy
 * follows; the names listed are this proxy's, each proxy lists its own.
 */
final class GlistCommand {

  /** The permission the command needs. */
  static final String PERMISSION = "warp.command.glist";

  /** Names per page: about ten lines of chat. */
  static final int PAGE_SIZE = 60;

  /** The word that lists every server. */
  static final String ALL = "all";

  private GlistCommand() {}

  /**
   * Builds {@code /glist}.
   *
   * @param proxy the proxy
   * @return the command
   */
  static LiteralArgumentBuilder<CommandSource> build(Warp proxy) {
    return LiteralArgumentBuilder.<CommandSource>literal("glist")
        .requires(source -> source.hasPermission(PERMISSION))
        .executes(
            context -> {
              context.getSource().sendMessage(overview(proxy));
              return Command.SINGLE_SUCCESS;
            })
        .then(
            RequiredArgumentBuilder.<CommandSource, String>argument(
                    "server", StringArgumentType.string())
                .suggests(
                    (context, builder) ->
                        CommandSupport.suggestServers(builder, proxy.servers(), List.of(ALL)))
                .executes(
                    context ->
                        names(
                            proxy,
                            context.getSource(),
                            StringArgumentType.getString(context, "server"),
                            1))
                .then(
                    RequiredArgumentBuilder.<CommandSource, Integer>argument(
                            "page", IntegerArgumentType.integer(1))
                        .executes(
                            context ->
                                names(
                                    proxy,
                                    context.getSource(),
                                    StringArgumentType.getString(context, "server"),
                                    IntegerArgumentType.getInteger(context, "page")))));
  }

  /**
   * The answer to {@code /glist}.
   *
   * @param proxy the proxy
   * @return the message
   */
  static Component overview(Warp proxy) {
    Fleet fleet = proxy.fleet();
    boolean networked = fleet.nodes().size() > 1;
    int total = networked ? fleet.playerCount() : proxy.playerCount();
    List<Component> lines = new ArrayList<>();
    lines.add(Message.GLIST_TOTAL.with(Message.count(total)));
    List<String> empty = new ArrayList<>();
    int onServers = 0;
    for (RegisteredServer server : proxy.servers().servers()) {
      int count = networked ? fleet.playerCount(server.name()) : server.playerCount();
      if (count == 0) {
        empty.add(server.name());
        continue;
      }
      onServers += count;
      lines.add(
          Message.GLIST_SERVER.with(
              Message.component("server", serverLink(server.name())),
              Message.number("count", count)));
    }
    if (!empty.isEmpty()) {
      lines.add(Message.GLIST_EMPTY.with(Message.text("servers", String.join(", ", empty))));
    }
    if (total > onServers) {
      lines.add(Message.GLIST_CONNECTING.with(Message.number("count", total - onServers)));
    }
    if (networked) {
      List<ProxyNode> nodes = new ArrayList<>(fleet.nodes());
      nodes.sort(java.util.Comparator.comparing(ProxyNode::id));
      for (ProxyNode node : nodes) {
        lines.add(
            Message.GLIST_NODE.with(
                Message.text("node", node.id()), Message.number("count", node.players())));
      }
    }
    return Component.join(JoinConfiguration.newlines(), lines);
  }

  /** A server's name, a link to the names of its players. */
  static Component serverLink(String server) {
    return CommandSupport.runs(
        Message.VALUE.with(Message.text("text", server)),
        "/glist " + CommandSupport.quoteIfNeeded(server),
        Message.GLIST_SERVER_HOVER.with(Message.text("server", server)));
  }

  /** {@code /glist <server|all> [page]}. */
  private static int names(Warp proxy, CommandSource source, String target, int page) {
    boolean all = target.toLowerCase(Locale.ROOT).equals(ALL);
    List<RegisteredServer> servers;
    if (all) {
      servers = List.copyOf(proxy.servers().servers());
    } else {
      Optional<RegisteredServer> server = proxy.servers().server(target);
      if (server.isEmpty()) {
        source.sendMessage(Message.UNKNOWN_SERVER.with(Message.text("server", target)));
        return 0;
      }
      servers = List.of(server.get());
    }
    source.sendMessage(names(servers, all, page));
    return Command.SINGLE_SUCCESS;
  }

  /**
   * The names of the players on servers, one page of them.
   *
   * @param servers the servers
   * @param all whether they are every server ({@code /glist all})
   * @param page the page, from 1
   * @return the message
   */
  static Component names(List<RegisteredServer> servers, boolean all, int page) {
    record Entry(String server, String player) {}
    List<Entry> entries = new ArrayList<>();
    for (RegisteredServer server : servers) {
      server.players().stream()
          .sorted(CommandSupport.byName())
          .map(Player::username)
          .forEach(name -> entries.add(new Entry(server.name(), name)));
    }
    String name = all ? ALL : servers.getFirst().name();
    if (entries.isEmpty()) {
      return all
          ? Message.GLIST_NOBODY_ALL.of()
          : Message.GLIST_NOBODY.with(Message.text("server", name));
    }
    CommandSupport.Page slice = CommandSupport.Page.of(entries.size(), PAGE_SIZE, page);
    if (slice == null) {
      return Message.PAGE_OUT_OF_RANGE.with(
          Message.number("page", page),
          Message.number("pages", CommandSupport.Page.count(entries.size(), PAGE_SIZE)));
    }
    List<Component> lines = new ArrayList<>();
    lines.add(
        all
            ? Message.GLIST_HEADER_ALL.with(Message.count(entries.size()))
            : Message.GLIST_HEADER.with(
                Message.text("server", name), Message.count(entries.size())));
    List<Entry> shown = entries.subList(slice.from(), slice.to());
    int start = 0;
    while (start < shown.size()) {
      String server = shown.get(start).server();
      int end = start;
      List<String> group = new ArrayList<>();
      while (end < shown.size() && shown.get(end).server().equals(server)) {
        group.add(shown.get(end).player());
        end++;
      }
      Component players = Component.text(String.join(", ", group), NamedTextColor.WHITE);
      lines.add(
          all
              ? Message.GLIST_GROUP.with(
                  Message.component("server", serverLink(server)),
                  Message.component("names", players))
              : players);
      start = end;
    }
    if (slice.pages() > 1) {
      lines.add(slice.navigation("/glist " + CommandSupport.quoteIfNeeded(name)));
    }
    return Component.join(JoinConfiguration.newlines(), lines);
  }
}
