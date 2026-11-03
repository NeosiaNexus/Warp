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
import dev.warp.api.player.Player;
import dev.warp.api.server.RegisteredServer;
import dev.warp.api.server.ServerRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.jspecify.annotations.Nullable;

/** What the proxy's commands share: arguments, suggestions, links, pages and durations. */
final class CommandSupport {

  /** At most this many player names are suggested at once: a crowd's would not fit a packet. */
  static final int MAX_PLAYER_SUGGESTIONS = 100;

  private CommandSupport() {}

  // ---------------------------------------------------------------------------
  // Arguments and suggestions
  // ---------------------------------------------------------------------------

  /**
   * A server name argument. It takes a quoted string, so that a server whose name has characters
   * Brigadier does not allow unquoted ({@code "créatif"}) can still be named; suggestions quote
   * such names.
   *
   * @param name the argument's name
   * @param servers the servers to suggest
   * @return the argument
   */
  static RequiredArgumentBuilder<CommandSource, String> serverArgument(
      String name, ServerRegistry servers) {
    return RequiredArgumentBuilder.<CommandSource, String>argument(
            name, StringArgumentType.string())
        .suggests((context, builder) -> suggestServers(builder, servers, List.of()));
  }

  /**
   * Suggests the keywords given, then the servers, whose names start with what was typed.
   *
   * @param builder the suggestions
   * @param servers the servers
   * @param keywords words suggested first, such as {@code all}
   * @return the suggestions
   */
  static CompletableFuture<Suggestions> suggestServers(
      SuggestionsBuilder builder, ServerRegistry servers, List<String> keywords) {
    String typed = unquoted(builder.getRemaining());
    for (String keyword : keywords) {
      suggestIfMatches(builder, typed, keyword);
    }
    for (RegisteredServer server : servers.servers()) {
      suggestIfMatches(builder, typed, server.name());
    }
    return builder.buildFuture();
  }

  /**
   * Suggests the names of the players given that start with what was typed, in alphabetical order,
   * at most {@link #MAX_PLAYER_SUGGESTIONS}.
   *
   * @param builder the suggestions
   * @param players the players
   */
  static void suggestPlayers(SuggestionsBuilder builder, Collection<Player> players) {
    String typed = unquoted(builder.getRemaining()).toLowerCase(Locale.ROOT);
    players.stream()
        .map(Player::username)
        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .limit(MAX_PLAYER_SUGGESTIONS)
        .forEach(name -> builder.suggest(quoteIfNeeded(name)));
  }

  private static void suggestIfMatches(SuggestionsBuilder builder, String typed, String word) {
    if (word.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT))) {
      builder.suggest(quoteIfNeeded(word));
    }
  }

  private static String unquoted(String typed) {
    return typed.startsWith("\"") ? typed.substring(1) : typed;
  }

  /**
   * Quotes a word Brigadier would not read unquoted.
   *
   * @param word the word
   * @return the word, or the word in double quotes
   */
  static String quoteIfNeeded(String word) {
    for (int i = 0; i < word.length(); i++) {
      if (!StringReader.isAllowedInUnquotedString(word.charAt(i))) {
        return '"' + word.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
      }
    }
    return word;
  }

  // ---------------------------------------------------------------------------
  // Links
  // ---------------------------------------------------------------------------

  /**
   * Makes a component run a command when clicked.
   *
   * @param text the component
   * @param command the command, with its {@code /}
   * @param hover shown on hover
   * @return the link
   */
  static Component runs(Component text, String command, Component hover) {
    return text.clickEvent(ClickEvent.runCommand(command)).hoverEvent(HoverEvent.showText(hover));
  }

  /**
   * Makes a component type a command in the chat box when clicked.
   *
   * @param text the component
   * @param command the command, with its {@code /}
   * @param hover shown on hover
   * @return the link
   */
  static Component suggests(Component text, String command, Component hover) {
    return text.clickEvent(ClickEvent.suggestCommand(command))
        .hoverEvent(HoverEvent.showText(hover));
  }

  /**
   * Joins components with a separator.
   *
   * @param parts the components
   * @param separator between two of them
   * @return the joined component
   */
  static Component join(List<Component> parts, String separator) {
    return Component.join(JoinConfiguration.separator(Component.text(separator)), parts);
  }

  // ---------------------------------------------------------------------------
  // Pages
  // ---------------------------------------------------------------------------

  /**
   * One page of a list.
   *
   * @param page the page, from 1
   * @param pages how many there are, at least 1
   * @param from the index of its first item
   * @param to the index after its last item
   */
  record Page(int page, int pages, int from, int to) {

    /**
     * Cuts a list into pages.
     *
     * @param size the list's size
     * @param pageSize the items per page
     * @param page the page wanted, from 1
     * @return the page, or {@code null} if there is no such page
     */
    static @Nullable Page of(int size, int pageSize, int page) {
      int pages = Math.max(1, (size + pageSize - 1) / pageSize);
      if (page < 1 || page > pages) {
        return null;
      }
      int from = (page - 1) * pageSize;
      return new Page(page, pages, from, Math.min(size, from + pageSize));
    }

    /**
     * Returns the number of pages a list has.
     *
     * @param size the list's size
     * @param pageSize the items per page
     * @return at least 1
     */
    static int count(int size, int pageSize) {
      return Math.max(1, (size + pageSize - 1) / pageSize);
    }

    /**
     * The line under a page: links to the previous and next pages, when there are, around "page
     * 2/5".
     *
     * @param command the command showing a page, with its {@code /}, to which the page is appended
     * @return the line
     */
    Component navigation(String command) {
      List<Component> parts = new ArrayList<>();
      if (page > 1) {
        parts.add(
            runs(
                Message.PAGE_PREVIOUS.render(),
                command + " " + (page - 1),
                Message.PAGE_PREVIOUS_HOVER.render(Message.text("page", "" + (page - 1)))));
      }
      parts.add(
          Message.PAGE_STATUS.render(
              Message.text("page", "" + page), Message.text("pages", "" + pages)));
      if (page < pages) {
        parts.add(
            runs(
                Message.PAGE_NEXT.render(),
                command + " " + (page + 1),
                Message.PAGE_NEXT_HOVER.render(Message.text("page", "" + (page + 1)))));
      }
      return join(parts, " ");
    }
  }

  // ---------------------------------------------------------------------------
  // Formatting
  // ---------------------------------------------------------------------------

  /**
   * Formats a duration for people: {@code 3d 4h}, {@code 4h 12m}, {@code 12m 5s}, {@code 5s}.
   *
   * @param duration the duration
   * @return the text
   */
  static String duration(Duration duration) {
    long seconds = Math.max(0, duration.toSeconds());
    long days = seconds / 86_400;
    long hours = seconds % 86_400 / 3_600;
    long minutes = seconds % 3_600 / 60;
    long secs = seconds % 60;
    if (days > 0) {
      return days + "d " + hours + "h";
    }
    if (hours > 0) {
      return hours + "h " + minutes + "m";
    }
    if (minutes > 0) {
      return minutes + "m " + secs + "s";
    }
    return secs + "s";
  }

  /**
   * Sorts players by name, ignoring case.
   *
   * @return the order
   */
  static Comparator<Player> byName() {
    return Comparator.comparing(Player::username, String.CASE_INSENSITIVE_ORDER);
  }
}
