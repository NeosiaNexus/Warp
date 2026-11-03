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

import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * What the proxy's commands say: each message a stable key and its English template, in
 * MiniMessage.
 *
 * <p>Templates style text with the commands' own tags rather than colors, so that every command
 * looks the same and a theme changes in one place: {@code <value>} for names and numbers, {@code
 * <good>} for success, {@code <bad>} for failures, {@code <warn>} for what needs attention, {@code
 * <dim>} for hints; plain text is gray. A translation replaces templates by key and keeps the tags
 * and placeholders. Placeholders typed by people (names, reasons) are inserted unparsed, so a
 * player called {@code <red>} stays a name.
 */
enum Message {

  // ---------------------------------------------------------------------------
  // Shared
  // ---------------------------------------------------------------------------

  VALUE("value", "<value><text></value>"),
  UNKNOWN_SERVER("unknown-server", "<bad>Unknown server: <value><server></value>"),
  UNKNOWN_PLAYER("unknown-player", "<bad>No player named <value><player></value> is connected."),
  PAGE_STATUS("page.status", "<dim>page <page>/<pages></dim>"),
  PAGE_PREVIOUS("page.previous", "<value>« previous</value>"),
  PAGE_NEXT("page.next", "<value>next »</value>"),
  PAGE_PREVIOUS_HOVER("page.previous.hover", "Page <page>"),
  PAGE_NEXT_HOVER("page.next.hover", "Page <page>"),
  PAGE_OUT_OF_RANGE("page.out-of-range", "<bad>There is no page <page>: <pages> in all."),

  // ---------------------------------------------------------------------------
  // /server
  // ---------------------------------------------------------------------------

  SERVER_ALREADY_CONNECTED("server.already-connected", "Already connected to <server>."),
  SERVER_IN_PROGRESS("server.in-progress", "Already switching servers."),
  SERVER_DENIED("server.denied", "<bad>You cannot join <value><server></value> right now."),
  SERVER_NOT_A_PLAYER(
      "server.not-a-player",
      "<bad>Only players switch servers: move them with <value>/send \\<player> \\<server></value>."),

  // ---------------------------------------------------------------------------
  // /warp
  // ---------------------------------------------------------------------------

  WARP_VERSION("warp.version", "<value>Warp <version></value> <dim>(<commit> on <branch>)</dim>"),
  WARP_VERSION_HOVER("warp.version.hover", "Click to copy"),
  WARP_RUNTIME(
      "warp.runtime",
      "Up <value><uptime></value> <dim>·</dim> Java <value><java></value> <dim>(<vm>)</dim>"),
  WARP_PLAYERS(
      "warp.players",
      "Players <value><players></value> <dim>·</dim> Servers <value><servers></value>"),
  WARP_COMPRESSION("warp.compression", "Compression <value><compression></value>"),
  WARP_MEMORY(
      "warp.memory",
      "Heap <value><used></value> of <value><max></value> MiB <dim>·</dim> Direct"
          + " <value><direct></value> MiB"),
  WARP_PLUGINS("warp.plugins", "Plugins <value><count></value> <dim>(<states>)</dim>"),
  WARP_PLUGINS_NONE("warp.plugins-none", "Plugins <value>none</value>"),
  WARP_DRAINING(
      "warp.draining",
      "<warn>Shutting down</warn> for <value><elapsed></value> (<phase>, by <trigger>),"
          + " <value><players></value> players left"),
  WARP_SUBCOMMANDS("warp.subcommands", "<dim>More:</dim> <commands>"),
  WARP_SUBCOMMAND_HOVER("warp.subcommand.hover", "Click to type <command>"),

  // ---------------------------------------------------------------------------
  // /warp plugins
  // ---------------------------------------------------------------------------

  PLUGINS_NONE("plugins.none", "No plugins: drop their jars into <value>plugins/</value>."),
  PLUGINS_HEADER(
      "plugins.header",
      "<value><count></value> <n:'0#plugins|1#plugin|2#plugins'> <dim>(<states>)</dim>"),
  PLUGINS_LINE("plugins.line", "<dim>-</dim> <name> <dim><version></dim> <state>"),
  PLUGINS_HOVER("plugins.hover", "<value><id></value> <version><newline><description>"),
  PLUGINS_HOVER_AUTHORS("plugins.hover.authors", "<newline><dim>by</dim> <authors>"),
  PLUGINS_HOVER_URL("plugins.hover.url", "<newline><dim><url></dim>"),
  PLUGINS_HOVER_FAILURE("plugins.hover.failure", "<newline><bad><failure>"),
  PLUGIN_STATE_LOADING("plugin-state.loading", "<warn>loading</warn>"),
  PLUGIN_STATE_ENABLED("plugin-state.enabled", "<good>enabled</good>"),
  PLUGIN_STATE_FAILED("plugin-state.failed", "<bad>failed</bad>"),
  PLUGIN_STATE_DISABLED("plugin-state.disabled", "<dim>disabled</dim>"),
  PLUGIN_STATE_COUNT("plugin-state.count", "<count> <state>"),

  // ---------------------------------------------------------------------------
  // /glist
  // ---------------------------------------------------------------------------

  GLIST_TOTAL(
      "glist.total",
      "<value><count></value> <n:'0#players|1#player|2#players'> online"
          + " <dim>(click a server, or /glist \\<server|all>, for names)</dim>"),
  GLIST_SERVER("glist.server", "<dim>-</dim> <server> <value><count></value>"),
  GLIST_SERVER_HOVER("glist.server.hover", "List the players on <server>"),
  GLIST_EMPTY("glist.empty", "<dim>- empty: <servers></dim>"),
  GLIST_CONNECTING("glist.connecting", "<dim>- not on a server yet: <count></dim>"),
  GLIST_HEADER(
      "glist.header",
      "<value><server></value> <dim>·</dim> <value><count></value>"
          + " <n:'0#players|1#player|2#players'>"),
  GLIST_HEADER_ALL(
      "glist.header-all",
      "All servers <dim>·</dim> <value><count></value> <n:'0#players|1#player|2#players'>"),
  GLIST_GROUP("glist.group", "<server><dim>:</dim> <names>"),
  GLIST_NOBODY("glist.nobody", "Nobody is on <value><server></value>."),
  GLIST_NOBODY_ALL("glist.nobody-all", "Nobody is online."),

  // ---------------------------------------------------------------------------
  // /send
  // ---------------------------------------------------------------------------

  SEND_UNKNOWN_TARGET(
      "send.unknown-target", "<bad>No player or server is named <value><target></value>."),
  SEND_AMBIGUOUS(
      "send.ambiguous",
      "<bad><value><target></value> is both a player and a server: say which with <value>/send"
          + " player <target></value> or <value>/send server <target></value>, then the"
          + " destination."),
  SEND_NO_CURRENT(
      "send.no-current", "<bad><value>current</value> means your server, and you are on none."),
  SEND_NOBODY("send.nobody", "<warn>Nobody to send: <target> is empty."),
  SEND_ALREADY_THERE(
      "send.already-there",
      "<warn>Nothing to do: <value><count></value> <n:'0#players are|1#player is|2#players are'>"
          + " already on <value><server></value>."),
  SEND_UNREACHABLE(
      "send.unreachable",
      "<bad><value><server></value> does not answer (<error>): nobody was sent."),
  SEND_STARTED(
      "send.started",
      "Sending <value><count></value> players from <target> to <value><server></value>..."),
  SEND_ONE_DONE("send.one-done", "<good>Sent <value><player></value> to <value><server></value>."),
  SEND_ONE_FAILED(
      "send.one-failed",
      "<bad>Could not send <value><player></value> to <value><server></value>: <reason>."),
  SEND_SUMMARY(
      "send.summary",
      "<good>Sent <value><sent></value> of <value><count></value></good> from <target> to"
          + " <value><server></value><failures>"),
  SEND_FAILURES("send.failures", "<dim>; not sent:</dim> <groups>"),
  SEND_FAILURE_GROUP("send.failure-group", "<bad><count> <reason></bad>"),
  SEND_MORE("send.more", "<names> and <count> more"),
  SEND_TARGET_ALL("send.target.all", "every server"),
  SEND_TARGET_SERVER("send.target.server", "<value><server></value>"),
  SEND_REASON_ALREADY("send.reason.already", "already there"),
  SEND_REASON_SWITCHING("send.reason.switching", "already switching servers"),
  SEND_REASON_DENIED("send.reason.denied", "denied by a plugin"),
  SEND_REASON_REFUSED("send.reason.refused", "refused: <reason>"),
  SEND_REASON_UNREACHABLE("send.reason.unreachable", "server unreachable or too slow"),
  SEND_REASON_LEFT("send.reason.left", "left the proxy"),
  SEND_REASON_TIMEOUT("send.reason.timeout", "no answer within <seconds> s"),
  SEND_REASON_ERROR("send.reason.error", "error: <error>"),

  // ---------------------------------------------------------------------------
  // /shutdown
  // ---------------------------------------------------------------------------

  SHUTDOWN_REASON("shutdown.reason", "<warn>The server is shutting down: <reason>"),
  SHUTDOWN_STARTED(
      "shutdown.started",
      "<warn>Shutting down.</warn> <value><count></value> <n:'0#players are|1#player is|2#players"
          + " are'> <plan>; Warp stops once empty, or in <value><timeout></value> at most."),
  SHUTDOWN_PLAN_TRANSFER(
      "shutdown.plan.transfer",
      "transferred to <value><target></value> from <value><delay></value> on (clients older than"
          + " 1.20.5 are <fallback>)"),
  SHUTDOWN_PLAN_KICK("shutdown.plan.kick", "kicked from <value><delay></value> on"),
  SHUTDOWN_PLAN_KEEP("shutdown.plan.keep", "kept until they leave"),
  SHUTDOWN_FALLBACK_KICK("shutdown.fallback.kick", "kicked"),
  SHUTDOWN_FALLBACK_KEEP("shutdown.fallback.keep", "kept"),
  SHUTDOWN_ALREADY(
      "shutdown.already",
      "<warn>Already shutting down</warn> for <value><elapsed></value> (<phase>, by <trigger>),"
          + " <value><players></value> players left."),
  SHUTDOWN_REFUSED("shutdown.refused", "<bad>Cannot shut down: <error>"),

  // ---------------------------------------------------------------------------
  // /find
  // ---------------------------------------------------------------------------

  FIND_ON(
      "find.on",
      "<value><player></value> is on <server> <dim>(<ping> ms, <version>)</dim><address>"),
  FIND_CONNECTING(
      "find.connecting",
      "<value><player></value> is not on a server yet <dim>(<version>)</dim><address>"),
  FIND_ADDRESS("find.address", " <dim>from</dim> <value><address></value>"),
  FIND_SERVER_HOVER("find.server.hover", "List the players on <server>"),

  // ---------------------------------------------------------------------------
  // /alert
  // ---------------------------------------------------------------------------

  ALERT("alert", "<red>[Alert]</red> <white><message></white>"),
  ALERT_SENT(
      "alert.sent",
      "<good>Alert sent to <value><count></value> <n:'0#players|1#player|2#players'>.");

  /** The commands' styles, the tags templates use instead of colors. */
  private static final TagResolver STYLES =
      TagResolver.resolver(
          TagResolver.resolver("value", Tag.styling(NamedTextColor.AQUA)),
          TagResolver.resolver("good", Tag.styling(NamedTextColor.GREEN)),
          TagResolver.resolver("bad", Tag.styling(NamedTextColor.RED)),
          TagResolver.resolver("warn", Tag.styling(NamedTextColor.GOLD)),
          TagResolver.resolver("dim", Tag.styling(NamedTextColor.DARK_GRAY)));

  private static final MiniMessage MINI_MESSAGE =
      MiniMessage.builder().tags(TagResolver.resolver(TagResolver.standard(), STYLES)).build();

  private final String key;
  private final String template;

  Message(String key, String template) {
    this.key = "warp.command." + key;
    this.template = template;
  }

  /**
   * Returns the message's key, under which a translation would replace its template.
   *
   * @return the key, such as {@code warp.command.send.summary}
   */
  String key() {
    return key;
  }

  /**
   * Returns the English template.
   *
   * @return the template, in MiniMessage
   */
  String template() {
    return template;
  }

  /**
   * Renders the message, gray where its template sets no color and never italic (as item lore would
   * be).
   *
   * @param placeholders the message's placeholders
   * @return the message
   */
  Component render(TagResolver... placeholders) {
    return MINI_MESSAGE
        .deserialize(template, TagResolver.resolver(placeholders))
        .colorIfAbsent(NamedTextColor.GRAY)
        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
  }

  // ---------------------------------------------------------------------------
  // Placeholders
  // ---------------------------------------------------------------------------

  /**
   * A placeholder whose text is inserted as typed, never parsed.
   *
   * @param name the placeholder's name
   * @param text the text
   * @return the placeholder
   */
  static TagResolver text(String name, String text) {
    return Placeholder.unparsed(name, text);
  }

  /**
   * A placeholder for a component.
   *
   * @param name the placeholder's name
   * @param component the component
   * @return the placeholder
   */
  static TagResolver component(String name, Component component) {
    return Placeholder.component(name, component);
  }

  /**
   * A count: {@code <count>}, grouped by thousands ({@code 12,345}), and {@code <n:'...'>} to
   * choose the words that agree with it ({@code <n:'0#players|1#player|2#players'>}).
   *
   * @param count the count
   * @return the placeholders
   */
  static TagResolver count(long count) {
    return TagResolver.resolver(
        Placeholder.unparsed("count", number(count)), Formatter.choice("n", count));
  }

  /**
   * Formats a number grouped by thousands, the same on every machine.
   *
   * @param number the number
   * @return such as {@code 12,345}
   */
  static String number(long number) {
    return String.format(Locale.ROOT, "%,d", number);
  }
}
